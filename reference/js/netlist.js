// src/netlist.js -- PHASE 2 graph engine for DeldSim Clone.
// Pure logic: no DOM, no SVG. Depends on IC_LIBRARY/resolveGate (ics.js) and
// optionally getBreadboardNet (board.js) for net collapsing.

const Netlist = (function () {
  // nodeId -> { type, meta, value: 0|1|null }
  const nodes = new Map();
  // nodeId -> Set of connected nodeIds (bidirectional adjacency)
  const edges = new Map();
  // Edges created by the simulator itself (panel wiring and IC pin-to-hole
  // ties). clearWires() must never remove these.
  const permanentEdges = new Set();
  // switch nodeId -> current user value 0|1
  const switches = new Map();
  // Simulator-controlled wire_term sources, e.g. the momentary pulse output.
  const externalSources = new Map();
  // icId -> { icKey, pinNodes: { pinNumber: nodeId } }
  const icInstances = new Map();
  // Per-instance flip-flop storage and previous clock levels. State is kept
  // between resolveCircuit() calls so a clock edge is observable exactly once.
  const sequentialStates = new Map();
  const counterStates = new Map();
  const assumedInputs = new Map();
  let floatingSettings = { sequentialTTL: true, adderLow: true };

  function setFloatingInputSettings(settings) {
    if (!settings || typeof settings.sequentialTTL !== "boolean" ||
        typeof settings.adderLow !== "boolean") {
      throw new Error("floating input settings require boolean sequentialTTL and adderLow values");
    }
    floatingSettings = { sequentialTTL: settings.sequentialTTL, adderLow: settings.adderLow };
  }

  function getAssumedInputs() {
    return Array.from(assumedInputs.values(), item => ({ ...item }));
  }

  function edgeKey(nodeIdA, nodeIdB) {
    return JSON.stringify(nodeIdA < nodeIdB
      ? [nodeIdA, nodeIdB]
      : [nodeIdB, nodeIdA]);
  }

  function detachEdge(nodeIdA, nodeIdB) {
    const a = edges.get(nodeIdA);
    if (a) a.delete(nodeIdB);
    const b = edges.get(nodeIdB);
    if (b) b.delete(nodeIdA);
  }

  function ensureEdge(nodeId) {
    if (!edges.has(nodeId)) edges.set(nodeId, new Set());
    return edges.get(nodeId);
  }

  function addNode(nodeId, type, meta) {
    nodes.set(nodeId, { type: type, meta: meta || {}, value: null });
    ensureEdge(nodeId);
  }

  function removeNode(nodeId) {
    const adj = edges.get(nodeId);
    if (adj) {
      for (const other of adj) {
        const back = edges.get(other);
        if (back) back.delete(nodeId);
        permanentEdges.delete(edgeKey(nodeId, other));
      }
    }
    edges.delete(nodeId);
    nodes.delete(nodeId);
    switches.delete(nodeId);
    externalSources.delete(nodeId);
  }

  // User-created jumper wire. Kept for compatibility with existing callers.
  function addWire(nodeIdA, nodeIdB) {
    if (nodeIdA === nodeIdB) return;
    ensureEdge(nodeIdA).add(nodeIdB);
    ensureEdge(nodeIdB).add(nodeIdA);
  }

  // Simulator-owned connection: front-panel traces or an IC pin's physical
  // connection to the breadboard hole beneath it.
  function addPermanentWire(nodeIdA, nodeIdB) {
    if (nodeIdA === nodeIdB) return;
    ensureEdge(nodeIdA).add(nodeIdB);
    ensureEdge(nodeIdB).add(nodeIdA);
    permanentEdges.add(edgeKey(nodeIdA, nodeIdB));
  }

  // Remove only a user-created edge. A permanent edge can only be removed
  // explicitly with removePermanentWire() or implicitly with removeNode().
  function removeWire(nodeIdA, nodeIdB) {
    if (permanentEdges.has(edgeKey(nodeIdA, nodeIdB))) return false;
    detachEdge(nodeIdA, nodeIdB);
    return true;
  }

  function removePermanentWire(nodeIdA, nodeIdB) {
    const key = edgeKey(nodeIdA, nodeIdB);
    if (!permanentEdges.has(key)) return false;
    permanentEdges.delete(key);
    detachEdge(nodeIdA, nodeIdB);
    return true;
  }

  function isPermanentWire(nodeIdA, nodeIdB) {
    return permanentEdges.has(edgeKey(nodeIdA, nodeIdB));
  }

  function setSwitch(nodeId, value) {
    if (value !== 0 && value !== 1) {
      throw new Error("switch value must be 0 or 1");
    }
    switches.set(nodeId, value);
    const n = nodes.get(nodeId);
    if (n && n.type === "switch") n.value = value;
  }

  // Drive a passive wire terminal from a simulator-controlled source.
  // Its level is restored on every resolve, just like a switch source.
  function setSource(nodeId, value) {
    if (value !== 0 && value !== 1) throw new Error("source value must be 0 or 1");
    const node = nodes.get(nodeId);
    if (!node) throw new Error("unknown source node: " + nodeId);
    if (node.type !== "wire_term") {
      throw new Error("external sources require a wire_term node: " + nodeId);
    }
    externalSources.set(nodeId, value);
    node.value = value;
  }

  function placeIC(icId, icKey, pinNodeMap) {
    const def = IC_LIBRARY[icKey];
    if (!def) throw new Error("unknown IC key: " + icKey);
    const pinNodes = {};
    for (const p in pinNodeMap) pinNodes[Number(p)] = pinNodeMap[p];
    icInstances.set(icId, { icKey: icKey, pinNodes: pinNodes });
    sequentialStates.set(icId, (def.flipFlops || []).map(() => ({
      q: null, qbar: null, previousClock: null,
    })));
    if (def.counter) counterStates.set(icId, {
      // TTL counters power up indeterminate; a valid reset establishes zero.
      qa: null, upper: null, previousClockA: null, previousClockB: null,
    });
  }

  function removeIC(icId) {
    icInstances.delete(icId);
    sequentialStates.delete(icId);
    counterStates.delete(icId);
    for (const [nodeId, item] of assumedInputs) {
      if (item.icId === icId) assumedInputs.delete(nodeId);
    }
  }

  function getOutputPins(def) {
    const pins = new Set(def.outputPins || []);
    for (const gate of (def.gates || [])) pins.add(gate.output);
    if (def.adder) {
      (def.adder.sum || []).forEach(p => pins.add(p));
      if (def.adder.carryOut !== undefined) pins.add(def.adder.carryOut);
    }
    if (def.decoder3to8) (def.decoder3to8.outputs || []).forEach(p => pins.add(p));
    if (def.counter) (def.counter.outputs || []).forEach(p => pins.add(p));
    for (const ff of (def.flipFlops || [])) {
      pins.add(ff.q);
      pins.add(ff.qbar);
    }
    return Array.from(pins).filter(Number.isInteger);
  }

  // Only functional inputs can receive a floating-level substitution. Build
  // the set from supported IC behavior descriptors and exclude power pins.
  function getInputPins(def) {
    const pins = new Set(def.inputPins || []);
    for (const gate of (def.gates || [])) (gate.inputs || []).forEach(pin => pins.add(pin));
    if (def.adder) {
      [...(def.adder.a || []), ...(def.adder.b || []), def.adder.carryIn]
        .forEach(pin => pins.add(pin));
    }
    if (def.decoder3to8) {
      const d = def.decoder3to8;
      [...(d.address || []), ...(d.enableLow || []), d.enableHigh]
        .forEach(pin => pins.add(pin));
    }
    for (const ff of (def.flipFlops || [])) {
      [ff.clock, ff.data, ff.j, ff.k, ff.preset, ff.clear].forEach(pin => pins.add(pin));
    }
    if (def.counter) {
      const c = def.counter;
      [c.clockA, c.clockB, ...(c.resetZero || []), ...(c.presetNine || [])]
        .forEach(pin => pins.add(pin));
    }
    pins.delete(def.vcc);
    pins.delete(def.gnd);
    return new Set(Array.from(pins).filter(Number.isInteger));
  }

  function getConnected(nodeId) {
    const adj = edges.get(nodeId);
    return adj ? Array.from(adj) : [];
  }

  function getNodeValue(nodeId) {
    const n = nodes.get(nodeId);
    return n ? n.value : null;
  }

  // BFS from nodeId through all wire edges -> every node in the same net.
  function collectNet(nodeId) {
    const seen = new Set([nodeId]);
    const queue = [nodeId];
    while (queue.length) {
      const cur = queue.shift();
      const adj = edges.get(cur);
      if (!adj) continue;
      for (const nb of adj) {
        if (!seen.has(nb)) {
          seen.add(nb);
          queue.push(nb);
        }
      }
    }
    return seen;
  }

  // Resolve value across an entire connected net.
  // Conflicting concrete values (short) -> null. All floating -> null.
  function getNetValue(nodeId) {
    if (!nodes.has(nodeId)) return null;
    let found = null;
    for (const id of collectNet(nodeId)) {
      const v = nodes.get(id).value;
      if (v === 0 || v === 1) {
        if (found === null) found = v;
        else if (found !== v) return null; // conflict
      }
    }
    return found;
  }

  // Assign `value` to every still-null node reachable from nodeId.
  // Returns true if any node was newly assigned.
  function propagateNet(nodeId, value) {
    let changed = false;
    for (const id of collectNet(nodeId)) {
      const n = nodes.get(id);
      if (n.value === null) {
        n.value = value;
        changed = true;
      }
    }
    return changed;
  }

  function getICPinNodes(icId) {
    const inst = icInstances.get(icId);
    return inst ? inst.pinNodes : null;
  }

  // Reset all derived values, restore fixed sources, propagate them, and mark
  // VCC/GND shorted nets unknown. Used before each combinational solve pass.
  function resetSignalNets() {
    for (const [id, node] of nodes) {
      if (node.type === "vcc") node.value = 1;
      else if (node.type === "gnd") node.value = 0;
      else if (node.type === "switch") node.value = switches.has(id) ? switches.get(id) : 0;
      else if (externalSources.has(id)) node.value = externalSources.get(id);
      else node.value = null;
    }

    for (const [id, node] of nodes) {
      if (node.type === "vcc" || node.type === "gnd" || node.type === "switch" ||
          externalSources.has(id)) {
        propagateNet(id, node.value);
      }
    }

    const visited = new Set();
    for (const [id, node] of nodes) {
      if (visited.has(id) || !edges.get(id) || edges.get(id).size === 0) continue;
      const net = collectNet(id);
      for (const member of net) visited.add(member);
      let hasVcc = false, hasGnd = false;
      for (const member of net) {
        const type = nodes.get(member).type;
        if (type === "vcc") hasVcc = true;
        if (type === "gnd") hasGnd = true;
      }
      if (hasVcc && hasGnd) {
        console.warn("netlist: VCC/GND conflict detected in net containing " + id);
        for (const member of net) nodes.get(member).value = null;
      }
    }
  }

  function resolveCircuit() {
    // Seed sources and clear all derived values before resolving this frame.
    resetSignalNets();

    // Step 4 -- power check per IC instance
    // Master power switch: if the board's POWER button is off, every IC
    // loses power regardless of its VCC/GND nets.
    const boardPowered = (typeof window === "undefined") || window.isPowered !== false;
    const powered = new Map();
    for (const [icId, inst] of icInstances) {
      const def = IC_LIBRARY[inst.icKey];
      const vccNet = inst.pinNodes[def.vcc] !== undefined
        ? collectNet(inst.pinNodes[def.vcc]) : new Set();
      const gndNet = inst.pinNodes[def.gnd] !== undefined
        ? collectNet(inst.pinNodes[def.gnd]) : new Set();
      const hasV = boardPowered && Array.from(vccNet).some((x) => {
        const n = nodes.get(x);
        return n && n.type === "vcc";
      });
      const hasG = boardPowered && Array.from(gndNet).some((x) => {
        const n = nodes.get(x);
        return n && n.type === "gnd";
      });
      powered.set(icId, hasV && hasG);
      if (!(hasV && hasG)) {
        // unpowered -> every output pin stays null
        for (const g of (def.gates || [])) {
          const outNode = inst.pinNodes[g.output];
          if (outNode !== undefined && nodes.has(outNode)) nodes.get(outNode).value = null;
        }
      }
    }

    // Step 5 -- resolve combinational devices (including custom TTL blocks).
    const drivenOutputIds = new Set();
    for (const [, inst] of icInstances) {
      const def = IC_LIBRARY[inst.icKey];
      for (const pin of getOutputPins(def)) {
        if (inst.pinNodes[pin] !== undefined) drivenOutputIds.add(inst.pinNodes[pin]);
      }
    }

    // Drive one IC output onto its complete connected net. External sources
    // still win only when they agree; opposing driven sources create a short.
    const contendedNets = new Set();
    function netKey(net) {
      return Array.from(net).sort().join("|");
    }
    function driveOutput(inst, pin, value) {
      if (value !== 0 && value !== 1) return;
      const outNode = inst.pinNodes[pin];
      if (outNode === undefined || !nodes.has(outNode)) return;
      const net = collectNet(outNode);
      const key = netKey(net);
      if (contendedNets.has(key)) return;

      let conflict = false;
      for (const id of net) {
        if (id === outNode) continue;
        const node = nodes.get(id);
        if (!node) continue;
        const isFixedSource = node.type === "vcc" || node.type === "gnd" || node.type === "switch";
        if (isFixedSource && (node.value === 0 || node.value === 1) && node.value !== value) {
          conflict = true;
          break;
        }
        if (drivenOutputIds.has(id) && (node.value === 0 || node.value === 1) && node.value !== value) {
          conflict = true;
          break;
        }
      }
      if (conflict) {
        contendedNets.add(key);
        for (const id of net) {
          const node = nodes.get(id);
          if (node && node.type !== "vcc" && node.type !== "gnd" && node.type !== "switch") node.value = null;
        }
        return;
      }
      for (const id of net) {
        const node = nodes.get(id);
        if (node && node.type !== "vcc" && node.type !== "gnd" && node.type !== "switch") node.value = value;
      }
    }

    function readPin(inst, pin) {
      const id = inst.pinNodes[pin];
      if (id === undefined) return null;
      const def = IC_LIBRARY[inst.icKey];
      if (pin === def.vcc || pin === def.gnd) return getNetValue(id);

      const value = getNetValue(id);
      if (value !== null) return value;

      // A source or IC output is a driver even when it currently resolves unknown.
      const hasDriver = Array.from(collectNet(id)).some(member => {
        const node = nodes.get(member);
        return !!node && (
          node.type === "switch" || node.type === "vcc" || node.type === "gnd" ||
          externalSources.has(member) || drivenOutputIds.has(member)
        );
      });
      if (hasDriver) return null;

      // Never substitute a level for a power, output, or unused package pin.
      if (!getInputPins(def).has(pin)) return null;

      const mode = def.floatingInputs || "none";
      const override = (def.floatingInputOverrides || {})[pin];
      let assumed = null, source = null;
      if (mode === "ttl" && floatingSettings.sequentialTTL) {
        assumed = override === "low" ? 0 : 1;
        source = "TTL";
      } else if (mode === "low" && floatingSettings.adderLow) {
        assumed = 0;
        source = "LOW";
      }
      if (assumed === null) return null;

      const icId = [...icInstances.entries()].find(([, instance]) => instance === inst)?.[0] || null;
      assumedInputs.set(id, { icId, icKey: inst.icKey, pin, nodeId: id,
        value: assumed, mode, source, override: override || null });
      return assumed;
    }

    // Flip-flop outputs are state, not transient combinational values. Restore
    // the last committed Q/Q-bar values before solving combinational inputs.
    function driveStoredSequentialOutputs() {
      for (const [icId, inst] of icInstances) {
        if (powered.get(icId) !== true) continue;
        const def = IC_LIBRARY[inst.icKey];
        const states = sequentialStates.get(icId) || [];
        (def.flipFlops || []).forEach((ff, index) => {
          const state = states[index];
          if (!state) return;
          driveOutput(inst, ff.q, state.q);
          const qbar = state.qbar === 0 || state.qbar === 1
            ? state.qbar
            : (state.q === null ? null : 1 - state.q);
          driveOutput(inst, ff.qbar, qbar);
        });
        if (def.counter) {
          const state = counterStates.get(icId);
          if (state) {
            const q = state.upper === null
              ? [state.qa, null, null, null]
              : [state.qa, state.upper & 1, (state.upper >> 1) & 1, (state.upper >> 2) & 1];
            def.counter.outputs.forEach((pin, index) => driveOutput(inst, pin, q[index]));
          }
        }
      }
    }

    function evaluateCombinational() {
      for (let iter = 0; iter < 20; iter++) {
        const before = Array.from(nodes, ([id, node]) =>
          id + ":" + (node.value === null ? "n" : node.value)).join(";");
        for (const [icId, inst] of icInstances) {
          if (!powered.get(icId)) continue;
          const def = IC_LIBRARY[inst.icKey];

          // Standard gates: 7400/02/04/08/32/86.
          for (const gate of (def.gates || [])) {
            const inputs = gate.inputs.map(pin => readPin(inst, pin));
            if (inputs.some(v => v === null)) continue;
            driveOutput(inst, gate.output, resolveGate(gate.fn, inputs));
          }

          // 7483: four-bit A + B + carry-in.
          if (def.adder) {
            const aBits = def.adder.a.map(pin => readPin(inst, pin));
            const bBits = def.adder.b.map(pin => readPin(inst, pin));
            const cin = readPin(inst, def.adder.carryIn);
            if (![...aBits, ...bBits, cin].some(v => v === null)) {
              const a = aBits.reduce((n, bit, i) => n | (bit << i), 0);
              const b = bBits.reduce((n, bit, i) => n | (bit << i), 0);
              const total = a + b + cin;
              def.adder.sum.forEach((pin, i) => driveOutput(inst, pin, (total >> i) & 1));
              driveOutput(inst, def.adder.carryOut, (total >> 4) & 1);
            }
          }

          // 74138: active-low Y0..Y7, selected by CBA when all enables agree.
          if (def.decoder3to8) {
            const d = def.decoder3to8;
            const addr = d.address.map(pin => readPin(inst, pin));
            const enL = d.enableLow.map(pin => readPin(inst, pin));
            const enH = readPin(inst, d.enableHigh);
            if (![...addr, ...enL, enH].some(v => v === null)) {
              const enabled = enL.every(v => v === 0) && enH === 1;
              const selected = addr.reduce((n, bit, i) => n | (bit << i), 0);
              d.outputs.forEach((pin, index) =>
                driveOutput(inst, pin, enabled ? (index === selected ? 0 : 1) : 1));
            }
          }


        }

        // Output values, not only booleans, are the progress metric. A
        // second identical pass exits immediately after stable propagation.
        const after = Array.from(nodes, ([id, node]) =>
          id + ":" + (node.value === null ? "n" : node.value)).join(";");
        if (after === before) break;
      }
    }

    // Resolve combinational logic, sample every sequential device from the
    // same settled snapshot, then commit all state together. Repeat because a
    // committed Q output may create a clock edge for a downstream ripple stage.
    const MAX_SEQUENTIAL_PASSES = 50;
    let sequentialSettled = false;
    let sequentialPasses = 0;

    for (sequentialPasses = 0; sequentialPasses < MAX_SEQUENTIAL_PASSES; sequentialPasses++) {
      assumedInputs.clear();
      resetSignalNets();
      contendedNets.clear();
      driveStoredSequentialOutputs();
      evaluateCombinational();

      const sequentialUpdates = [];
      for (const [icId, inst] of icInstances) {
        const def = IC_LIBRARY[inst.icKey];
        const states = sequentialStates.get(icId) || [];
        const isPowered = powered.get(icId) === true;

        (def.flipFlops || []).forEach((ff, index) => {
          const state = states[index] || { q: null, qbar: null, previousClock: null };
          states[index] = state;

          if (!isPowered) {
            sequentialUpdates.push({ state, clock: null, q: null, qbar: null });
            return;
          }

          // Read every input before committing any Q output. This is essential
          // for simultaneous sampling and feedback configurations such as Q-bar -> D.
          const clock = readPin(inst, ff.clock);
          const hasPreset = Number.isInteger(ff.preset);
          const hasClear = Number.isInteger(ff.clear);
          const preset = hasPreset ? readPin(inst, ff.preset) : 1;
          const clear = hasClear ? readPin(inst, ff.clear) : 1;
          const data = ff.type === "D" ? readPin(inst, ff.data) : null;
          const j = ff.type === "JK" ? readPin(inst, ff.j) : null;
          const k = ff.type === "JK" ? readPin(inst, ff.k) : null;

          const edgeDetected = ff.edge === "falling"
            ? state.previousClock === 1 && clock === 0
            : state.previousClock === 0 && clock === 1;

          let nextQ = state.q;
          let nextQbar = state.qbar;
          let clockAllowed = true;
          let bothAsyncControlsActive = false;

          if (ff.asyncActiveLow) {
            // Only controls that physically exist on this IC are considered.
            // Unknown asynchronous controls make the stored state indeterminate.
            if ((hasPreset && preset === null) || (hasClear && clear === null)) {
              nextQ = null;
              nextQbar = null;
              clockAllowed = false;
            } else {
              const presetActive = hasPreset && preset === 0;
              const clearActive = hasClear && clear === 0;

              if (presetActive && clearActive) {
                // The 7474 truth table specifies Q=Q-bar=HIGH for this state.
                nextQ = 1;
                nextQbar = 1;
                bothAsyncControlsActive = true;
                clockAllowed = false;
              } else if (presetActive) {
                nextQ = 1;
                nextQbar = 0;
                clockAllowed = false;
              } else if (clearActive) {
                nextQ = 0;
                nextQbar = 1;
                clockAllowed = false;
              }
            }
          }

          if (clockAllowed && edgeDetected) {
            if (ff.type === "D") {
              nextQ = data;
            } else if (j === null || k === null) {
              nextQ = null;
            } else if (j === 0 && k === 0) {
              // Hold the previously committed state.
            } else if (j === 0 && k === 1) {
              nextQ = 0;
            } else if (j === 1 && k === 0) {
              nextQ = 1;
            } else {
              nextQ = nextQ === null ? null : 1 - nextQ;
            }
          }

          if (clockAllowed && !bothAsyncControlsActive) {
            nextQbar = nextQ === null ? null : 1 - nextQ;
          }
          sequentialUpdates.push({ state, clock, q: nextQ, qbar: nextQbar });
        });
        sequentialStates.set(icId, states);
      }

      // Counters are modeled behaviorally as a divide-by-two section and a
      // cascaded upper section. A changed QA is propagated on the next pass,
      // where it can create a falling edge at CKB.
      const counterUpdates = [];
      for (const [icId, inst] of icInstances) {
        const def = IC_LIBRARY[inst.icKey];
        if (!def.counter) continue;
        const state = counterStates.get(icId);
        if (!state) continue;

        if (powered.get(icId) !== true) {
          counterUpdates.push({
            state, qa: null, upper: null, clockA: null, clockB: null,
          });
          continue;
        }

        const c = def.counter;
        const clockA = readPin(inst, c.clockA);
        const clockB = readPin(inst, c.clockB);
        const resetPins = c.resetZero || [];
        const presetPins = c.presetNine || [];
        const resetValues = resetPins.map(pin => readPin(inst, pin));
        const presetValues = presetPins.map(pin => readPin(inst, pin));

        let qa = state.qa;
        let upper = state.upper;
        const anyUnknownControl = resetValues.includes(null) || presetValues.includes(null);

        if (anyUnknownControl) {
          qa = null;
          upper = null;
        } else if (resetValues.length && resetValues.every(value => value === 1)) {
          qa = 0;
          upper = 0;
        } else if (presetValues.length && presetValues.every(value => value === 1)) {
          qa = 1;
          upper = 4;
        } else {
          const edge = c.edge || "falling";
          const edgeA = edge === "falling"
            ? state.previousClockA === 1 && clockA === 0
            : state.previousClockA === 0 && clockA === 1;
          const edgeB = edge === "falling"
            ? state.previousClockB === 1 && clockB === 0
            : state.previousClockB === 0 && clockB === 1;

          if (edgeA && qa !== null) qa = 1 - qa;
          const modulus = Number.isInteger(c.upperModulus) ? c.upperModulus : 5;
          if (edgeB && upper !== null) upper = (upper + 1) % modulus;
        }

        counterUpdates.push({ state, qa, upper, clockA, clockB });
      }

      // Commit only after every flip-flop and counter has sampled the same
      // pre-update network state. A changed output causes another propagation pass.
      let stateChanged = false;
      for (const update of sequentialUpdates) {
        if (update.state.q !== update.q || update.state.qbar !== update.qbar) {
          stateChanged = true;
        }
        update.state.q = update.q;
        update.state.qbar = update.qbar;
        update.state.previousClock = update.clock;
      }

      for (const update of counterUpdates) {
        if (update.state.qa !== update.qa || update.state.upper !== update.upper) {
          stateChanged = true;
        }
        update.state.qa = update.qa;
        update.state.upper = update.upper;
        update.state.previousClockA = update.clockA;
        update.state.previousClockB = update.clockB;
      }

      if (!stateChanged) {
        sequentialSettled = true;
        sequentialPasses++;
        break;
      }
    }

    if (!sequentialSettled) {
      console.warn(
        "netlist: sequential propagation reached " + MAX_SEQUENTIAL_PASSES +
        " passes; the circuit may contain an oscillator or unstable feedback"
      );
      // Show the outputs of the final committed state even when a circuit
      // fails to settle within the safety bound.
      assumedInputs.clear();
      resetSignalNets();
      contendedNets.clear();
      driveStoredSequentialOutputs();
      evaluateCombinational();
    }

    // Step 8 -- LED update
    for (const [id, n] of nodes) {
      if (n.type === "led") n.value = getNetValue(id);
    }

    // Step 9 -- return full state map
    const state = new Map();
    for (const [id, n] of nodes) state.set(id, n.value);
    return state;
  }

  // Initialize all sequential storage without changing the circuit graph or
  // switch positions. Previous clocks are cleared so the next resolve records
  // current clock levels without manufacturing an edge.
  function resetSequentialState() {
    for (const states of sequentialStates.values()) {
      for (const state of states) {
        state.q = 0;
        state.qbar = 1;
        state.previousClock = null;
      }
    }
    for (const state of counterStates.values()) {
      state.qa = 0;
      state.upper = 0;
      state.previousClockA = null;
      state.previousClockB = null;
    }
    assumedInputs.clear();
  }

  function clear() {
    nodes.clear();
    edges.clear();
    permanentEdges.clear();
    switches.clear();
    externalSources.clear();
    icInstances.clear();
    sequentialStates.clear();
    counterStates.clear();
    assumedInputs.clear();
  }

  // Remove only user-created jumper wires. Built-in panel traces and the
  // physical IC-pin-to-hole ties survive; nodes, switch states and ICs remain.
  function clearWires() {
    for (const [nodeId, adjacent] of edges) {
      for (const other of Array.from(adjacent)) {
        if (!permanentEdges.has(edgeKey(nodeId, other))) {
          detachEdge(nodeId, other);
        }
      }
    }
  }

  return {
    addNode: addNode,
    removeNode: removeNode,
    addWire: addWire,
    addPermanentWire: addPermanentWire,
    removeWire: removeWire,
    removePermanentWire: removePermanentWire,
    isPermanentWire: isPermanentWire,
    setSwitch: setSwitch,
    setSource: setSource,
    setFloatingInputSettings: setFloatingInputSettings,
    getAssumedInputs: getAssumedInputs,
    placeIC: placeIC,
    removeIC: removeIC,
    resolveCircuit: resolveCircuit,
    resetSequentialState: resetSequentialState,
    getNodeValue: getNodeValue,
    getConnected: getConnected,
    getNetValue: getNetValue,
    getICPinNodes: getICPinNodes,
    getPlacedICs: () => Array.from(icInstances, ([id, inst]) => ({
      id, icKey: inst.icKey, pinNodes: { ...inst.pinNodes },
    })),
    clear: clear,
    clearWires: clearWires,
  };
})();

// Export for headless tooling/tests; harmless in the browser.
if (typeof module !== "undefined" && module.exports) {
  module.exports = { Netlist };
}
