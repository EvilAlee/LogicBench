// DeldSim TTL IC library.
// Pin numbers and power pins follow the named DIP datasheets. Sequential
// devices are evaluated by Netlist on clock edges; combinational devices use
// the gate/adder/decoder descriptors below.

const IC_LIBRARY = {
  "7408": {
    name: "Quad 2-Input AND",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2], output: 3, fn: "AND" },
      { inputs: [4, 5], output: 6, fn: "AND" },
      { inputs: [10, 9], output: 8, fn: "AND" },
      { inputs: [13, 12], output: 11, fn: "AND" },
    ],
  },
  "7432": {
    name: "Quad 2-Input OR",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2], output: 3, fn: "OR" },
      { inputs: [4, 5], output: 6, fn: "OR" },
      { inputs: [10, 9], output: 8, fn: "OR" },
      { inputs: [13, 12], output: 11, fn: "OR" },
    ],
  },
  "7404": {
    name: "Hex Inverter",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1], output: 2, fn: "NOT" },
      { inputs: [3], output: 4, fn: "NOT" },
      { inputs: [5], output: 6, fn: "NOT" },
      { inputs: [9], output: 8, fn: "NOT" },
      { inputs: [11], output: 10, fn: "NOT" },
      { inputs: [13], output: 12, fn: "NOT" },
    ],
  },
  "7400": {
    name: "Quad 2-Input NAND",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2], output: 3, fn: "NAND" },
      { inputs: [4, 5], output: 6, fn: "NAND" },
      { inputs: [10, 9], output: 8, fn: "NAND" },
      { inputs: [13, 12], output: 11, fn: "NAND" },
    ],
  },
  // 7402 has the unusual NOR pinout: outputs 1, 4, 10 and 13.
  "7402": {
    name: "Quad 2-Input NOR",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [2, 3], output: 1, fn: "NOR" },
      { inputs: [5, 6], output: 4, fn: "NOR" },
      { inputs: [8, 9], output: 10, fn: "NOR" },
      { inputs: [11, 12], output: 13, fn: "NOR" },
    ],
  },
  "7486": {
    name: "Quad 2-Input XOR",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2], output: 3, fn: "XOR" },
      { inputs: [4, 5], output: 6, fn: "XOR" },
      { inputs: [10, 9], output: 8, fn: "XOR" },
      { inputs: [13, 12], output: 11, fn: "XOR" },
    ],
  },

  "7410": {
    name: "Triple 3-Input NAND",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2, 13], output: 12, fn: "NAND" },
      { inputs: [3, 4, 5], output: 6, fn: "NAND" },
      { inputs: [9, 10, 11], output: 8, fn: "NAND" },
    ],
  },
  "7411": {
    name: "Triple 3-Input AND",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2, 13], output: 12, fn: "AND" },
      { inputs: [3, 4, 5], output: 6, fn: "AND" },
      { inputs: [9, 10, 11], output: 8, fn: "AND" },
    ],
  },
  "7427": {
    name: "Triple 3-Input NOR",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2, 13], output: 12, fn: "NOR" },
      { inputs: [3, 4, 5], output: 6, fn: "NOR" },
      { inputs: [9, 10, 11], output: 8, fn: "NOR" },
    ],
  },
  "7420": {
    name: "Dual 4-Input NAND",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2, 4, 5], output: 6, fn: "NAND" },
      { inputs: [9, 10, 12, 13], output: 8, fn: "NAND" },
    ],
  },
  "7421": {
    name: "Dual 4-Input AND",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2, 4, 5], output: 6, fn: "AND" },
      { inputs: [9, 10, 12, 13], output: 8, fn: "AND" },
    ],
  },
  "7430": {
    name: "8-Input NAND",
    floatingInputs: "none",
    pins: 14, vcc: 14, gnd: 7,
    gates: [
      { inputs: [1, 2, 3, 4, 5, 6, 11, 12], output: 8, fn: "NAND" },
    ],
  },

  "7474": {
    name: "Dual D-Type Positive-Edge Flip-Flop",
    floatingInputs: "ttl",
    floatingInputOverrides: { 1: "high", 4: "high", 10: "high", 13: "high" },
    pins: 14, vcc: 14, gnd: 7,
    inputPins: [1, 2, 3, 4, 10, 11, 12, 13],
    outputPins: [5, 6, 8, 9],
    flipFlops: [
      { type: "D", edge: "rising", clock: 3, data: 2, preset: 4, clear: 1, q: 5, qbar: 6, asyncActiveLow: true },
      { type: "D", edge: "rising", clock: 11, data: 12, preset: 10, clear: 13, q: 9, qbar: 8, asyncActiveLow: true },
    ],
  },
  "7473": {
    name: "Dual JK Master-Slave Flip-Flop With Clear",
    floatingInputs: "ttl",
    pins: 14, vcc: 4, gnd: 11,
    inputPins: [1, 2, 3, 5, 6, 7, 10, 14],
    outputPins: [8, 9, 12, 13],
    // The classic 7473 is positive-pulse/master-slave: J/K are loaded while
    // CLK is HIGH and the observable slave outputs transfer on HIGH-to-LOW.
    flipFlops: [
      { type: "JK", edge: "falling", clock: 1, clear: 2, k: 3, j: 14, q: 12, qbar: 13, asyncActiveLow: true },
      { type: "JK", edge: "falling", clock: 5, clear: 6, j: 7, k: 10, q: 9, qbar: 8, asyncActiveLow: true },
    ],
  },
  "7476": {
    name: "Dual JK Master-Slave Flip-Flop",
    floatingInputs: "ttl",
    floatingInputOverrides: { 2: "high", 3: "high", 7: "high", 8: "high" },
    pins: 16, vcc: 5, gnd: 13,
    inputPins: [1, 2, 3, 4, 6, 7, 8, 9, 12, 16],
    outputPins: [10, 11, 14, 15],
    flipFlops: [
      // Positive-pulse master/slave model: capture the high phase and expose
      // the committed slave state on the falling transition.
      { type: "JK", edge: "falling", clock: 1, preset: 2, clear: 3, j: 4, k: 16, q: 15, qbar: 14, asyncActiveLow: true },
      { type: "JK", edge: "falling", clock: 6, preset: 7, clear: 8, j: 9, k: 12, q: 11, qbar: 10, asyncActiveLow: true },
    ],
  },
  "7483": {
    name: "4-Bit Binary Full Adder",
    floatingInputs: "low",
    pins: 16, vcc: 5, gnd: 12,
    inputPins: [1, 3, 4, 7, 8, 10, 11, 13, 16],
    outputPins: [2, 6, 9, 14, 15],
    adder: {
      a: [10, 8, 3, 1],
      b: [11, 7, 4, 16],
      carryIn: 13,
      sum: [9, 6, 2, 15],
      carryOut: 14,
    },
  },
  "7490": {
    name: "Decade Counter (Divide-by-2 and Divide-by-5)",
    floatingInputs: "ttl",
    floatingInputOverrides: { 2: "low", 3: "low", 6: "low", 7: "low" },
    pins: 14, vcc: 5, gnd: 10,
    inputPins: [1, 2, 3, 6, 7, 14],
    outputPins: [8, 9, 11, 12],
    counter: {
      clockA: 14, clockB: 1,
      resetZero: [2, 3], presetNine: [6, 7],
      outputs: [12, 9, 8, 11],
      upperModulus: 5,
      edge: "falling",
    },
  },
  "7493": {
    name: "4-Bit Binary Ripple Counter",
    floatingInputs: "ttl",
    floatingInputOverrides: { 2: "low", 3: "low" },
    pins: 14, vcc: 5, gnd: 10,
    inputPins: [1, 2, 3, 14],
    outputPins: [8, 9, 11, 12],
    counter: {
      clockA: 14, clockB: 1,
      resetZero: [2, 3],
      presetNine: [],
      outputs: [12, 9, 8, 11],
      upperModulus: 8,
      edge: "falling",
    },
  },
  "74138": {
    name: "3-to-8 Line Decoder / Demultiplexer",
    floatingInputs: "none",
    pins: 16, vcc: 16, gnd: 8,
    inputPins: [1, 2, 3, 4, 5, 6],
    outputPins: [15, 14, 13, 12, 11, 10, 9, 7],
    decoder3to8: {
      address: [1, 2, 3],       // A0, A1, A2 (least significant first)
      enableLow: [4, 5],        // G2A, G2B
      enableHigh: 6,            // G1
      outputs: [15, 14, 13, 12, 11, 10, 9, 7], // Y0..Y7, active LOW
      activeLow: true,
    },
  },
};

