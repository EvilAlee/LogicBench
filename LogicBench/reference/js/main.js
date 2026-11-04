// src/main.js -- PHASE 4 orchestration for DeldSim Clone.
// No new circuit logic lives here: it only wires together board.js, ics.js,
// netlist.js and wires.js and owns the interaction state machines.

(function () {
  "use strict";

  const SVG_NS = "http://www.w3.org/2000/svg";
  const SETTINGS_STORAGE_KEY = "deldsim.settings.v1";
  const DEFAULT_SIMULATOR_SETTINGS = Object.freeze({ sequentialTTL: true, adderLow: true });
  let simulatorSettings = { ...DEFAULT_SIMULATOR_SETTINGS };

  let pulsePressed = false;
  let legacyPulseButton = null;

  function updateWaveformButtonAppearance() {
    if (!legacyPulseButton) return;
    legacyPulseButton.dataset.pressed = String(pulsePressed);
    legacyPulseButton.setAttribute("aria-pressed", String(pulsePressed));
    legacyPulseButton.setAttribute("opacity", pulsePressed ? "0.72" : "1");
    legacyPulseButton.setAttribute("transform", pulsePressed ? "translate(0 1)" : "");
  }

  function setPulsePressed(pressed) {
    const next = Boolean(pressed);
    if (pulsePressed === next) return false;
    pulsePressed = next;
    if (hasNode("pin-pulse")) Netlist.setSource("pin-pulse", pulsePressed ? 1 : 0);
    updateWaveformButtonAppearance();
    resolveAndRefresh();
    return true;
  }

  function attachPulseButtonListeners() {
    if (!svgRoot) return;
    const buttonSvg = svgRoot.querySelector('svg[viewBox="0 0 184 40"]');
    legacyPulseButton = buttonSvg?.querySelector(".svg_button") || null;
    if (!legacyPulseButton) {
      console.warn("Original GENERATE PULSE button artwork was not found");
      return;
    }

    legacyPulseButton.setAttribute("role", "button");
    legacyPulseButton.setAttribute("tabindex", "0");
    legacyPulseButton.setAttribute("aria-label", "Momentary generate pulse");
    legacyPulseButton.setAttribute("aria-pressed", "false");
    legacyPulseButton.style.cursor = "pointer";

    const press = event => {
      if (event.button !== undefined && event.button !== 0) return;
      event.preventDefault?.();
      setPulsePressed(true);
    };
    const release = () => setPulsePressed(false);

    legacyPulseButton.addEventListener("pointerdown", press);
    legacyPulseButton.addEventListener("pointerup", release);
    legacyPulseButton.addEventListener("pointerleave", release);
    legacyPulseButton.addEventListener("pointercancel", release);
    document.addEventListener("pointerup", release);
    document.addEventListener("pointercancel", release);
    window.addEventListener("blur", release);
    legacyPulseButton.addEventListener("keydown", event => {
      if (event.key === " " || event.key === "Enter") {
        event.preventDefault();
        setPulsePressed(true);
      }
    });
    legacyPulseButton.addEventListener("keyup", event => {
      if (event.key === " " || event.key === "Enter") {
        event.preventDefault();
        setPulsePressed(false);
      }
    });
    updateWaveformButtonAppearance();
  }

  const CLOCK_FREQUENCIES = Object.freeze({
    "sw-clock-10": 10,
    "sw-clock-5": 5,
    "sw-clock-1": 1,
    "sw-clock-0_5": 0.5,
  });
  const clockEnabled = new Map(Object.keys(CLOCK_FREQUENCIES).map(id => [id, false]));
  const clockStartedAt = new Map();
  const clockOutputLevels = new Map(Object.keys(CLOCK_FREQUENCIES).map(id => [id, 0]));
  let clockFrameHandle = null;

  let svgRoot = null;
  let boardContainer = null;

  // ---------------------------------------------------------------------
  // State machines
  // ---------------------------------------------------------------------

  let wireState = {
    mode: "IDLE",     // "IDLE" | "WIRING"
    fromNode: null,   // nodeId of first clicked hole
    fromPt: null,     // SVG point of the first click
  };

  // Wire preview line shown while in WIRING state (PATCH 2).
  let previewLine = null;

  const icState = {
    mode: "IDLE",     // "IDLE" | "PLACING"
    icKey: null,      // e.g. "7408"
  };
  let selectedICKey = null;
  let selectedWire = null;

  // slotIndex (1-5) -> { icId, icKey } — one IC per breadboard base slot.
  const placedICs = new Map();

  // IC base zones in board-SVG coordinates (from index.html:
  // <svg x="N" y="256" width="152" height="168">). Slot i covers
  // [xs[i-1], xs[i-1]+152] x [256, 424].
  const IC_BASE_XS = [136, 424, 712, 1000, 1288];
  const IC_BASE_Y = 256;
  const IC_BASE_W = 152;
  const IC_BASE_H = 168;

  // ---------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------

  function cancelWire() {
    removePreviewLine();
    wireState.mode = "IDLE";
    wireState.fromNode = null;
    wireState.fromPt = null;
  }

  function cancelPlace() {
    icState.mode = "IDLE";
    icState.icKey = null;
    if (boardContainer) boardContainer.classList.remove("placing-ic");
  }

  function cancelAllModes() {
    removePreviewLine();
    wireState = { mode: "IDLE", fromNode: null, fromPt: null };
    icState.mode = "IDLE";
    icState.icKey = null;
    setWireStatus("IDLE", "status-idle");
    if (boardContainer) boardContainer.classList.remove("placing-ic");
  }

  // ------------------------------------------------------------------
  // Wire status indicator + preview line helpers
  // ------------------------------------------------------------------

  function setWireStatus(text, className) {
    const el = document.getElementById("wire-status");
    if (!el) return;
    el.textContent = text;
    el.className = className;
  }

  function createPreviewLine(x1, y1) {
    removePreviewLine();
    previewLine = document.createElementNS(SVG_NS, "line");
    previewLine.setAttribute("x1", x1);
    previewLine.setAttribute("y1", y1);
    previewLine.setAttribute("x2", x1);
    previewLine.setAttribute("y2", y1);
    previewLine.setAttribute("stroke", "#00f9f9");
    previewLine.setAttribute("stroke-width", "2");
    previewLine.setAttribute("stroke-dasharray", "6 3");
    previewLine.setAttribute("pointer-events", "none");
    svgRoot.appendChild(previewLine);
  }

  function removePreviewLine() {
    if (previewLine) {
      previewLine.remove();
      previewLine = null;
    }
  }

  // The single central update for every circuit edit: solve once, then repaint
  // every circuit-driven SVG element from the same resolved result. Never pause
  // LED/switch/display updates while the circuit is active.
  function resolveAndRefresh() {
    const stateMap = Netlist.resolveCircuit();
    try { Wires.updateAllColors(stateMap, window.isPowered !== false); } catch (e) { console.error("updateAllColors failed:", e); }
    try { updateSevenSegmentDisplays(stateMap); } catch (e) { console.error("updateSevenSegmentDisplays failed:", e); }
    try { updateSwitches(stateMap); } catch (e) { console.error("updateSwitches failed:", e); }
    try { renderWireList(); } catch (e) { console.error("renderWireList failed:", e); }
    try { updateAssumptionBadges(); } catch (e) { console.error("updateAssumptionBadges failed:", e); }
    try { renderSimulationNotices(); } catch (e) { console.error("renderSimulationNotices failed:", e); }
    try { renderSimulationStatus(); } catch (e) { console.error("renderSimulationStatus failed:", e); }

    // Paint LEDs last so no other circuit-element refresh can leave a stale
    // indicator behind. This runs after switches, ICs, wires, power and loads.
    try { updateLEDs(stateMap); } catch (e) { console.error("updateLEDs failed:", e); }
    return stateMap;
  }

  // ── Master power (POWER button, top right of board) ──────────────
  window.isPowered = true;

  function togglePower() {
    window.isPowered = !window.isPowered;
    const status = document.getElementById("wire-status");
    if (status) {
      status.textContent = window.isPowered ? "POWER ON" : "POWER OFF";
      status.className = window.isPowered ? "status-idle" : "status-wiring";
    }
    resolveAndRefresh();
  }

  // ---------------------------------------------------------------------
  // LED rendering
  // ---------------------------------------------------------------------
  // On the board an LED is a nested <svg> holding TWO visible <circle>s (an
  // outer ring r=8 and the inner disc r=6) plus a transparent hit <rect> on
  // top. The old updateLEDs only painted that transparent hit rect, so the
  // visible circles never changed colour. We now locate the circles by pure
  // attribute geometry (no getBoundingClientRect / layout dependence), map
  // each one to its netlist node through getNodeAtPoint, and force their
  // colour with inline !important styles so no base SVG/CSS can override it.

  const LED_HIGH_FILL = "#ff0000";
  const LED_HIGH_RING = "#ff6a6a";
  const LED_LOW_FILL  = "#333";

  let ledGlyphs = null; // nodeId -> { disc: SVGCircleElement, ring: SVGCircleElement|null }

  // Map a point from `el`'s own user space up through every nested <svg>
  // (x/y offset + viewBox scale) into root board-SVG coordinates.
  function toBoardPoint(el, x, y) {
    for (let p = el.parentNode; p && p !== svgRoot; p = p.parentNode) {
      if (!p.tagName || p.tagName.toLowerCase() !== "svg") continue;
      const vb = (p.getAttribute("viewBox") || "").trim().split(/[\s,]+/).map(Number);
      const w = parseFloat(p.getAttribute("width"));
      const h = parseFloat(p.getAttribute("height"));
      const hasVB = vb.length === 4 && vb.every(Number.isFinite) && vb[2] > 0 && vb[3] > 0;
      const sx = hasVB && w > 0 ? w / vb[2] : 1;
      const sy = hasVB && h > 0 ? h / vb[3] : 1;
      x = (parseFloat(p.getAttribute("x")) || 0) + (x - (hasVB ? vb[0] : 0)) * sx;
      y = (parseFloat(p.getAttribute("y")) || 0) + (y - (hasVB ? vb[1] : 0)) * sy;
    }
    return { x: x, y: y };
  }

  function buildLedGlyphs() {
    const map = new Map();
    if (!svgRoot) return map;

    // The clickable transparent rectangle is annotated with the exact LED
    // nodeId by annotateBoard(). Use that identity directly instead of
    // reverse-mapping nested SVG coordinates to the nearest board node.
    // Coordinate guessing can shift a glyph onto a neighbouring LED when
    // nested SVG viewBoxes/transforms are involved (the visible LED then
    // appears to respond to a different input).
    for (const nodeId in BOARD_NODE_META) {
      if (BOARD_NODE_META[nodeId].type !== "led") continue;

      const hits = svgRoot.querySelectorAll('[data-node-id="' + nodeId + '"]');
      let glyph = null;
      for (const hit of hits) {
        const unit = hit.closest("svg");
        if (!unit || !svgRoot.contains(unit)) continue;

        const disc = unit.querySelector('circle[r="6"]');
        if (!disc) continue;
        const ring = unit.querySelector('circle[r="8"]');
        glyph = { disc: disc, ring: ring };
        break;
      }
      if (glyph) map.set(nodeId, glyph);
    }

    // Expose only the mapping identities for diagnostics; not the DOM nodes.
    // This also lets browser regression tests ensure every board LED is mapped.
    window.__deldsimLedGlyphIds = Array.from(map.keys());
    return map;
  }

  function paintLed(glyph, high, state) {
    const { disc, ring } = glyph;
    // Inline !important beats presentation attributes AND any stylesheet rule.
    disc.style.setProperty("fill", high ? LED_HIGH_FILL : LED_LOW_FILL, "important");
    disc.style.setProperty("filter", high ? "drop-shadow(0 0 6px " + LED_HIGH_FILL + ")" : "none", "important");
    disc.classList.remove("led-high", "led-low", "led-float");
    disc.classList.add(state);
    if (ring) {
      if (high) ring.style.setProperty("fill", LED_HIGH_RING, "important");
      else ring.style.removeProperty("fill");   // back to the artwork's own dark ring
    }
  }

  function updateLEDs(stateMap) {
    // A board redraw/replacement can invalidate a cached SVG reference. Rebuild
    // the small 40-LED map only when one of its visible circles is detached.
    if (!ledGlyphs || [...ledGlyphs.values()].some(glyph =>
      !glyph.disc?.isConnected || !svgRoot?.contains(glyph.disc))) {
      ledGlyphs = buildLedGlyphs();
    }

    for (const nodeId in BOARD_NODE_META) {
      if (BOARD_NODE_META[nodeId].type !== "led") continue;
      const glyph = ledGlyphs.get(nodeId);
      if (!glyph) continue;

      // Read the resolved electrical net directly at paint time. The wire
      // color is only a visual hint; the LED must follow its own final net.
      const v = typeof Netlist.getNetValue === "function"
        ? Netlist.getNetValue(nodeId)
        : (stateMap?.get ? stateMap.get(nodeId) : stateMap?.[nodeId]);

      if (v === 1) paintLed(glyph, true, "led-high");
      else paintLed(glyph, false, v === 0 ? "led-low" : "led-float");
    }
  }

  // Each built-in digit accepts four binary inputs (bit weights 1, 2, 4, 8)
  // and internally decodes hexadecimal 0-F. No external segment/COM pads exist.
  function updateSevenSegmentDisplays(stateMap) {
    if (!svgRoot) return;
    const digits = [{ name: "tens", x: 1328 }, { name: "ones", x: 1408 }];
    const segmentNames = ["a", "b", "c", "d", "e", "f", "g"];
    for (const digit of digits) {
      const display = svgRoot.querySelector(
        'svg[viewBox="0 0 64 80"][x="' + digit.x + '"][y="8"]'
      );
      if (!display) continue;
      const bits = [1, 2, 3, 4].map(pin =>
        Netlist.getNetValue("pin-seg-digit-" + digit.name + "-" + pin));
      const onSegments = new Set();
      let decodedValue = null, hexLetter = false;
      if (bits.every(value => value === 0 || value === 1)) {
        decodedValue = bits.reduce((n, bit, i) => n | (bit << i), 0);
        const pattern = SEVEN_SEGMENT_HEX_ACTIVE_LOW[decodedValue];
        if (pattern) {
          pattern.forEach((off, index) => { if (off === 0) onSegments.add(segmentNames[index]); });
          hexLetter = decodedValue >= 10;
        }
      }
      display.dataset.hexValue = decodedValue === null ? "" : String(decodedValue);
      display.dataset.hexLetter = String(hexLetter);
      for (const name of segmentNames) {
        const path = display.querySelector('path[data-segment="' + name + '"]');
        if (!path) continue;
        const enabled = onSegments.has(name);
        path.style.setProperty("fill", enabled ? "#ff2a2a" : "#1a1a1a", "important");
        path.style.setProperty("filter", enabled ? "drop-shadow(0 0 2px #ff2a2a)" : "none", "important");
        path.dataset.segmentState = enabled ? "on" : "off";
      }
      const decimalPoint = display.querySelector('circle[cx="48"][cy="48"]');
      if (decimalPoint) {
        decimalPoint.dataset.hexLetterDot = String(hexLetter);
        decimalPoint.style.setProperty("fill", hexLetter ? "#ff2a2a" : "#1a1a1a", "important");
        decimalPoint.style.setProperty("filter", hexLetter ? "drop-shadow(0 0 2px #ff2a2a)" : "none", "important");
      }
    }
  }

  // Keep the original vertical toggle artwork. Clock switches show their
  // enable state instead of following the instantaneous square-wave level.
  function updateSwitches(stateMap) {
    for (const nodeId in BOARD_NODE_META) {
      if (BOARD_NODE_META[nodeId]?.type !== "switch") continue;
      const enabled = Object.prototype.hasOwnProperty.call(CLOCK_FREQUENCIES, nodeId)
        ? clockEnabled.get(nodeId) === true
        : stateMap.get(nodeId) === 1;

      svgRoot.querySelectorAll(`.svg_toggle_switch_notch[data-node-id="${nodeId}"]`)
        .forEach(notch => {
          const geometry = window.__notchGeom?.get(notch);
          if (geometry) {
            const cy = enabled ? geometry.cyOff - geometry.travel : geometry.cyOff;
            notch.setAttribute("cy", String(cy));
          }
          const radius = Number(notch.getAttribute("r")) || 0;
          notch.setAttribute("fill", radius >= 7 && enabled ? "#00ff88" : "#ffffff");
        });
    }
  }

  // ---------------------------------------------------------------------
  // Node registration (init step 3-4)
  // ---------------------------------------------------------------------

  function registerBoardNodes() {
    resetClockControls();

    // Permanent supply nodes.
    Netlist.addNode("vcc-rail", "vcc", {});
    Netlist.addNode("gnd-rail", "gnd", {});

    // Every connectable point on the board becomes a netlist node so that
    // wires can terminate anywhere (holes included). Types map straight
    // from BOARD_NODE_META; "hole" points are passive ("wire_term").
    for (const nodeId in BOARD_NODE_META) {
      const t = BOARD_NODE_META[nodeId].type;
      if (t === "switch") {
        Netlist.addNode(nodeId, "switch", { meta: BOARD_NODE_META[nodeId] });
        Netlist.setSwitch(nodeId, 0);            // default value 0
      } else if (t === "led") {
        Netlist.addNode(nodeId, "led", { meta: BOARD_NODE_META[nodeId] });
      } else if (t === "vcc") {
        Netlist.addNode(nodeId, "vcc", { meta: BOARD_NODE_META[nodeId] });
      } else if (t === "gnd") {
        Netlist.addNode(nodeId, "gnd", { meta: BOARD_NODE_META[nodeId] });
      } else if (t === "hole") {
        Netlist.addNode(nodeId, "wire_term", { meta: BOARD_NODE_META[nodeId] });
      }
      // "button" types carry no signal — skip.
    }

    // Dedicated momentary pulse source (not the old btn-pulse artwork node).
    if (hasNode("pin-pulse")) Netlist.setSource("pin-pulse", 0);
    pulsePressed = false;
    updateWaveformButtonAppearance();

    // Fixed panel indicators are simulator-owned permanent connections.
    // Unlike channel straps, they are not removed by Clear All Wires.
    const tieIfPresent = (a, b) => {
      if (hasNode(a) && hasNode(b)) Netlist.addPermanentWire(a, b);
    };

    // Use the real panel source nodes. vcc-rail/gnd-rail are internal
    // Netlist nodes and intentionally do not pass board.js hasNode().
    tieIfPresent("pin-out-vcc", "led-out-vcc");
    tieIfPresent("pin-gnd", "led-gnd");
    tieIfPresent("pin-high", "led-high");
    tieIfPresent("pin-low", "led-low");

    // The board channel straps are internal Netlist edges, never visual
    // jumper wires. They are restored after Clear All Wires and whenever
    // Netlist.clear() is followed by this full node-registration pass.
    registerBoardStraps();
  }

  function registerBoardStraps() {
    const strapIfPresent = (a, b) => {
      if (hasNode(a) && hasNode(b)) Netlist.addWire(a, b);
    };

    // Each input switch, terminal rod and indicator LED is one electrical net.
    for (let i = 0; i <= 15; i++) {
      const sw = "sw-input-" + i;
      strapIfPresent(sw, "pin-input-" + i);
      strapIfPresent(sw, "led-input-" + i);
    }

    // Clock controls are manual logic sources in this simulator.
    for (const rate of ["0_5", "1", "5", "10"]) {
      const sw = "sw-clock-" + rate;
      strapIfPresent(sw, "pin-clock-" + rate);
      strapIfPresent(sw, "led-clock-" + rate);
    }

    // Output terminal rods and their visible LED indicators are one net.
    for (let i = 0; i <= 15; i++) {
      strapIfPresent("pin-out-" + i, "led-out-" + i);
    }
  }

  // ---------------------------------------------------------------------
  // Switch handling
  // ---------------------------------------------------------------------

  function resetClockControls() {
    if (clockFrameHandle !== null) {
      window.cancelAnimationFrame(clockFrameHandle);
      clockFrameHandle = null;
    }
    for (const nodeId of Object.keys(CLOCK_FREQUENCIES)) {
      clockEnabled.set(nodeId, false);
      clockStartedAt.delete(nodeId);
      clockOutputLevels.set(nodeId, 0);
    }
  }

  function setClockEnabled(nodeId, enabled) {
    if (!Object.prototype.hasOwnProperty.call(CLOCK_FREQUENCIES, nodeId)) return false;
    const nextEnabled = Boolean(enabled);
    clockEnabled.set(nodeId, nextEnabled);
    if (nextEnabled) {
      clockStartedAt.set(nodeId, performance.now());
      clockOutputLevels.set(nodeId, 1);
      Netlist.setSwitch(nodeId, 1);
    } else {
      clockStartedAt.delete(nodeId);
      clockOutputLevels.set(nodeId, 0);
      Netlist.setSwitch(nodeId, 0);
    }
    return true;
  }

  // One shared requestAnimationFrame loop services all enabled oscillators.
  // Each frame calculates phase from performance.now() and solves the circuit
  // only once if one or more clock source levels actually changed.
  function scheduleClockFrame() {
    if (clockFrameHandle !== null) return;
    if (![...clockEnabled.values()].some(Boolean)) return;
    clockFrameHandle = window.requestAnimationFrame(runClockFrame);
  }

  function runClockFrame() {
    clockFrameHandle = null;
    const now = performance.now();
    let anyEnabled = false;
    let anyChanged = false;

    for (const [nodeId, frequency] of Object.entries(CLOCK_FREQUENCIES)) {
      if (clockEnabled.get(nodeId) !== true) continue;
      anyEnabled = true;
      const startedAt = clockStartedAt.get(nodeId);
      if (!Number.isFinite(startedAt)) continue;

      const halfPeriodMs = 1000 / (2 * frequency);
      const halfCycles = Math.floor((now - startedAt) / halfPeriodMs);
      const level = halfCycles % 2 === 0 ? 1 : 0;
      if (clockOutputLevels.get(nodeId) !== level) {
        clockOutputLevels.set(nodeId, level);
        Netlist.setSwitch(nodeId, level);
        anyChanged = true;
      }
    }

    if (anyChanged) resolveAndRefresh();
    if (anyEnabled) scheduleClockFrame();
  }

  function handleSwitchClick(nodeId) {
    if (Object.prototype.hasOwnProperty.call(CLOCK_FREQUENCIES, nodeId)) {
      setClockEnabled(nodeId, !clockEnabled.get(nodeId));
      resolveAndRefresh();
      scheduleClockFrame();
      return;
    }

    const current = Netlist.getNodeValue(nodeId);
    Netlist.setSwitch(nodeId, current === 1 ? 0 : 1);
    resolveAndRefresh();
  }

  // ---------------------------------------------------------------------
  // Hole handling — wire placement state machine
  // ---------------------------------------------------------------------

  // Physical-tie map: IC pin node -> breadboard hole (pin-in-*/pin-out-* ->
  // hole-bbN-*). Populated on IC placement, cleaned on removeIC. The UI
  // treats an IC pin exactly like its underlying hole: clicks are translated
  // to the hole ID before reaching the router, so a visual wire is NEVER
  // attempted between a pin and its own hole (zero distance + hitbox trips
  // WARN-FALLBACK). The netlist keeps the logical connection through the
  // auto-tie wire placed at IC-insertion time.
  const icTiedPairs = new Map();

  // Translate any endpoint to its canonical routing id (hole for IC pins).
  function routeIdFor(nodeId) {
    const hole = icTiedPairs.get(nodeId);
    return (hole && hasNode(hole)) ? hole : nodeId;
  }

  function handleHoleClick(nodeId, svgPt) {
    if (!nodeId || !hasNode(nodeId)) return;

    // Clicking an IC pin behaves exactly like clicking the hole under it.
    // Clicking an occupied hole already resolves to the hole — keep it.
    nodeId = routeIdFor(nodeId);

    if (wireState.mode === "IDLE") {
      // Start wire
      wireState = { mode: "WIRING", fromNode: nodeId, fromPt: svgPt };
      createPreviewLine(svgPt.x, svgPt.y);
      setWireStatus("WIRING...", "status-wiring");
      return;
    }

    // WIRING — complete wire
    removePreviewLine();
    setWireStatus("IDLE", "status-idle");

    if (nodeId === wireState.fromNode) {
      // Same physical point (e.g. started on pin-in-X then clicked its hole,
      // or double-clicked one location) — cancel, never route.
      wireState = { mode: "IDLE", fromNode: null, fromPt: null };
      return;
    }

    // Belt-and-braces: refuse any residual pin<->own-hole pair.
    if (icTiedPairs.get(wireState.fromNode) === nodeId ||
        icTiedPairs.get(nodeId) === wireState.fromNode) {
      wireState = { mode: "IDLE", fromNode: null, fromPt: null };
      return;
    }

    // The visible rod and LED circle on one channel are already joined
    // by an invisible strap. Reject that duplicate endpoint pair quietly.
    if (Rules?.isSameTerminalPair?.(wireState.fromNode, nodeId)) {
      wireState = { mode: "IDLE", fromNode: null, fromPt: null };
      return;
    }

    // Draw wire if not already connected (duplicate guard via registry key).
    const existing = Wires.getWireElement(wireState.fromNode, nodeId);
    if (!existing) {
      const from = wireState.fromNode;

      // Electrical validation: reject illegal pairs without creating a wire.
      // Validate against the ORIGINAL clicked pin (or hole) so netlist-level
      // rules still apply; the hole is electrically the pin anyway.
      if (typeof Rules !== 'undefined' && Rules && !Rules.canConnect(from, nodeId)) {
        console.warn('Invalid Connection');
        flashInvalid(nodeId);
        wireState = { mode: "IDLE", fromNode: null, fromPt: null };
        return;
      }

      // Router works on holes only (visual endpoints). For the netlist,
      // prefer the IC-pin id of the destination hole when one exists, so
      // switch->IC-pin wiring registers against the datasheet pin name
      // while the drawn wire terminates at the hole coordinates.
      let netFrom = from, netTo = nodeId;
      const destPin = [...icTiedPairs.entries()].find(([, h]) => h === nodeId);
      if (destPin) netTo = destPin[0];
      const srcPin = [...icTiedPairs.entries()].find(([, h]) => h === from);
      if (srcPin) netFrom = srcPin[0];

      Netlist.addWire(netFrom, netTo);
      const wireEl = Wires.drawWire(from, nodeId);
      if (!wireEl) {
        // Router found no legal path: undo the electrical connection so the
        // netlist never holds a wire that is not drawn.
        Netlist.removeWire(netFrom, netTo);
        console.warn('No legal route for', from, '<->', nodeId);
        flashInvalid(nodeId);
        wireState = { mode: "IDLE", fromNode: null, fromPt: null };
        resolveAndRefresh();
        return;
      }
    }

    wireState = { mode: "IDLE", fromNode: null, fromPt: null };
    resolveAndRefresh();
  }

  // Brief red flash on the rejected node's board element(s), ~300ms.
  function flashInvalid(nodeId) {
    const els = document.querySelectorAll(`[data-node-id="${nodeId}"]`);
    els.forEach(el => { el.style.filter = 'drop-shadow(0 0 6px #ff2a2a)'; });
    setTimeout(() => {
      els.forEach(el => { el.style.filter = ''; });
    }, 300);
  }

  // ---------------------------------------------------------------------
  // IC placement
  // ---------------------------------------------------------------------

  function populateICList() {
    const list = document.getElementById("ic-list");
    if (!list) return;
    list.innerHTML = "";
    for (const key in IC_LIBRARY) {
      const li = document.createElement("li");
      li.textContent = key + " — " + IC_LIBRARY[key].name;
      li.dataset.ickey = key;
      li.setAttribute("role", "button");
      li.setAttribute("tabindex", "0");
      li.setAttribute("aria-label", "Select " + key + ": " + IC_LIBRARY[key].name);
      // Render the datasheet on selection, not every time the pointer moves
      // across the component list. This avoids repeated DOM construction.
      li.addEventListener("click", () => startPlacingIC(key));
      li.addEventListener("keydown", event => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          startPlacingIC(key);
        }
      });
      list.appendChild(li);
    }
  }

  function showPinDiagram(icKey) {
    const viewer = document.getElementById("pin-viewer");
    if (!viewer) return;
    const ic = icKey && IC_LIBRARY[icKey];
    viewer.replaceChildren();
    viewer.classList.add("ic-datasheet");

    if (!ic) {
      const hint = document.createElement("p");
      hint.className = "hint";
      hint.textContent = "Hover or select an IC to inspect its pins and logic behavior.";
      viewer.appendChild(hint);
      return;
    }

    const inputs = [];
    const outputs = [];
    const behavior = [];
    const addPin = (rows, pin, label) => {
      if (!Number.isInteger(pin) || rows.some(row => row.pin === pin)) return;
      rows.push({ pin, label });
    };

    let purpose = "TTL logic device";
    if (ic.gates) {
      purpose = "Combinational logic";
      const descriptions = {
        AND: "HIGH only when every input is HIGH.",
        OR: "HIGH when at least one input is HIGH.",
        NOT: "The output is the inverse of its input.",
        NAND: "LOW only when every input is HIGH.",
        NOR: "HIGH only when every input is LOW.",
        XOR: "HIGH when an odd number of inputs are HIGH (parity).",
      };
      ic.gates.forEach((gate, index) => {
        gate.inputs.forEach((pin, inputIndex) => {
          const inputName = gate.inputs.length === 1 ? "IN" : ["A", "B", "C", "D", "E", "F", "G", "H"][inputIndex];
          addPin(inputs, pin, `Gate ${index + 1} · ${inputName}`);
        });
        addPin(outputs, gate.output, `Gate ${index + 1} · OUT`);
        behavior.push(`Gate ${index + 1} (${gate.fn}): ${descriptions[gate.fn] || "Output follows the listed logic function."}`);
      });
    }

    if (ic.flipFlops) {
      purpose = "Sequential storage";
      ic.flipFlops.forEach((ff, index) => {
        const n = index + 1;
        addPin(inputs, ff.clock, `FF${n} · CLK`);
        if (Number.isInteger(ff.data)) addPin(inputs, ff.data, `FF${n} · D`);
        if (Number.isInteger(ff.j)) addPin(inputs, ff.j, `FF${n} · J`);
        if (Number.isInteger(ff.k)) addPin(inputs, ff.k, `FF${n} · K`);
        if (Number.isInteger(ff.preset)) addPin(inputs, ff.preset, `FF${n} · PRE̅`);
        if (Number.isInteger(ff.clear)) addPin(inputs, ff.clear, `FF${n} · CLR̅`);
        addPin(outputs, ff.q, `FF${n} · Q`);
        addPin(outputs, ff.qbar, `FF${n} · Q̅`);
        const controls = [
          Number.isInteger(ff.preset) ? "PRE̅ (active LOW)" : null,
          Number.isInteger(ff.clear) ? "CLR̅ (active LOW)" : null,
        ].filter(Boolean);
        const trigger = ff.edge === "falling" ? "HIGH-to-LOW (falling)" : "LOW-to-HIGH (rising)";
        const controlText = controls.length
          ? controls.join(" and ") + " are asynchronous controls."
          : "No asynchronous preset or clear input.";
        if (ff.type === "D") {
          behavior.push(`Flip-flop ${n}: Q captures D on the ${trigger} clock transition. ${controlText}`);
        } else {
          behavior.push(`Flip-flop ${n}: JK=00 holds, 01 resets, 10 sets, 11 toggles on the ${trigger} clock transition. ${controlText}`);
        }
      });
    }

    if (ic.adder) {
      purpose = "4-bit binary full adder";
      ic.adder.a.forEach((pin, i) => addPin(inputs, pin, `A${i}${i === 0 ? " · LSB" : ""}`));
      ic.adder.b.forEach((pin, i) => addPin(inputs, pin, `B${i}${i === 0 ? " · LSB" : ""}`));
      addPin(inputs, ic.adder.carryIn, "CARRY IN");
      ic.adder.sum.forEach((pin, i) => addPin(outputs, pin, `SUM${i}${i === 0 ? " · LSB" : ""}`));
      addPin(outputs, ic.adder.carryOut, "CARRY OUT");
      behavior.push("Adds unsigned A[3:0] and B[3:0] plus carry-in. SUM0 is the least-significant result bit; carry-out is the fifth result bit.");
    }

    if (ic.counter) {
      purpose = icKey === "7490" ? "Decade ripple counter" : "4-bit binary ripple counter";
      const c = ic.counter;
      addPin(inputs, c.clockA, "CLOCK A · FALLING");
      addPin(inputs, c.clockB, "CLOCK B · FALLING");
      (c.resetZero || []).forEach((pin, index) =>
        addPin(inputs, pin, "RESET TO ZERO " + (index + 1) + " · ACTIVE HIGH"));
      (c.presetNine || []).forEach((pin, index) =>
        addPin(inputs, pin, "PRESET TO NINE " + (index + 1) + " · ACTIVE HIGH"));
      ["QA", "QB", "QC", "QD"].forEach((name, index) =>
        addPin(outputs, c.outputs[index], name));
      behavior.push("The A section divides by 2; the B section is clocked separately.");
      behavior.push("For the full count sequence, connect QA to CLOCK B and apply pulses to CLOCK A.");
      behavior.push("Counter state is unknown until a valid reset or preset establishes it.");
      if (c.upperModulus === 5) behavior.push("The upper section cycles through five states (decade mode).");
      if (c.upperModulus === 8) behavior.push("The upper section cycles through eight states (four-bit binary mode).");
    }

    if (ic.decoder3to8) {
      purpose = "3-to-8 line decoder / demultiplexer";
      ic.decoder3to8.address.forEach((pin, i) => addPin(inputs, pin, `A${i} · ADDRESS`));
      ic.decoder3to8.enableLow.forEach((pin, i) => addPin(inputs, pin, i === 0 ? "G2A · ACTIVE LOW" : "G2B · ACTIVE LOW"));
      addPin(inputs, ic.decoder3to8.enableHigh, "G1 · ACTIVE HIGH");
      ic.decoder3to8.outputs.forEach((pin, i) => addPin(outputs, pin, `Y${i} · ACTIVE LOW`));
      behavior.push("Enabled when G1=1 and both G2 inputs=0. The selected Y output goes LOW; all unselected Y outputs remain HIGH.");
      behavior.push("Address is binary A2 A1 A0, with A0 as the least-significant bit.");
    }

    const heading = document.createElement("div");
    heading.className = "ds-heading";
    const titleGroup = document.createElement("div");
    const eyebrow = document.createElement("div");
    eyebrow.className = "ds-eyebrow";
    eyebrow.textContent = "IC REFERENCE";
    const title = document.createElement("h3");
    title.textContent = icKey;
    const subtitle = document.createElement("p");
    subtitle.textContent = ic.name;
    titleGroup.append(eyebrow, title, subtitle);
    const packageTag = document.createElement("span");
    packageTag.className = "ds-package";
    packageTag.textContent = `DIP-${ic.pins}`;
    heading.append(titleGroup, packageTag);
    viewer.appendChild(heading);

    const purposeLine = document.createElement("p");
    purposeLine.className = "ds-purpose";
    purposeLine.textContent = purpose;
    viewer.appendChild(purposeLine);
    const power = document.createElement("div");
    power.className = "ds-power";
    power.textContent = `POWER · VCC pin ${ic.vcc} · GND pin ${ic.gnd}`;
    viewer.appendChild(power);

    function appendPinSection(label, rows, kind) {
      const section = document.createElement("section");
      section.className = "ds-section";
      const sectionTitle = document.createElement("h4");
      sectionTitle.textContent = label;
      const grid = document.createElement("div");
      grid.className = "ds-pin-grid";
      rows.forEach(row => {
        const chip = document.createElement("div");
        chip.className = "ds-pin-chip " + kind;
        const number = document.createElement("strong");
        number.textContent = "P" + row.pin;
        const signal = document.createElement("span");
        signal.textContent = row.label;
        chip.append(number, signal);
        grid.appendChild(chip);
      });
      section.append(sectionTitle, grid);
      viewer.appendChild(section);
    }
    appendPinSection("Inputs", inputs, "input");
    appendPinSection("Outputs", outputs, "output");

    if (behavior.length) {
      const section = document.createElement("section");
      section.className = "ds-section";
      const sectionTitle = document.createElement("h4");
      sectionTitle.textContent = "Behavior";
      const list = document.createElement("ul");
      list.className = "ds-behavior";
      behavior.forEach(description => {
        const item = document.createElement("li");
        item.textContent = description;
        list.appendChild(item);
      });
      section.append(sectionTitle, list);
      viewer.appendChild(section);
    }

    const pinDetails = document.createElement("details");
    pinDetails.className = "ds-full-pinout";
    const summary = document.createElement("summary");
    summary.textContent = "Full pin map";
    pinDetails.appendChild(summary);
    const table = document.createElement("table");
    const head = document.createElement("tr");
    ["Pin", "Direction", "Signal"].forEach(label => {
      const th = document.createElement("th");
      th.textContent = label;
      head.appendChild(th);
    });
    const thead = document.createElement("thead");
    thead.appendChild(head);
    const tbody = document.createElement("tbody");
    for (let pin = 1; pin <= ic.pins; pin++) {
      const input = inputs.find(row => row.pin === pin);
      const output = outputs.find(row => row.pin === pin);
      let direction = "—", signal = "Not described";
      if (pin === ic.vcc) { direction = "POWER"; signal = "VCC"; }
      else if (pin === ic.gnd) { direction = "POWER"; signal = "GND"; }
      else if (input && output) { direction = "I/O"; signal = input.label + " / " + output.label; }
      else if (input) { direction = "INPUT"; signal = input.label; }
      else if (output) { direction = "OUTPUT"; signal = output.label; }
      const row = document.createElement("tr");
      [String(pin), direction, signal].forEach(value => {
        const cell = document.createElement("td");
        cell.textContent = value;
        row.appendChild(cell);
      });
      tbody.appendChild(row);
    }
    table.append(thead, tbody);
    pinDetails.appendChild(table);
    viewer.appendChild(pinDetails);
  }

  function startPlacingIC(icKey) {
    cancelAllModes();
    selectedICKey = icKey;
    document.querySelectorAll("#ic-list li").forEach(item => {
      item.classList.toggle("selected", item.dataset.ickey === icKey);
    });
    showPinDiagram(icKey);
    icState.mode = "PLACING";
    icState.icKey = icKey;
    if (boardContainer) boardContainer.classList.add("placing-ic");
    setWireStatus("Place " + icKey, "status-wiring");
  }

  // Which slot (1-5) contains this board-SVG point? null if outside all bases.
  function getSlotFromPoint(x, y) {
    for (let i = 0; i < IC_BASE_XS.length; i++) {
      if (x >= IC_BASE_XS[i] && x <= IC_BASE_XS[i] + IC_BASE_W &&
          y >= IC_BASE_Y  && y <= IC_BASE_Y + IC_BASE_H) {
        return i + 1;
      }
    }
    return null;
  }

  // hole-bb1-L1 → slot 1, hole-bb2-R7 → slot 2, etc.
  function getSlotFromNodeId(nodeId) {
    const m = nodeId && nodeId.match(/hole-bb(\d+)/);
    return m ? parseInt(m[1], 10) : null;
  }

  function handleICBaseClick(slotIndex) {
    if (icState.mode !== "PLACING") return;
    if (placedICs.has(slotIndex)) {
      console.log("Slot already occupied");
      cancelAllModes();
      return;
    }
    createICAtSlot(slotIndex, icState.icKey);
    cancelAllModes();
    resolveAndRefresh();
  }

  // One package-aware placement path for interactive placement and circuit
  // restore. The physical DIP position comes from the real pin count.
  function createICAtSlot(slotIndex, icKey, forcedId) {
    if (placedICs.has(slotIndex)) throw new Error("IC slot " + slotIndex + " is occupied");
    const def = IC_LIBRARY[icKey];
    if (!def) throw new Error("Unknown IC: " + icKey);
    const pinCount = def.pins;
    const pinNodeMap = getICPinHoleMap(slotIndex, pinCount);
    const pinIds = {};

    const inputPins = new Set(def.inputPins || []);
    const outputPins = new Set(def.outputPins || []);
    for (const gate of (def.gates || [])) {
      gate.inputs.forEach(pin => inputPins.add(pin));
      outputPins.add(gate.output);
    }
    if (def.adder) {
      def.adder.a.concat(def.adder.b, [def.adder.carryIn]).forEach(pin => inputPins.add(pin));
      def.adder.sum.concat([def.adder.carryOut]).forEach(pin => outputPins.add(pin));
    }
    if (def.decoder3to8) {
      def.decoder3to8.address.concat(def.decoder3to8.enableLow, [def.decoder3to8.enableHigh])
        .forEach(pin => inputPins.add(pin));
      def.decoder3to8.outputs.forEach(pin => outputPins.add(pin));
    }
    if (def.counter) {
      [def.counter.clockA, def.counter.clockB]
        .concat(def.counter.resetZero || [], def.counter.presetNine || [])
        .filter(Number.isInteger).forEach(pin => inputPins.add(pin));
      def.counter.outputs.forEach(pin => outputPins.add(pin));
    }
    for (const ff of (def.flipFlops || [])) {
      [ff.clock, ff.data, ff.j, ff.k, ff.preset, ff.clear]
        .filter(Number.isInteger).forEach(pin => inputPins.add(pin));
      outputPins.add(ff.q);
      outputPins.add(ff.qbar);
    }

    const icId = forcedId || ("ic-" + slotIndex + "-" + Date.now());
    for (const pinText of Object.keys(pinNodeMap)) {
      const pin = Number(pinText);
      const name = outputPins.has(pin) ? `pin-out-${slotIndex}-${pin}`
        : inputPins.has(pin) ? `pin-in-${slotIndex}-${pin}`
        : `pin-${slotIndex}-${pin}`;
      Netlist.addNode(name, "wire_term", { meta: BOARD_NODE_META[pinNodeMap[pin]] || {} });
      Netlist.addPermanentWire(name, pinNodeMap[pin]);
      pinIds[pin] = name;
      icTiedPairs.set(name, pinNodeMap[pin]);
    }

    Netlist.placeIC(icId, icKey, pinIds);
    placedICs.set(slotIndex, { icId, icKey, pinIds });

    try {
      if (typeof Wires !== "undefined" && Wires.registerRoutingPoints) {
        const list = [];
        for (const pinText of Object.keys(pinNodeMap)) {
          const pt = getPointForNode(pinNodeMap[pinText]);
          if (pt) list.push({ id: pinIds[pinText], x: pt.x, y: pt.y });
        }
        Wires.registerRoutingPoints(list);
      }
    } catch (_) { /* coordinate-free test harness */ }

    renderICOverlay(icId, icKey, slotIndex);
    renderPlacedICList();
    return icId;
  }

  function renderICOverlay(icId, icKey, slotIndex) {
    const x = IC_BASE_XS[slotIndex - 1];
    const y = IC_BASE_Y;

    const g = document.createElementNS(SVG_NS, "g");
    g.dataset.icid = icId;
    g.dataset.slot = String(slotIndex);
    g.setAttribute("class", "ic-overlay");
    // Overlay must never swallow clicks — pin holes sit underneath it.
    // IC removal is handled by coordinate hit-testing in the svgRoot
    // contextmenu listener instead of an element listener on this group.
    g.setAttribute("pointer-events", "none");

    // IC body height follows the real package pin count. Each side uses
    // half the pins and adjacent breadboard rows are 16 SVG units apart.
    const pinCount = IC_LIBRARY[icKey]?.pins || 20;
    const half = Math.ceil(pinCount / 2);
    const bodyHeight = (half - 1) * 16 + 8;
    const body = document.createElementNS(SVG_NS, "rect");
    body.setAttribute("x", x + 46);
    body.setAttribute("y", y + 4);
    body.setAttribute("width",  60);
    body.setAttribute("height", bodyHeight);
    body.setAttribute("fill", "#1a1a1a");
    body.setAttribute("rx", "4");
    body.setAttribute("stroke", "#444");
    body.setAttribute("stroke-width", "1");
    g.appendChild(body);

    // IC name label, rotated 90° like the factory silkscreen.
    const cx = x + 76, cy = y + 4 + bodyHeight / 2;
    const label = document.createElementNS(SVG_NS, "text");
    label.setAttribute("x", cx);
    label.setAttribute("y", cy);
    label.setAttribute("text-anchor", "middle");
    label.setAttribute("dominant-baseline", "middle");
    label.setAttribute("fill", "#fff");
    label.setAttribute("font-size", "12");
    label.setAttribute("font-family", "monospace");
    label.setAttribute("transform", `rotate(90,${cx},${cy})`);
    label.textContent = icKey;
    g.appendChild(label);

    // The factory SVG has generic 20-position socket labels. Overlay the
    // actual datasheet pin numbers for this package so a DIP-14 does not
    // visually claim that its upper-right supply pin is pin 20.
    const addPinLabel = (pin, px, py, anchor) => {
      const text = document.createElementNS(SVG_NS, "text");
      text.dataset.icPin = String(pin);
      text.setAttribute("x", px);
      text.setAttribute("y", py);
      text.setAttribute("text-anchor", anchor);
      text.setAttribute("dominant-baseline", "middle");
      text.setAttribute("font-size", "10");
      text.setAttribute("font-family", "monospace");
      text.setAttribute("fill", "#fff");
      text.setAttribute("stroke", "#2498bd");
      text.setAttribute("stroke-width", "2.5");
      text.setAttribute("paint-order", "stroke");
      text.textContent = String(pin);
      g.appendChild(text);
    };
    for (let pin = 1; pin <= half; pin++) {
      addPinLabel(pin, x + 16, y + 8 + (pin - 1) * 16, "end");
    }
    for (let pin = half + 1; pin <= pinCount; pin++) {
      const rowFromTop = pinCount - pin;
      addPinLabel(pin, x + 136, y + 8 + rowFromTop * 16, "start");
    }

    const assumedBadge = document.createElementNS(SVG_NS, "g");
    assumedBadge.setAttribute("class", "assumed-input-badge");
    assumedBadge.style.display = "none";
    const badgeRect = document.createElementNS(SVG_NS, "rect");
    badgeRect.setAttribute("x", x + 68);
    badgeRect.setAttribute("y", y + 1);
    badgeRect.setAttribute("width", 36);
    badgeRect.setAttribute("height", 12);
    badgeRect.setAttribute("rx", 4);
    badgeRect.setAttribute("fill", "#123f49");
    badgeRect.setAttribute("stroke", "#43c9d4");
    badgeRect.setAttribute("stroke-width", "0.8");
    const badgeText = document.createElementNS(SVG_NS, "text");
    badgeText.setAttribute("x", x + 86);
    badgeText.setAttribute("y", y + 9);
    badgeText.setAttribute("text-anchor", "middle");
    badgeText.setAttribute("font-size", "6");
    badgeText.setAttribute("font-family", "monospace");
    badgeText.setAttribute("font-weight", "bold");
    badgeText.setAttribute("fill", "#d7fbff");
    badgeText.textContent = "ASSUMED";
    assumedBadge.append(badgeRect, badgeText);
    g.appendChild(assumedBadge);

    svgRoot.appendChild(g);
  }

  function removeIC(icId, slotIndex) {
    const entry = placedICs.get(slotIndex);
    const pinNodes = Netlist.getICPinNodes(icId) || {};
    const pinIds = (entry && entry.pinIds) ? Object.values(entry.pinIds) : [];

    // Drawn wires end at the breadboard HOLE under a pin, while netlist edges
    // may end at the pin node (pin-in/out-*). Jumper wires physically stay in
    // their holes when a chip is pulled, so move each pin's netlist edges back
    // onto the hole instead of deleting them. This keeps drawn wires and the
    // netlist consistent (no orphan wires, no ghost connections).
    pinIds.forEach(pinId => {
      const hole = icTiedPairs.get(pinId);
      Netlist.getConnected(pinId).slice().forEach(other => {
        if (hole && other === hole) {
          // Remove the simulator-owned physical pin-to-hole tie explicitly.
          Netlist.removePermanentWire(pinId, other);
        } else {
          // User jumpers remain attached to the hole after IC removal.
          Netlist.removeWire(pinId, other);
          if (hole) Netlist.addWire(hole, other);
        }
      });
    });
    pinIds.forEach(id => Netlist.removeNode(id));
    // Free the physical-tie entries (this is what marks the holes empty).
    [...pinIds, ...Object.values(pinNodes)].forEach(id => icTiedPairs.delete(id));
    Netlist.removeIC(icId);
    placedICs.delete(slotIndex);
    // Remove SVG overlay.
    const overlay = document.querySelector(`[data-icid="${icId}"]`);
    if (overlay) overlay.remove();
    renderPlacedICList();
    resolveAndRefresh();
  }

  // Keep the currently placed chips visible in the inspector so they can be
  // removed without requiring a right-click on the board.
  function renderPlacedICList() {
    const list = document.getElementById("placed-ic-list");
    if (!list) return;
    list.replaceChildren();

    if (!placedICs.size) {
      const empty = document.createElement("span");
      empty.className = "placed-ics-empty";
      empty.textContent = "No ICs placed";
      list.appendChild(empty);
      return;
    }

    for (const [slot, entry] of [...placedICs.entries()].sort((a, b) => a[0] - b[0])) {
      const item = document.createElement("div");
      item.className = "ic-manager-item";
      const label = document.createElement("span");
      label.className = "ic-manager-label";
      label.textContent = "Slot " + slot + " · " + entry.icKey;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "ic-remove-button";
      remove.dataset.removeSlot = String(slot);
      remove.textContent = "Remove";
      remove.setAttribute("aria-label", "Remove " + entry.icKey + " from slot " + slot);
      remove.addEventListener("click", () => {
        const current = placedICs.get(slot);
        if (!current || current.icId !== entry.icId) return;
        cancelAllModes();
        removeIC(current.icId, slot);
      });
      item.append(label, remove);
      list.appendChild(item);
    }
  }

  function getEndpointDisplayName(nodeId) {
    if (nodeId === "pin-out-vcc") return "VCC";
    if (nodeId === "pin-gnd") return "GND";
    if (nodeId === "pin-high") return "HIGH";
    if (nodeId === "pin-low") return "LOW";
    let match = /^pin-input-(\d+)$/.exec(nodeId);
    if (match) return "Input " + match[1];
    match = /^pin-out-(\d+)$/.exec(nodeId);
    if (match) return "Output " + match[1];
    match = /^pin-(?:in-|out-)?(\d+)-(\d+)$/.exec(nodeId);
    if (match) return "IC " + match[1] + " · Pin " + match[2];
    match = /^hole-bb(\d+)-([LR])(\d+)$/.exec(nodeId);
    if (match) return "Base " + match[1] + " · " + match[2] + match[3];
    match = /^pin-seg-digit-(tens|ones)-(.+)$/.exec(nodeId);
    if (match) return match[1] + " digit · " + match[2].toUpperCase();
    const meta = typeof getNodeMeta === "function" ? getNodeMeta(nodeId) : null;
    if (meta && meta.label) return String(meta.label);
    return nodeId.replace(/^pin-/, "").replaceAll("-", " ");
  }

  function renderSimulationStatus() {
    const power = document.getElementById("simulation-power");
    if (power) {
      const on = window.isPowered !== false;
      power.textContent = on ? "POWER ON" : "POWER OFF";
      power.className = on ? "simulation-power is-on" : "simulation-power is-off";
    }

    const summary = document.getElementById("simulation-summary");
    if (summary) {
      const icCount = placedICs.size;
      const wireCount = typeof Wires !== "undefined" ? Wires.getAllWires().length : 0;
      summary.textContent = icCount + " placed IC" + (icCount === 1 ? "" : "s") +
        " · " + wireCount + " jumper" + (wireCount === 1 ? "" : "s");
    }

    const info = document.getElementById("selected-wire-info");
    if (!info) return;
    info.replaceChildren();
    if (!selectedWire) {
      info.dataset.selected = "false";
      info.textContent = "Select a wire on the board or in the list to inspect its signal.";
      return;
    }

    info.dataset.selected = "true";
    const value = Netlist.getNetValue(selectedWire.from);
    const state = value === 1 ? "HIGH (1)" : value === 0 ? "LOW (0)" : "FLOATING / CONFLICT";
    const title = document.createElement("strong");
    title.textContent = getEndpointDisplayName(selectedWire.from) + "  ↔  " +
      getEndpointDisplayName(selectedWire.to);
    const detail = document.createElement("span");
    detail.textContent = "Signal: " + state;
    info.append(title, detail);
  }

  function selectWireConnection(from, to) {
    if (!from || !to) return;
    if (selectedWire) {
      Wires.getWireElement(selectedWire.from, selectedWire.to)?.classList.remove("is-selected");
    }
    selectedWire = { from, to };
    Wires.getWireElement(from, to)?.classList.add("is-selected");
    document.querySelectorAll("#wire-list .wire-item").forEach(row => {
      row.classList.toggle("is-selected", row.dataset.from === from && row.dataset.to === to);
    });
    renderSimulationStatus();
  }

  function clearSelectedWire() {
    if (selectedWire) {
      Wires.getWireElement(selectedWire.from, selectedWire.to)?.classList.remove("is-selected");
    }
    selectedWire = null;
    document.querySelectorAll("#wire-list .wire-item.is-selected").forEach(row =>
      row.classList.remove("is-selected"));
    renderSimulationStatus();
  }

  let wireListSignature = null;

  function renderWireList() {
    const list = document.getElementById("wire-list");
    if (!list || typeof Wires === "undefined") return;
    const wires = Wires.getAllWires();
    const count = document.getElementById("wire-count");
    if (count) count.textContent = String(wires.length);

    // Switch toggles and power changes do not alter wire topology. In that
    // case, update only the tiny color swatches and preserve existing buttons.
    const signature = wires.map(wire => wire.from + "\u0000" + wire.to).join("\u0001");
    if (signature === wireListSignature && list.children.length) {
      const rows = list.querySelectorAll(".wire-item");
      rows.forEach((row, index) => {
        const wire = wires[index];
        row.classList.toggle("is-selected", !!selectedWire &&
          selectedWire.from === wire.from && selectedWire.to === wire.to);
        const group = Wires.getWireElement(wire.from, wire.to);
        const path = group && group.querySelector("path");
        const swatch = row.querySelector(".wire-swatch");
        if (swatch) swatch.style.background = path ? path.getAttribute("stroke") : "#64748b";
      });
      return;
    }

    wireListSignature = signature;
    list.replaceChildren();
    if (!wires.length) {
      const empty = document.createElement("p");
      empty.className = "wire-list-empty";
      empty.textContent = "No jumper wires yet. Connect two terminals on the board.";
      list.appendChild(empty);
      return;
    }

    wires.forEach((wire, index) => {
      const row = document.createElement("div");
      row.className = "wire-item";
      row.dataset.from = wire.from;
      row.dataset.to = wire.to;
      row.classList.toggle("is-selected", !!selectedWire &&
        selectedWire.from === wire.from && selectedWire.to === wire.to);
      row.addEventListener("click", event => {
        if (event.target.closest("button")) return;
        selectWireConnection(wire.from, wire.to);
      });
      const group = Wires.getWireElement(wire.from, wire.to);
      const path = group && group.querySelector("path");
      const swatch = document.createElement("span");
      swatch.className = "wire-swatch";
      swatch.style.background = path ? path.getAttribute("stroke") : "#64748b";
      swatch.setAttribute("aria-hidden", "true");

      const route = document.createElement("div");
      route.className = "wire-route";
      const endpoints = document.createElement("span");
      endpoints.textContent = getEndpointDisplayName(wire.from) + " → " + getEndpointDisplayName(wire.to);
      endpoints.title = wire.from + " → " + wire.to;
      const ordinal = document.createElement("small");
      ordinal.textContent = "WIRE " + String(index + 1).padStart(2, "0");
      route.append(ordinal, endpoints);

      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "wire-remove-button";
      remove.textContent = "×";
      remove.title = "Remove this jumper wire";
      remove.setAttribute("aria-label", "Remove wire " + (index + 1) + ": " + endpoints.textContent);
      remove.dataset.from = wire.from;
      remove.dataset.to = wire.to;
      remove.addEventListener("click", () => removeWireConnection(wire.from, wire.to));
      row.append(swatch, route, remove);
      list.appendChild(row);
    });
  }

  // Removes one visual jumper and its user-created graph edge(s). The alias
  // lookup matters because drawn endpoints are breadboard holes but electrical
  // edges attached to occupied IC bases may use pin-in/out node IDs.
  function removeWireConnection(from, to) {
    if (!from || !to) return false;
    const aliasesFor = nodeId => {
      const aliases = [nodeId];
      for (const [pinId, holeId] of icTiedPairs) {
        if (holeId === nodeId) aliases.push(pinId);
      }
      return [...new Set(aliases)];
    };
    let electricalRemoved = false;
    for (const a of aliasesFor(from)) {
      for (const b of aliasesFor(to)) {
        if (a !== b && Netlist.removeWire(a, b)) electricalRemoved = true;
      }
    }
    if (selectedWire && ((selectedWire.from === from && selectedWire.to === to) ||
        (selectedWire.from === to && selectedWire.to === from))) {
      selectedWire = null;
    }
    const visualRemoved = Wires.removeWire(from, to);
    if (electricalRemoved || visualRemoved) {
      resolveAndRefresh();
      return true;
    }
    return false;
  }

  function loadSimulatorSettings() {
    let stored = null;
    try { stored = JSON.parse(window.localStorage.getItem(SETTINGS_STORAGE_KEY) || "null"); }
    catch (_) { stored = null; }
    simulatorSettings = {
      sequentialTTL: typeof stored?.sequentialTTL === "boolean" ? stored.sequentialTTL : true,
      adderLow: typeof stored?.adderLow === "boolean" ? stored.adderLow : true,
    };
    Netlist.setFloatingInputSettings(simulatorSettings);
    const ttl = document.getElementById("settings-sequential-ttl");
    const adder = document.getElementById("settings-adder-low");
    if (ttl) ttl.checked = simulatorSettings.sequentialTTL;
    if (adder) adder.checked = simulatorSettings.adderLow;
  }

  function applySimulatorSettings() {
    const ttl = document.getElementById("settings-sequential-ttl");
    const adder = document.getElementById("settings-adder-low");
    simulatorSettings = {
      sequentialTTL: ttl ? ttl.checked : true,
      adderLow: adder ? adder.checked : true,
    };
    Netlist.setFloatingInputSettings(simulatorSettings);
    try { window.localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(simulatorSettings)); }
    catch (_) { /* page-session preference still applies */ }
    resolveAndRefresh();
  }

  function attachSettingsControls() {
    const gear = document.getElementById("btn-settings");
    const panel = document.getElementById("board-settings-panel");
    const close = document.getElementById("btn-close-settings");
    if (!gear || !panel) return;
    const setOpen = value => {
      panel.hidden = !value;
      gear.setAttribute("aria-expanded", String(value));
      if (value) close?.focus(); else gear.focus();
    };
    gear.addEventListener("click", () => setOpen(panel.hidden));
    close?.addEventListener("click", () => setOpen(false));
    panel.querySelectorAll("input[type=checkbox]").forEach(input =>
      input.addEventListener("change", applySimulatorSettings));
    document.addEventListener("pointerdown", event => {
      if (!panel.hidden && !panel.contains(event.target) &&
          event.target !== gear && !gear.contains(event.target)) setOpen(false);
    });
    document.addEventListener("keydown", event => {
      if (event.key === "Escape" && !panel.hidden) setOpen(false);
    });
  }

  function updateAssumptionBadges() {
    if (!svgRoot || typeof Netlist.getAssumedInputs !== "function") return;
    const counts = new Map();
    for (const item of Netlist.getAssumedInputs()) counts.set(item.icId, (counts.get(item.icId) || 0) + 1);
    svgRoot.querySelectorAll(".ic-overlay[data-icid]").forEach(overlay => {
      const count = counts.get(overlay.dataset.icid) || 0;
      const badge = overlay.querySelector(".assumed-input-badge");
      if (!badge) return;
      badge.style.display = count ? "" : "none";
      const label = badge.querySelector("text");
      if (label) label.textContent = "ASSUMED " + count;
      badge.setAttribute("aria-label", count + " floating inputs assumed");
    });
  }

  function renderSimulationNotices() {
    const host = document.getElementById("simulation-notices");
    if (!host || typeof Netlist.getAssumedInputs !== "function") return;
    host.replaceChildren();
    for (const item of Netlist.getAssumedInputs()) {
      const placement = [...placedICs.entries()].find(([, entry]) => entry.icId === item.icId);
      const notice = document.createElement("div");
      notice.className = "simulation-notice";
      notice.dataset.level = "info";
      notice.textContent = "Info · Slot " + (placement ? placement[0] : "?") +
        " · " + item.icKey + " pin " + item.pin + ": floating input read as " +
        (item.value ? "HIGH (1)" : "LOW (0)") + " (" + item.source + ")";
      host.appendChild(notice);
    }
  }

  function hideBoardProbe() {
    const probe = document.getElementById("board-probe");
    if (probe) probe.hidden = true;
  }

  function updateBoardProbe(event, point) {
    const probe = document.getElementById("board-probe");
    if (!probe || !point || typeof Netlist.getAssumedInputs !== "function") return;
    const boardNode = getNodeAtPoint(point.x, point.y, 12);
    const pinEntry = [...icTiedPairs.entries()].find(([, hole]) => hole === boardNode);
    if (!pinEntry) { probe.hidden = true; return; }
    const item = Netlist.getAssumedInputs().find(assumption => assumption.nodeId === pinEntry[0]);
    if (!item) { probe.hidden = true; return; }
    probe.textContent = "IC " + item.icKey + " · pin " + item.pin +
      " — floating, read as " + item.value + " (" + item.source + ")";
    const rect = boardContainer.getBoundingClientRect();
    probe.style.left = Math.max(4, event.clientX - rect.left + boardContainer.scrollLeft + 12) + "px";
    probe.style.top = Math.max(4, event.clientY - rect.top + boardContainer.scrollTop + 12) + "px";
    probe.hidden = false;
  }

  // ---------------------------------------------------------------------
  // Event delegation (single listeners)
  // ---------------------------------------------------------------------

  function attachListeners() {
    // PATCH 1: coordinate-based click detection. e.target is unreliable —
    // transparent overlay rects and demo-wire paths sit above the real nodes.
    svgRoot.addEventListener("click", (e) => {
      const wireGroup = e.target.closest(".wire-group[data-from][data-to]");

      // Convert screen coords to SVG coords and resolve physical terminals
      // before wire selection. A jumper may pass close to a pin/hole; node
      // clicks must keep their wiring/interaction priority in that case.
      const pt = svgRoot.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;
      const svgPt = pt.matrixTransform(svgRoot.getScreenCTM().inverse());

      // Check if click is inside an IC base zone while placing.
      const slot = getSlotFromPoint(svgPt.x, svgPt.y);
      if (slot && icState.mode === "PLACING") {
        clearSelectedWire();
        handleICBaseClick(slot);
        return;
      }

      // Resolve the original board toggle, pin or hole hit target.
      const nodeId = getNodeAtPoint(svgPt.x, svgPt.y, 12);
      if (!nodeId && wireGroup) {
        selectWireConnection(wireGroup.dataset.from, wireGroup.dataset.to);
        return;
      }
      clearSelectedWire();
      if (!nodeId) return;

      const meta = getNodeMeta(nodeId);
      const type = meta?.type || "hole";

      if (type === "switch") {
        handleSwitchClick(nodeId);
        return;
      }

      // Power button toggles global board power.
      if (nodeId === "btn-power" || meta?.action === "power") {
        togglePower();
        return;
      }

      // Other passive buttons carry no signal — ignore.
      if (type === "button") return;

      // Everything else — holes, vcc, gnd, led — enters wire mode
      handleHoleClick(nodeId, svgPt);
    });

    // Hover probe and wire preview share a single coordinate conversion.
    svgRoot.addEventListener("mousemove", (e) => {
      const pt = svgRoot.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;
      const svgPt = pt.matrixTransform(svgRoot.getScreenCTM().inverse());
      updateBoardProbe(e, svgPt);
      if (wireState.mode === "WIRING" && previewLine) {
        previewLine.setAttribute("x2", svgPt.x);
        previewLine.setAttribute("y2", svgPt.y);
      }
    });
    svgRoot.addEventListener("mouseleave", hideBoardProbe);

    svgRoot.addEventListener("contextmenu", (e) => {
      e.preventDefault();

      // Overlay groups have pointer-events:none, so hit-test by coordinates.
      const pt = svgRoot.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;
      const svgPt = pt.matrixTransform(svgRoot.getScreenCTM().inverse());
      const slot = getSlotFromPoint(svgPt.x, svgPt.y);
      if (slot && placedICs.has(slot)) {
        const entry = placedICs.get(slot);
        removeIC(entry.icId, slot);
        return;
      }

      const wire = e.target.closest(".wire-group[data-from][data-to]");
      if (!wire) return;
      removeWireConnection(wire.dataset.from, wire.dataset.to);
    });

    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape") cancelAllModes();
    });

    // Clear All Wires: removes wires only — keeps nodes, switches, ICs.
    const btnClearWires = document.getElementById("btn-clear-wires");
    if (btnClearWires) {
      btnClearWires.addEventListener("click", () => {
        cancelAllModes();
        clearSelectedWire();
        Wires.clearAll();
        Netlist.clearWires();
        registerBoardStraps();
        resolveAndRefresh();
      });
    }
  }

  // ---------------------------------------------------------------------
  // Runtime annotation — map clickable SVG elements to board nodeIds
  // ---------------------------------------------------------------------

  function annotateBoard() {
    const svg = document.querySelector('#board-container svg');
    if (!svg) return;

    // Snapshot the factory OFF-state geometry of every toggle notch BEFORE
    // any state change. updateSwitches moves notches between:
    //   ON  = cyOff - travel (notch up)
    //   OFF = cyOff          (notch down, as shipped)
    // A snapshot avoids hardcoding '24', which is wrong for clock-section
    // toggles whose tracks sit at different local coords. Travel is measured
    // from each notch's OWN track rect — the rounded pill sibling inside the
    // toggle <svg> (e.g. x=2 w=12 rx=6). The knob center must stay within
    // [trackX + r, trackX + trackW - r]; vertical travel = trackH - 2r.
    const notchGeom = new WeakMap();
    svg.querySelectorAll('.svg_toggle_switch_notch').forEach(n => {
      if (notchGeom.has(n)) return;
      const cyOff = parseFloat(n.getAttribute('cy')) || 0;
      const r = parseFloat(n.getAttribute('r')) || 8;
      const parentSvg = n.closest('svg');
      let travel = 16; // legacy default (input row: cy 24 <-> 8)
      if (parentSvg) {
        const track = [...parentSvg.children].find(c =>
          c.tagName === 'rect' && c.getAttribute('rx'));
        if (track) {
          const tx = parseFloat(track.getAttribute('x')) || 0;
          const tw = parseFloat(track.getAttribute('width')) || 0;
          const th = parseFloat(track.getAttribute('height')) || 0;
          const lo = tx + r, hi = tx + tw - r;   // horizontal bounds (unused)
          const vert = th > 0 ? th - 2 * r : 0;  // vertical travel in track
          if (vert > 0 && cyOff - vert >= 0) travel = vert;
          else if (hi > lo) travel = Math.min(hi - cyOff, Math.max(cyOff - lo, 0));
        }
      }
      notchGeom.set(n, { cyOff, travel });
    });
    window.__notchGeom = notchGeom; // consumed by updateSwitches

    const ctmInverse = svg.getScreenCTM().inverse();

    function toSVGPoint(el) {
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) return null;
      const cx = rect.left + rect.width  / 2;
      const cy = rect.top  + rect.height / 2;
      const pt = svg.createSVGPoint();
      pt.x = cx;
      pt.y = cy;
      return pt.matrixTransform(ctmInverse);
    }

    const clickables = svg.querySelectorAll('.trainer_kit_clickable_element');
    let annotated = 0;

    clickables.forEach(el => {
      // Skip wire paths (they have data-from set by Wires module)
      if (el.dataset.from) return;

      const pt = toSVGPoint(el);
      if (!pt) return;

      const nodeId = getNodeAtPoint(pt.x, pt.y, 10);
      if (nodeId) {
        el.dataset.nodeId = nodeId;
        const meta = getNodeMeta(nodeId);
        el.dataset.type = meta?.type || 'hole';
        annotated++;
      }
    });

    console.log('annotateBoard:', annotated, 'elements annotated');
  }

  // ---------------------------------------------------------------------
  // Board-only zoom controls. Scaling the root SVG leaves the sidebars,
  // toolbar and all other interface text at their normal size.
  // ---------------------------------------------------------------------

  let boardZoom = 1;
  const BOARD_ZOOM_MIN = 0.5;
  const BOARD_ZOOM_MAX = 2;
  const BOARD_ZOOM_STEP = 0.1;

  function applyBoardZoom() {
    if (!svgRoot) return;
    svgRoot.style.width = (boardZoom * 100).toFixed(0) + "%";
    svgRoot.style.height = "auto";
    const label = document.getElementById("board-zoom-level");
    if (label) label.textContent = Math.round(boardZoom * 100) + "%";
    const minus = document.getElementById("btn-zoom-out");
    const plus = document.getElementById("btn-zoom-in");
    if (minus) minus.disabled = boardZoom <= BOARD_ZOOM_MIN;
    if (plus) plus.disabled = boardZoom >= BOARD_ZOOM_MAX;
  }

  function setBoardZoom(nextZoom) {
    boardZoom = Math.min(BOARD_ZOOM_MAX, Math.max(BOARD_ZOOM_MIN,
      Math.round(nextZoom * 10) / 10));
    applyBoardZoom();
  }

  function attachZoomControls() {
    document.getElementById("btn-zoom-out")?.addEventListener("click", () =>
      setBoardZoom(boardZoom - BOARD_ZOOM_STEP));
    document.getElementById("btn-zoom-in")?.addEventListener("click", () =>
      setBoardZoom(boardZoom + BOARD_ZOOM_STEP));
    document.getElementById("btn-zoom-reset")?.addEventListener("click", () =>
      setBoardZoom(1));
    applyBoardZoom();
  }

  function attachWorkspaceControls() {
    const app = document.getElementById("app");
    const sidebarButton = document.getElementById("btn-toggle-sidebar");
    const toolkitButton = document.getElementById("btn-toggle-toolkit");

    sidebarButton?.addEventListener("click", () => {
      const collapsed = app.classList.toggle("sidebar-collapsed");
      sidebarButton.textContent = collapsed ? "›" : "‹";
      sidebarButton.title = collapsed ? "Expand component library" : "Collapse component library";
      sidebarButton.setAttribute("aria-expanded", String(!collapsed));
    });

    toolkitButton?.addEventListener("click", () => {
      const collapsed = app.classList.toggle("toolkit-collapsed");
      toolkitButton.textContent = collapsed ? "Show toolkit" : "Hide toolkit";
      toolkitButton.setAttribute("aria-expanded", String(!collapsed));
    });
  }

  // Reset stored sequential state while preserving all circuit wiring and
  // current switch positions. The physical IC reset pins remain authoritative
  // during the subsequent resolve.
  function attachSequentialResetControl() {
    const button = document.getElementById("btn-reset");
    if (!button) return;
    button.addEventListener("click", () => {
      cancelAllModes();
      clearSelectedWire();
      Netlist.resetSequentialState();
      resolveAndRefresh();
      setWireStatus("IC STATE RESET TO ZERO", "status-success");
    });
  }

  // ---------------------------------------------------------------------
  // Init
  // ---------------------------------------------------------------------

  function init() {
    svgRoot = document.querySelector("#board-container svg");
    boardContainer = document.getElementById("board-container");
    if (!svgRoot) { console.error("main.js: board SVG not found in #board-container"); return; }

    annotateBoard();

    Wires.init(svgRoot);
    registerBoardNodes();
    loadSimulatorSettings();
    attachSettingsControls();
    attachPulseButtonListeners();
    populateICList();
    attachListeners();
    attachZoomControls();
    attachWorkspaceControls();
    attachSequentialResetControl();
    attachSaveLoadControls();
    renderPlacedICList();
    window.DeldSim = Object.freeze({
      saveCircuit,
      loadCircuit,
      removeWire: removeWireConnection,
      removeIC: (slotIndex) => {
        const entry = placedICs.get(slotIndex);
        if (!entry) return false;
        removeIC(entry.icId, slotIndex);
        return true;
      },
      refresh: resolveAndRefresh,
      // External callers that request a solve also receive a fully repainted
      // board, not a new logic map with potentially stale visible indicators.
      resolveCircuit: resolveAndRefresh,
    });
    resolveAndRefresh();
  }


  function saveCircuit() {
    const switches = {};
    for (const nodeId in BOARD_NODE_META) {
      if (BOARD_NODE_META[nodeId].type === "switch") {
        switches[nodeId] = Object.prototype.hasOwnProperty.call(CLOCK_FREQUENCIES, nodeId)
          ? (clockEnabled.get(nodeId) === true ? 1 : 0)
          : (Netlist.getNodeValue(nodeId) === 1 ? 1 : 0);
      }
    }
    return {
      format: "deldsim-circuit",
      version: 1,
      masterPower: window.isPowered !== false,
      switches,
      ics: Array.from(placedICs, ([slot, item]) => ({
        slot, icKey: item.icKey, id: item.icId,
      })).sort((a, b) => a.slot - b.slot),
      wires: Wires.getAllWires().map(w => ({ from: w.from, to: w.to })),
    };
  }

  function restoreUserWire(from, to) {
    if (!hasNode(from) || !hasNode(to) || from === to) {
      throw new Error("Invalid wire endpoint: " + from + " -> " + to);
    }
    if (Wires.getWireElement(from, to)) return;
    let netFrom = from, netTo = to;
    const destPin = [...icTiedPairs.entries()].find(([, hole]) => hole === to);
    if (destPin) netTo = destPin[0];
    const srcPin = [...icTiedPairs.entries()].find(([, hole]) => hole === from);
    if (srcPin) netFrom = srcPin[0];
    Netlist.addWire(netFrom, netTo);
    const wireEl = Wires.drawWire(from, to);
    if (!wireEl) {
      Netlist.removeWire(netFrom, netTo);
      throw new Error("Could not restore route: " + from + " -> " + to);
    }
  }

  function loadCircuit(data) {
    if (!data || data.format !== "deldsim-circuit" || data.version !== 1 ||
        !Array.isArray(data.ics) || !Array.isArray(data.wires) ||
        typeof data.switches !== "object" || data.switches === null) {
      throw new Error("Unsupported or malformed DeldSim circuit JSON");
    }
    const occupied = new Set();
    for (const item of data.ics) {
      if (!Number.isInteger(item.slot) || item.slot < 1 || item.slot > 5 ||
          occupied.has(item.slot) || !IC_LIBRARY[item.icKey]) {
        throw new Error("Invalid or duplicate IC placement in saved circuit");
      }
      occupied.add(item.slot);
    }
    for (const wire of data.wires) {
      if (!wire || typeof wire.from !== "string" || typeof wire.to !== "string") {
        throw new Error("Malformed saved wire entry");
      }
    }

    cancelAllModes();
    Wires.clearAll();
    if (Wires.clearRoutingPoints) Wires.clearRoutingPoints();
    Netlist.clear();
    placedICs.clear();
    icTiedPairs.clear();
    renderPlacedICList();
    svgRoot.querySelectorAll(".ic-overlay").forEach(el => el.remove());

    registerBoardNodes();
    for (const item of data.ics) createICAtSlot(item.slot, item.icKey, item.id);
    for (const wire of data.wires) restoreUserWire(wire.from, wire.to);

    for (const nodeId in data.switches) {
      if (BOARD_NODE_META[nodeId]?.type !== "switch") continue;
      const value = data.switches[nodeId];
      if (value !== 0 && value !== 1) continue;
      if (Object.prototype.hasOwnProperty.call(CLOCK_FREQUENCIES, nodeId)) {
        setClockEnabled(nodeId, value === 1);
      } else {
        Netlist.setSwitch(nodeId, value);
      }
    }
    scheduleClockFrame();
    window.isPowered = data.masterPower !== false;
    resolveAndRefresh();
    return true;
  }

  function downloadCircuitJSON() {
    const json = JSON.stringify(saveCircuit(), null, 2);
    const url = URL.createObjectURL(new Blob([json], { type: "application/json" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "deldsim-circuit.json";
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  function attachSaveLoadControls() {
    const saveButton = document.getElementById("btn-save-circuit");
    const loadButton = document.getElementById("btn-load-circuit");
    const fileInput = document.getElementById("circuit-json-file");
    if (saveButton) saveButton.addEventListener("click", downloadCircuitJSON);
    if (loadButton && fileInput) loadButton.addEventListener("click", () => fileInput.click());
    if (fileInput) fileInput.addEventListener("change", async () => {
      const file = fileInput.files && fileInput.files[0];
      if (!file) return;
      try {
        loadCircuit(JSON.parse(await file.text()));
        setWireStatus("CIRCUIT LOADED", "status-idle");
      } catch (error) {
        console.error("Circuit load failed:", error);
        setWireStatus("LOAD ERROR", "status-wiring");
        alert("Could not load circuit: " + error.message);
      } finally {
        fileInput.value = "";
      }
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  // Expose for headless integration checks; harmless in the browser.
  if (typeof module !== "undefined" && module.exports) {
    module.exports = { init, resolveAndRefresh, cancelAllModes };
  }
})();
