// src/board-extra.js — additional board terminals outside the generated SVG.
(function () {
  "use strict";

  // The dedicated pulse endpoint is on the 4-unit grid in the bottom strip,
  // just left of the waveform button and clear of the clock DIP housing.
  BOARD_NODES["pin-pulse"] = { x: 1344, y: 656 };
  BOARD_NODE_META["pin-pulse"] = {
    type: "hole", section: "pulse", label: "PULSE", role: "signal",
  };

  // Headless consumers need the node definition but have no SVG document.
  if (typeof document === "undefined") return;
  const svg = document.querySelector("#board-container > svg");
  if (!svg || svg.querySelector('[data-extra-node="pin-pulse"]')) return;

  const ns = "http://www.w3.org/2000/svg";
  const create = (tag, attrs = {}) => {
    const el = document.createElementNS(ns, tag);
    for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, String(value));
    return el;
  };
  const group = create("g", {
    "data-extra-node": "pin-pulse",
    "aria-label": "PULSE output terminal",
  });
  group.appendChild(create("circle", {
    cx: 1344, cy: 656, r: 7, fill: "#17313b",
    stroke: "#d6f4fb", "stroke-width": 1.5,
  }));
  group.appendChild(create("circle", {
    cx: 1344, cy: 656, r: 3, fill: "#ecf7fa",
    stroke: "#63818c", "stroke-width": 0.6,
  }));
  group.appendChild(create("rect", {
    x: 1332, y: 644, width: 24, height: 24,
    fill: "#ffffff", opacity: 0, "pointer-events": "all",
    class: "trainer_kit_clickable_element",
  }));
  const label = create("text", {
    x: 1344, y: 680, fill: "#f0f8fa", "font-size": 9,
    "font-weight": "bold", "text-anchor": "middle", "letter-spacing": 0.8,
  });
  label.textContent = "PULSE";
  group.appendChild(label);
  svg.appendChild(group);
})();