/**
 * Gate evaluator. A floating input remains unknown; never invent a logic level.
 */
function resolveGate(fn, inputValues) {
  const vals = Array.isArray(inputValues) ? inputValues : [inputValues];
  if (vals.some(v => v === null || v === undefined)) return null;
  switch (fn) {
    case "AND": return vals.every(v => v === 1) ? 1 : 0;
    case "OR": return vals.some(v => v === 1) ? 1 : 0;
    case "NOT": return vals[0] === 1 ? 0 : 1;
    case "NAND": return vals.every(v => v === 1) ? 0 : 1;
    case "NOR": return vals.some(v => v === 1) ? 0 : 1;
    case "XOR": return vals.reduce((a, b) => a ^ b, 0);
    default: return null;
  }
}

// Segment patterns used internally by the integrated hexadecimal display;
// these visual segments are not external board terminals or a separate IC.
const SEVEN_SEGMENT_ACTIVE_LOW = {
  0: [0, 0, 0, 0, 0, 0, 1],
  1: [1, 0, 0, 1, 1, 1, 1],
  2: [0, 0, 1, 0, 0, 1, 0],
  3: [0, 0, 0, 0, 1, 1, 0],
  4: [1, 0, 0, 1, 1, 0, 0],
  5: [0, 1, 0, 0, 1, 0, 0],
  6: [0, 1, 0, 0, 0, 0, 0],
  7: [0, 0, 0, 1, 1, 1, 1],
  8: [0, 0, 0, 0, 0, 0, 0],
  9: [0, 0, 0, 0, 1, 0, 0],
};

// Integrated four-bit hexadecimal display decoder. Segment order is
// [a,b,c,d,e,f,g]; 0 means ON for the common-anode display. B and D use
// their conventional seven-segment lowercase forms (b and d).
const SEVEN_SEGMENT_HEX_ACTIVE_LOW = {
  ...SEVEN_SEGMENT_ACTIVE_LOW,
  10: [0, 0, 0, 1, 0, 0, 0], // A
  11: [1, 1, 0, 0, 0, 0, 0], // b
  12: [0, 1, 1, 0, 0, 0, 1], // C
  13: [1, 0, 0, 0, 0, 1, 0], // d
  14: [0, 1, 1, 0, 0, 0, 0], // E
  15: [0, 1, 1, 1, 0, 0, 0], // F
};

if (typeof module !== "undefined" && module.exports) {
  module.exports = { IC_LIBRARY, resolveGate, SEVEN_SEGMENT_ACTIVE_LOW, SEVEN_SEGMENT_HEX_ACTIVE_LOW };
}
