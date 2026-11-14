// src/rules.js — Electrical connection validation rules for DeldSim.
// Pure logic module: categorizes board nodes by ID and decides whether two
// user-visible endpoints may be joined. No DOM dependencies.

(function (global) {
  'use strict';

  const CAT = {
    INPUT:   'INPUT',    // front-panel data input terminals
    CLOCK:   'CLOCK',    // periodic clock source terminals
    PULSE:   'PULSE',    // momentary simulator-driven pulse source
    OUTPUT:  'OUTPUT',   // numbered output channel terminals
    VCC:     'VCC',
    GND:     'GND',
    IC_PIN:  'IC_PIN',   // IC pins, e.g. pin-in-3-1 / pin-out-3-3
    PASSIVE: 'PASSIVE',  // breadboard holes and other passive points
  };

  function channelKey(nodeId) {
    if (typeof nodeId !== 'string' || !nodeId) return null;
    const id = nodeId.toLowerCase();
    let match = id.match(/^(?:sw-input|pin-input|led-input)-(\d+)$/);
    if (match) return 'input:' + match[1];
    match = id.match(/^(?:sw-clock|pin-clock|led-clock)-([\d_]+)$/);
    if (match) return 'clock:' + match[1];
    match = id.match(/^(?:pin-out|led-out)-(\d+)$/);
    if (match) return 'output:' + match[1];
    return null;
  }

  function isSameTerminalPair(nodeA, nodeB) {
    if (nodeA === nodeB) return false;
    const a = channelKey(nodeA);
    return a !== null && a === channelKey(nodeB);
  }

  function categorize(nodeId) {
    if (typeof nodeId !== 'string' || !nodeId) return CAT.PASSIVE;
    const id = nodeId.toLowerCase();

    // Rail names are checked first: pin-out-vcc and led-out-vcc are supplies,
    // not numbered output-channel terminals.
    if (id.includes('vcc')) return CAT.VCC;
    if (id.includes('gnd')) return CAT.GND;
    if (id === 'pin-pulse') return CAT.PULSE;

    if (/^(?:sw-clock|pin-clock|led-clock)-[\d_]+$/.test(id) ||
        id.startsWith('clock')) return CAT.CLOCK;
    if (/^(?:sw-input|pin-input|led-input)-[\d_]+$/.test(id)) return CAT.INPUT;
    if (/^(?:pin-out|led-out)-\d+$/.test(id)) return CAT.OUTPUT;

    if (id.startsWith('pin-in-') || id.startsWith('pin-out-')) return CAT.IC_PIN;
    return CAT.PASSIVE;
  }

  // INPUT <-> INPUT, OUTPUT <-> OUTPUT and INPUT <-> OUTPUT are prohibited.
  // PASSIVE and IC_PIN endpoints may connect to any channel or power rail.
  function canConnect(nodeA, nodeB) {
    const a = categorize(nodeA);
    const b = categorize(nodeB);
    const pair = (x, y) => (a === x && b === y) || (a === y && b === x);

    if (a === CAT.PASSIVE || a === CAT.IC_PIN) return true;
    if (b === CAT.PASSIVE || b === CAT.IC_PIN) return true;
    if (pair(CAT.PULSE, CAT.INPUT)) return false;
    if (pair(CAT.PULSE, CAT.CLOCK)) return false;
    if (pair(CAT.INPUT, CAT.INPUT)) return false;
    if (pair(CAT.CLOCK, CAT.CLOCK)) return false;
    if (pair(CAT.CLOCK, CAT.INPUT)) return false;
    if (pair(CAT.OUTPUT, CAT.OUTPUT)) return false;
    if (pair(CAT.INPUT, CAT.OUTPUT)) return false;
    // CLOCK <-> OUTPUT is allowed so an enabled clock can blink a panel LED.
    if (pair(CAT.VCC, CAT.VCC)) return true;
    if (pair(CAT.GND, CAT.GND)) return true;
    return true;
  }

  const Rules = { CAT, categorize, channelKey, isSameTerminalPair, canConnect };

  if (typeof module !== 'undefined' && module.exports) module.exports = Rules;
  global.Rules = Rules;
})(typeof window !== 'undefined' ? window : globalThis);
