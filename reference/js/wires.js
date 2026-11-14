// src/wires.js -- grid A* wire router + SVG renderer for DeldSim Clone.
// Draws orthogonal, thin open-polyline wires into the board SVG.
// Depends on: board.js (getPointForNode, BOARD_NODES), netlist.js
// (Netlist.getConnected / getNetValue). Load order: ics, board, netlist,
// wires, main.
//
// ---------------------------------------------------------------------------
// ROUTER OVERVIEW (replaces the old constructive/template router)
//
// Grid: 4-unit cells over the 1576x720 board (395 x 181). Every board node
// sits exactly on a cell centre, so the exact pin coordinates survive
// routing untouched (no endpoint "step").
//
// Search: A* over states (cell, axis) with 4-neighbour moves, a binary heap,
// Manhattan heuristic, step cost 1, turn penalty, expansion cap.
//
// Hard rules (cells the search may never enter / moves it may never make):
//   - IC base hitboxes (base footprint inflated by 16 left/right, 14 up/down
//     => x-16..x+168, y 242..438) are blocked for ALL five slots.
//   - Horizontal moves only on lane tracks (y multiple of 8 inside the top
//     lane 120..224 or the bottom lane 448..552). Vertical moves are free
//     wherever a cell is not blocked, so IC-zone crossings happen only in
//     the gap columns between hitboxes.
//   - Cells within 8 units of any node centre that is not this wire's own
//     endpoint are blocked (keeps wires off other LEDs / switches / pins).
//   - Endpoint stubs: an IC pin exits HORIZONTALLY along its exact y, out of
//     its own hitbox (pins 1-10 left, 11-20 right); the search starts from
//     any free exit cell beyond the hitbox edge. A strip node (top strip /
//     bottom strip) exits VERTICALLY into a lane.
// Net-aware costs (occupancy of already-drawn wires):
//   - same net: cheaper than free space (trunk sharing / branching works);
//   - different net, same orientation in a cell: blocked;
//   - different net, perpendicular crossing: +CROSS, but never a turn on it;
//   - cells adjacent and parallel to a different net's run: +HALO, so
//     wires keep a visible gap (>= 8 units when there is room).
// Fallbacks: strict -> soft collinear -> tighter hitboxes -> null (the
// caller must NOT draw a diagonal; drawWire returns null).
// ---------------------------------------------------------------------------

const OBSTACLES = [
  { left: 136,  top: 256, w: 152, h: 168 },
  { left: 424,  top: 256, w: 152, h: 168 },
  { left: 712,  top: 256, w: 152, h: 168 },
  { left: 1000, top: 256, w: 152, h: 168 },
  { left: 1288, top: 256, w: 152, h: 168 },
];

const CELL = 4;
const NX = 395, NY = 181;                 // cells: x 0..1576, y 0..720
const IX_MIN = 2, IX_MAX = NX - 3, IY_MIN = 2, IY_MAX = NY - 3;

function makeHitboxes(dx, dy) {
  return OBSTACLES.map(o => ({
    left: o.left - dx, right: o.left + o.w + dx,
    top: o.top - dy,   bottom: o.top + o.h + dy,
  }));
}
// Hard hitboxes used by the router (x-16 .. x+168, y 242 .. 438).
const HITBOXES = makeHitboxes(16, 14);
// Last-resort tighter boxes (third fallback pass only).
const HITBOXES_TIGHT = makeHitboxes(8, 6);

// Lane bands (SVG coords; middle panel spans y 92..580).
const TOP_LANE = { lo: 111, hi: 227 };
const BOTTOM_LANE = { lo: 444, hi: 561 };
const TOP_TRACKS = [];
for (let y = 120; y <= 224; y += 8) TOP_TRACKS.push(y);
const BOTTOM_TRACKS = [];
for (let y = 448; y <= 552; y += 8) BOTTOM_TRACKS.push(y);

function isLaneY(y) {
  return y % 8 === 0 && ((y >= 120 && y <= 224) || (y >= 448 && y <= 552));
}
const LANE_ROW = new Uint8Array(NY);
for (let iy = 0; iy < NY; iy++) LANE_ROW[iy] = isLaneY(iy * CELL) ? 1 : 0;

// Cost model.
const STEP_COST = 1;
const SAME_NET_COST = 0.5;       // overlaying / sharing a same-net trunk
const TURN_COST = 6;
const CROSS_COST = 8;            // perpendicular crossing of a different net
const HALO_COST = 6;             // adjacent + parallel to a different net
const SOFT_COLLINEAR_COST = 60;  // fallback pass only
const SOFT_TURN_COST = 40;       // fallback pass only
const MAX_EXPANSIONS = 150000;
const STUB_EXTRA_CELLS = 12;     // how far beyond a hitbox a pin may exit
const KEEPOUT_RADIUS = 8;        // blocked radius around foreign nodes

// ---------------------------------------------------------------------------
// Endpoint classification (by coordinates, so any node id works)
// ---------------------------------------------------------------------------

// kind 'ic'   : pin inside a base hitbox; horizontal stub (dirX -1 / +1).
// kind 'vstub': everything else (strip nodes); vertical exit.
function classifyEndpoint(nodeId, pt) {
  if (pt) {
    for (let i = 0; i < HITBOXES.length; i++) {
      const hb = HITBOXES[i];
      if (pt.x >= hb.left && pt.x <= hb.right && pt.y >= hb.top && pt.y <= hb.bottom) {
        const mid = (OBSTACLES[i].left + OBSTACLES[i].w / 2);
        return { kind: 'ic', dirX: pt.x < mid ? -1 : 1, hbIndex: i };
      }
    }
    return { kind: 'vstub', dirY: pt.y < 340 ? 1 : -1 };
  }
  return { kind: 'vstub', dirY: 1 };
}

// ---------------------------------------------------------------------------
// Occupancy of drawn wires (per cell, with orientation flags)
// ---------------------------------------------------------------------------

// cellIdx -> [{ key, a, b, h, v }]
const _occ = new Map();
// wire key -> [cellIdx, ...]
const _wireCells = new Map();

function cellOf(x, y) { return [Math.round(x / CELL), Math.round(y / CELL)]; }

function releaseOccupancy(key) {
  const cells = _wireCells.get(key);
  if (!cells) return;
  for (const idx of cells) {
    const list = _occ.get(idx);
    if (!list) continue;
    const kept = list.filter(o => o.key !== key);
    if (kept.length) _occ.set(idx, kept); else _occ.delete(idx);
  }
  _wireCells.delete(key);
}

function clearOccupancy() {
  _occ.clear();
  _wireCells.clear();
}

// Record a drawn wire (corner list) so later routes avoid / share it.
function recordOccupancy(key, a, b, pts) {
  releaseOccupancy(key);
  const touched = new Map(); // idx -> { h, v }
  const mark = (ix, iy, h, v) => {
    if (ix < 0 || iy < 0 || ix >= NX || iy >= NY) return;
    const idx = iy * NX + ix;
    const t = touched.get(idx) || { h: false, v: false };
    t.h = t.h || h; t.v = t.v || v;
    touched.set(idx, t);
  };
  for (let i = 0; i + 1 < pts.length; i++) {
    const [ax, ay] = cellOf(pts[i][0], pts[i][1]);
    const [bx, by] = cellOf(pts[i + 1][0], pts[i + 1][1]);
    if (ay === by) for (let x = Math.min(ax, bx); x <= Math.max(ax, bx); x++) mark(x, ay, true, false);
    else if (ax === bx) for (let y = Math.min(ay, by); y <= Math.max(ay, by); y++) mark(ax, y, false, true);
  }
  // Corners and endpoints count as both orientations (nothing may touch them).
  for (const p of pts) { const [cx, cy] = cellOf(p[0], p[1]); mark(cx, cy, true, true); }
  const idxs = [];
  for (const [idx, t] of touched) {
    const list = _occ.get(idx) || [];
    list.push({ key, a, b, h: t.h, v: t.v });
    _occ.set(idx, list);
    idxs.push(idx);
  }
  _wireCells.set(key, idxs);
}

function isSameNet(o, netSet) {
  return !!(netSet && (netSet.has(o.a) || netSet.has(o.b)));
}

// ---------------------------------------------------------------------------
// Static obstacles: hitboxes + foreign node keep-outs
// ---------------------------------------------------------------------------

const _extraRoutingPoints = [];
function registerRoutingPoints(list) {
  for (const p of list || []) {
    if (p && Number.isFinite(p.x) && Number.isFinite(p.y)) {
      _extraRoutingPoints.push({ id: p.id || null, x: p.x, y: p.y });
    }
  }
  _keepoutPts = null;
}

function clearRoutingPoints() {
  _extraRoutingPoints.length = 0;
  _keepoutPts = null;
}

let _keepoutPts = null; // [{x,y}] node centres outside every hitbox
function keepoutPoints() {
  if (_keepoutPts) return _keepoutPts;
  const pts = [];
  try {
    if (typeof BOARD_NODES !== 'undefined' && BOARD_NODES) {
      for (const id in BOARD_NODES) {
        const p = BOARD_NODES[id];
        if (p && Number.isFinite(p.x) && Number.isFinite(p.y)) pts.push({ id, x: p.x, y: p.y });
      }
    }
  } catch (_) { /* headless without board.js */ }
  for (const p of _extraRoutingPoints) pts.push({ id: p.id || null, x: p.x, y: p.y });
  _keepoutPts = pts.filter(p => !HITBOXES.some(hb =>
    p.x >= hb.left && p.x <= hb.right && p.y >= hb.top && p.y <= hb.bottom));
  return _keepoutPts;
}

const _hitboxGrids = new Map(); // hitbox array -> Uint8Array of blocked cells
function hitboxGrid(hbs) {
  let g = _hitboxGrids.get(hbs);
  if (g) return g;
  g = new Uint8Array(NX * NY);
  for (const hb of hbs) {
    const ix1 = Math.ceil(hb.left / CELL), ix2 = Math.floor(hb.right / CELL);
    const iy1 = Math.ceil(hb.top / CELL),  iy2 = Math.floor(hb.bottom / CELL);
    for (let iy = iy1; iy <= iy2; iy++)
      for (let ix = ix1; ix <= ix2; ix++) g[iy * NX + ix] = 1;
  }
  _hitboxGrids.set(hbs, g);
  return g;
}

// A strip node and its siblings share a column inside the same strip
// (top strip y < 242, bottom strip y > 438).
function sameChannel(p, x, y) {
  if (p.x !== x) return false;
  return (p.y < HITBOXES[0].top && y < HITBOXES[0].top) ||
         (p.y > HITBOXES[0].bottom && y > HITBOXES[0].bottom);
}

// Blocked grid for one routing call: hitboxes + keep-outs around every node
// that is NOT one of this wire's endpoints.
function isDisplayInputTerminal(id) {
  return typeof id === "string" &&
    /^pin-seg-digit-(?:tens|ones)-[1-4]$/.test(id);
}

function buildBlocked(hbs, x1, y1, x2, y2, ctx) {
  const blocked = new Uint8Array(hitboxGrid(hbs));
  const r = Math.floor(KEEPOUT_RADIUS / CELL);
  const touchesDisplayTerminal = isDisplayInputTerminal(ctx?.idA) ||
    isDisplayInputTerminal(ctx?.idB);
  for (const p of keepoutPoints()) {
    if ((p.x === x1 && p.y === y1) || (p.x === x2 && p.y === y2)) continue;
    // The two four-bit display input banks are densely packed. On routes
    // touching this area, block neighbouring terminal cells but not their
    // full halo; this leaves a narrow vertical escape corridor while keeping
    // every other terminal center blocked.
    // Sibling nodes of the same strip channel (e.g. the pin under an LED)
    // sit on the endpoint's own vertical exit path: never block those.
    if (sameChannel(p, x1, y1) || sameChannel(p, x2, y2)) continue;
    const cx = Math.round(p.x / CELL), cy = Math.round(p.y / CELL);
    const nodeRadius = touchesDisplayTerminal && isDisplayInputTerminal(p.id) ? 0 : r;
    for (let dy = -nodeRadius; dy <= nodeRadius; dy++)
      for (let dx = -nodeRadius; dx <= nodeRadius; dx++) {
        const ix = cx + dx, iy = cy + dy;
        if (ix >= 0 && iy >= 0 && ix < NX && iy < NY) blocked[iy * NX + ix] = 1;
      }
  }
  return blocked;
}

// ---------------------------------------------------------------------------
// Cost of entering a cell / turning inside a cell
// ---------------------------------------------------------------------------

// m: 0 = horizontal move, 1 = vertical move. Returns Infinity when illegal.
function enterCost(idx, m, ctx) {
  let cost = STEP_COST;
  const occ = _occ.get(idx);
  if (occ) {
    let same = false, diff = false, collinear = false, corner = false;
    for (const o of occ) {
      if (isSameNet(o, ctx.netSet)) { same = true; continue; }
      diff = true;
      if (o.h && o.v) corner = true;
      else if ((m === 0 && o.h) || (m === 1 && o.v)) collinear = true;
    }
    if (collinear || corner) {
      if (!ctx.soft) return Infinity;
      cost += SOFT_COLLINEAR_COST;
    } else if (diff) {
      cost += CROSS_COST;
    } else if (same) {
      cost = SAME_NET_COST;
    }
  }
  // Halo: adjacent, parallel cells of a different net.
  const n1 = m === 0 ? idx - NX : idx - 1;
  const n2 = m === 0 ? idx + NX : idx + 1;
  for (const n of [n1, n2]) {
    const oc = _occ.get(n);
    if (!oc) continue;
    for (const o of oc) {
      if (isSameNet(o, ctx.netSet)) continue;
      if ((m === 0 && o.h) || (m === 1 && o.v)) { cost += HALO_COST; break; }
    }
  }
  return cost;
}

// Extra cost of changing axis inside cell idx (never on a foreign wire).
function turnPenalty(idx, ctx) {
  const occ = _occ.get(idx);
  if (occ) {
    for (const o of occ) {
      if (!isSameNet(o, ctx.netSet)) return ctx.soft ? SOFT_TURN_COST : Infinity;
    }
  }
  return 0;
}

// ---------------------------------------------------------------------------
// Endpoint plans: where the search may start / finish
// ---------------------------------------------------------------------------

// Returns { starts: [{idx, axis, g}], goals: Map(idx -> extra cost) }.
// IC pin: exit cells along the pin's row beyond its own hitbox edge. The
//   stub (pin -> exit cell) is part of the cost; a start state has axis 0
//   (horizontal) so the first vertical move pays one turn, and a goal needs
//   a vertical arrival plus one turn into the stub.
// Strip node: its own cell, vertical axis.
function buildPlan(ep, x, y, hbs, ctx, blocked) {
  const starts = [], goals = new Map();
  const iy = Math.round(y / CELL);
  if (ep.kind === 'ic') {
    const hb = hbs[ep.hbIndex];
    const dir = ep.dirX;
    const edgeX = dir < 0 ? hb.left : hb.right;
    let g = Math.abs(edgeX - x) / CELL * STEP_COST;   // pin -> hitbox edge
    for (let k = 1; k <= STUB_EXTRA_CELLS; k++) {
      const ix = Math.round((edgeX + dir * k * CELL) / CELL);
      if (ix < IX_MIN || ix > IX_MAX) break;
      const idx = iy * NX + ix;
      if (blocked[idx]) break;
      const c = enterCost(idx, 0, ctx);
      if (!isFinite(c)) break;
      g += c;
      starts.push({ idx, axis: 0, g });
      goals.set(idx, g + TURN_COST);
    }
  } else {
    const ix = Math.round(x / CELL);
    const idx = iy * NX + ix;
    if (!blocked[idx]) {
      starts.push({ idx, axis: 1, g: 0 });
      goals.set(idx, 0);
    }
  }
  return { starts, goals };
}

// ---------------------------------------------------------------------------
// Binary min-heap (parallel arrays: key, value)
// ---------------------------------------------------------------------------

class MinHeap {
  constructor() { this.k = []; this.v = []; }
  get size() { return this.k.length; }
  push(key, val) {
    const k = this.k, v = this.v;
    let i = k.length;
    k.push(key); v.push(val);
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (k[p] <= key) break;
      k[i] = k[p]; v[i] = v[p]; i = p;
    }
    k[i] = key; v[i] = val;
  }
  popKey() { return this.k[0]; }
  pop() {
    const k = this.k, v = this.v;
    const topV = v[0];
    const lastK = k.pop(), lastV = v.pop();
    const n = k.length;
    if (n > 0) {
      let i = 0;
      for (;;) {
        let c = 2 * i + 1;
        if (c >= n) break;
        if (c + 1 < n && k[c + 1] < k[c]) c++;
        if (k[c] >= lastK) break;
        k[i] = k[c]; v[i] = v[c]; i = c;
      }
      k[i] = lastK; v[i] = lastV;
    }
    return topV;
  }
}

// ---------------------------------------------------------------------------
// A*
// ---------------------------------------------------------------------------

const DIRS = [[1, 0, 0], [-1, 0, 0], [0, 1, 1], [0, -1, 1]];

// Returns an array of {ix, iy} cells from the start exit cell to the goal
// exit cell, or null.
function astar(startPlan, goalPlan, blocked, ctx) {
  if (!startPlan.starts.length || !goalPlan.goals.size) return null;
  const N = NX * NY * 2;
  const g = new Float32Array(N).fill(Infinity);
  const par = new Int32Array(N).fill(-1);
  const closed = new Uint8Array(N);

  // Heuristic bounds over the goal cells (they share one row).
  let gminx = Infinity, gmaxx = -Infinity, gy = 0;
  for (const idx of goalPlan.goals.keys()) {
    const ix = idx % NX; gy = (idx / NX) | 0;
    if (ix < gminx) gminx = ix;
    if (ix > gmaxx) gmaxx = ix;
  }
  const h = (ix, iy) => {
    const dx = ix < gminx ? gminx - ix : (ix > gmaxx ? ix - gmaxx : 0);
    return (dx + Math.abs(iy - gy)) * STEP_COST;
  };

  const heap = new MinHeap();
  for (const s of startPlan.starts) {
    const st = s.idx * 2 + s.axis;
    if (s.g < g[st]) {
      g[st] = s.g;
      heap.push(s.g + h(s.idx % NX, (s.idx / NX) | 0), st);
    }
  }

  let best = Infinity, bestState = -1, expansions = 0;
  while (heap.size) {
    const f = heap.popKey();
    if (f >= best) break;
    const s = heap.pop();
    if (closed[s]) continue;
    closed[s] = 1;
    if (++expansions > MAX_EXPANSIONS) break;

    const idx = s >> 1, axis = s & 1;
    const gc = goalPlan.goals.get(idx);
    if (gc !== undefined && axis === 1) {
      const total = g[s] + gc;
      if (total < best) { best = total; bestState = s; }
    }

    const ix = idx % NX, iy = (idx / NX) | 0;
    for (let d = 0; d < 4; d++) {
      const nx = ix + DIRS[d][0], ny = iy + DIRS[d][1], m = DIRS[d][2];
      if (nx < IX_MIN || nx > IX_MAX || ny < IY_MIN || ny > IY_MAX) continue;
      const nidx = ny * NX + nx;
      if (blocked[nidx]) continue;
      if (m === 0 && !LANE_ROW[ny]) continue;       // horizontal only on lanes
      let cost = enterCost(nidx, m, ctx);
      if (cost === Infinity) continue;
      if (m !== axis) {
        const tp = turnPenalty(idx, ctx);
        if (tp === Infinity) continue;
        cost += TURN_COST + tp;
      }
      const ng = g[s] + cost;
      const ns = nidx * 2 + m;
      if (ng < g[ns]) {
        g[ns] = ng; par[ns] = s;
        heap.push(ng + h(nx, ny), ns);
      }
    }
  }
  if (bestState < 0) return null;

  const cells = [];
  for (let s = bestState; s !== -1; s = par[s]) {
    const idx = s >> 1;
    cells.push({ ix: idx % NX, iy: (idx / NX) | 0 });
  }
  cells.reverse();
  return cells;
}

// ---------------------------------------------------------------------------
// Route assembly
// ---------------------------------------------------------------------------

// Axis-aligned segments intersect iff their bounding boxes intersect.
function segsTouch(a, b) {
  return !(Math.max(a[0][0], a[1][0]) < Math.min(b[0][0], b[1][0]) ||
           Math.max(b[0][0], b[1][0]) < Math.min(a[0][0], a[1][0]) ||
           Math.max(a[0][1], a[1][1]) < Math.min(b[0][1], b[1][1]) ||
           Math.max(b[0][1], b[1][1]) < Math.min(a[0][1], a[1][1]));
}

// True when the polyline touches itself (non-adjacent segments).
function selfTouches(pts) {
  const segs = [];
  for (let i = 0; i + 1 < pts.length; i++) segs.push([pts[i], pts[i + 1]]);
  for (let i = 0; i < segs.length; i++)
    for (let j = i + 2; j < segs.length; j++)
      if (segsTouch(segs[i], segs[j])) return true;
  return false;
}

// Straight shot between two facing IC pins on the same row, through the gap.
function directShot(epA, epB, x1, y1, x2, y2, hbs, blocked, ctx) {
  if (epA.kind !== 'ic' || epB.kind !== 'ic' || y1 !== y2) return null;
  const left = x1 < x2 ? { ep: epA, x: x1 } : { ep: epB, x: x2 };
  const right = x1 < x2 ? { ep: epB, x: x2 } : { ep: epA, x: x1 };
  if (left.ep.dirX !== 1 || right.ep.dirX !== -1) return null;
  if (left.ep.hbIndex === right.ep.hbIndex) return null;
  const iy = Math.round(y1 / CELL);
  const ixA = Math.round((hbs[left.ep.hbIndex].right + CELL) / CELL);
  const ixB = Math.round((hbs[right.ep.hbIndex].left - CELL) / CELL);
  if (ixA > ixB) return null;
  for (let ix = ixA; ix <= ixB; ix++) {
    const idx = iy * NX + ix;
    if (blocked[idx]) return null;
    if (!isFinite(enterCost(idx, 0, ctx))) return null;
  }
  return [[x1, y1], [x2, y2]];
}

function routePass(x1, y1, x2, y2, epA, epB, hbs, ctx) {
  const blocked = buildBlocked(hbs, x1, y1, x2, y2, ctx);
  const direct = directShot(epA, epB, x1, y1, x2, y2, hbs, blocked, ctx);
  if (direct) return direct;

  const planA = buildPlan(epA, x1, y1, hbs, ctx, blocked);
  const planB = buildPlan(epB, x2, y2, hbs, ctx, blocked);
  const cells = astar(planA, planB, blocked, ctx);
  if (!cells) return null;

  const pts = [];
  if (epA.kind === 'ic') pts.push([x1, y1]);
  for (const c of cells) pts.push([c.ix * CELL, c.iy * CELL]);
  if (epB.kind === 'ic') pts.push([x2, y2]);
  const out = simplify(pts);
  if (selfTouches(out)) return null;
  return out;
}

const ROUTE_PASSES = [
  { soft: false, hbs: HITBOXES },
  { soft: true,  hbs: HITBOXES },
  { soft: true,  hbs: HITBOXES_TIGHT },
];

// opts: { netSet:Set|null, idA, idB }. Returns the CORNER LIST (pins
// included) or null when no legal route exists. Never returns a diagonal.
function routeBetween(x1, y1, x2, y2, opts) {
  opts = opts || {};
  x1 = Math.round(x1); y1 = Math.round(y1);
  x2 = Math.round(x2); y2 = Math.round(y2);
  if (x1 === x2 && y1 === y2) return [[x1, y1], [x2, y2]];

  const idA = opts.idA || '', idB = opts.idB || '';
  const netSet = opts.netSet ||
    new Set([idA, idB].filter(Boolean));
  const epA = classifyEndpoint(idA, { x: x1, y: y1 });
  const epB = classifyEndpoint(idB, { x: x2, y: y2 });

  for (const pass of ROUTE_PASSES) {
    const route = routePass(x1, y1, x2, y2, epA, epB, pass.hbs,
                            { netSet, soft: pass.soft, idA, idB });
    if (route) return route;
  }
  console.warn('Wires: no route for', idA || `${x1},${y1}`, '\u2194', idB || `${x2},${y2}`);
  return null;
}

// Public entry: `d` string of an open polyline between two points ('' when
// no legal route exists).
function buildWirePath(x1, y1, x2, y2, opts) {
  opts = opts || {};
  x1 = Math.round(x1); y1 = Math.round(y1);
  x2 = Math.round(x2); y2 = Math.round(y2);
  if (x1 === x2 && y1 === y2) return '';
  const corners = routeBetween(x1, y1, x2, y2, opts);
  if (!corners) return '';
  const finalPts = finalizeRoute(corners, x1, y1, x2, y2, opts.idA, opts.idB);
  return toOpenPath(finalPts);
}

// Drop collinear middles, pin endpoints to EXACT coordinates (they already
// match because every node sits on the grid) and run the permanent
// orthogonality guard: every consecutive pair must share x or y.
function finalizeRoute(pts, x1, y1, x2, y2, idA, idB) {
  let s = simplify(pts.map(p => [Math.round(p[0]), Math.round(p[1])]));
  if (s.length < 2) return [[x1, y1], [x2, y2]];

  // Exact endpoints: replace the first/last point only when it lies on the
  // pin's row or column (a pure snap); otherwise prepend/append the pin.
  const first = s[0];
  if (first[0] === x1 || first[1] === y1) s[0] = [x1, y1];
  else s.unshift([x1, y1]);
  const last = s[s.length - 1];
  if (last[0] === x2 || last[1] === y2) s[s.length - 1] = [x2, y2];
  else s.push([x2, y2]);
  s = simplify(s);

  const out = [s[0]];
  for (let i = 1; i < s.length; i++) {
    const p = out[out.length - 1], q = s[i];
    if (p[0] !== q[0] && p[1] !== q[1]) {
      console.warn('Wires: non-orthogonal segment repaired', idA || `${x1},${y1}`,
                   '\u2194', idB || `${x2},${y2}`, JSON.stringify(s));
      out.push([q[0], p[1]]);
    }
    if (out[out.length - 1][0] !== q[0] || out[out.length - 1][1] !== q[1]) out.push(q);
  }
  return out;
}

// Drop duplicate + collinear middle points, keep only corners.
function simplify(pts) {
  const clean = [];
  for (const p of pts) {
    const last = clean[clean.length - 1];
    if (!last || last[0] !== p[0] || last[1] !== p[1]) clean.push([p[0], p[1]]);
  }
  if (clean.length < 3) return clean;
  const out = [clean[0]];
  for (let i = 1; i < clean.length - 1; i++) {
    const a = out[out.length - 1], b = clean[i], c = clean[i + 1];
    const collinear = (a[0] === b[0] && b[0] === c[0]) ||
                      (a[1] === b[1] && b[1] === c[1]);
    if (!collinear) out.push(b);
  }
  out.push(clean[clean.length - 1]);
  return out;
}

// Open polyline: M .. L .. (no retrace, no Z).
function toOpenPath(pts) {
  if (!pts || pts.length < 2) return '';
  let d = `M ${pts[0][0]} ${pts[0][1]}`;
  for (let i = 1; i < pts.length; i++) d += ` L ${pts[i][0]} ${pts[i][1]}`;
  return d;
}

// ---------------------------------------------------------------------------
// Color scheme — HIGH red, LOW dark, floating cyan (matches original).
// ---------------------------------------------------------------------------

function colorForValue(value) {
  if (value === 1)   return "#ff2a2a";  // HIGH — red
  if (value === 0)   return "#1a1a1a";  // LOW  — dark
  return "#00f9f9";                     // floating / null — cyan
}

// ---------------------------------------------------------------------------
// Wire identity colors — VCC nets are red, GND nets dark gray, everything
// else cycles through a distinct palette keyed off the wire ID.
// ---------------------------------------------------------------------------

const WIRE_PALETTE = [
  "#3498db", "#9b59b6", "#f1c40f", "#1abc9c",
  "#e67e22", "#e84393", "#00cec9", "#fdcb6e",
];

// Stable golden-angle hue spacing avoids palette repeats on larger circuits.
function getUniqueWireColor(wireId) {
  const id = Math.max(1, Number(wireId) || 1);
  const hue = (id * 137.50776405003785 + 18) % 360;
  return `hsl(${hue}, 72%, 58%)`;
}

let _wireIdCounter = 0;
const _wireIds = new Map(); // canonical key -> numeric wire id

function getWireId(key) {
  if (!_wireIds.has(key)) _wireIds.set(key, ++_wireIdCounter);
  return _wireIds.get(key);
}

// Does this node ID name or sit on a power rail? (prefix/substring match
// against board.js naming: pin-out-vcc, led-out-vcc, *vcc*, *gnd* …)
function isVccNode(id)  { return /vcc/i.test(id); }
function isGndNode(id)  { return /gnd/i.test(id); }

// netSet: Set of node IDs electrically connected to this wire's net (BFS via
// Netlist.getConnected), or null when Netlist is unavailable.
function getWireColor(nodeA, nodeB, wireId, netSet) {
  if (netSet && netSet.size) {
    for (const n of netSet) {
      if (isVccNode(n)) return "#e74c3c"; // Red — reaches VCC
      if (isGndNode(n)) return "#2c3e50"; // Dark Gray — reaches GND
    }
  } else if (isVccNode(nodeA) || isVccNode(nodeB)) {
    return "#e74c3c";
  } else if (isGndNode(nodeA) || isGndNode(nodeB)) {
    return "#2c3e50";
  }
  const id = (typeof wireId === "number" && wireId > 0) ? wireId : getWireId([nodeA, nodeB].sort().join("||"));
  return WIRE_PALETTE[id % WIRE_PALETTE.length];
}

// ---------------------------------------------------------------------------
// Wires module
// ---------------------------------------------------------------------------

const Wires = (function () {
  // Internal registry — do NOT rely on querying the DOM for state.
  // Key: canonical pair string (alphabetically sorted) -> <g> element.
  const wireRegistry = new Map();

  let svgRoot = null;
  let circuitPowered = true;

  function wireKey(a, b) {
    return [a, b].sort().join("||");
  }

  function computeNetSet(nodeId) {
    if (typeof Netlist === "undefined" || !Netlist ||
        typeof Netlist.getConnected !== "function") return null;
    const seen = new Set([nodeId]);
    const queue = [nodeId];
    while (queue.length) {
      const cur = queue.shift();
      let neighbors = [];
      try { neighbors = Netlist.getConnected(cur) || []; } catch (_) { neighbors = []; }
      for (const n of neighbors) {
        if (!seen.has(n)) { seen.add(n); queue.push(n); }
      }
    }
    return seen;
  }

  function init(svgElement) {
    svgRoot = svgElement;
  }

  // Create and append a <g> wire (visible thin path + invisible fat hit
  // path on top) between two nodes. Coordinates come from getPointForNode();
  // value from Netlist.getNetValue() (falls back to null → cyan).
  function drawWire(nodeIdA, nodeIdB) {
    if (!svgRoot) throw new Error("Wires.init(svg) must be called before drawWire");

    const key = wireKey(nodeIdA, nodeIdB);
    if (wireRegistry.has(key)) {
      const existing = wireRegistry.get(key);
      applyColor(existing, resolveNodeValue(nodeIdA));
      return existing;
    }

    const pA = getPointForNode(nodeIdA);
    const pB = getPointForNode(nodeIdB);
    if (!pA || !pB) return null; // unknown node ids — nothing to draw

    const netSet = computeNetSet(nodeIdA);
    const wireId = getWireId(key);
    const identityColor = circuitPowered
      ? getWireColor(nodeIdA, nodeIdB, wireId, netSet)
      : getUniqueWireColor(wireId);

    const rx1 = Math.round(pA.x), ry1 = Math.round(pA.y);
    const rx2 = Math.round(pB.x), ry2 = Math.round(pB.y);
    if (rx1 === rx2 && ry1 === ry2) return null; // same physical point
    const opts = { netSet, idA: nodeIdA, idB: nodeIdB };

    // One routing pass per draw. The corner list feeds BOTH the visible
    // polyline and the occupancy record (recorded from SNAPPED cells).
    const corners = routeBetween(rx1, ry1, rx2, ry2, opts);
    if (!corners) return null; // no legal route: caller rolls back, never draws a diagonal
    const finalPts = finalizeRoute(corners, rx1, ry1, rx2, ry2, nodeIdA, nodeIdB);
    const d = toOpenPath(finalPts);

    const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
    // data-from/data-to drive right-click removal via closest('[data-from]').
    // No trainer_kit_clickable_element class — annotateBoard skips anything
    // with dataset.from anyway, and clickability comes from the fat hit path.
    g.setAttribute("class", "wire-group");

    const visible = document.createElementNS("http://www.w3.org/2000/svg", "path");
    visible.setAttribute("d", d);
    visible.setAttribute("stroke", identityColor);
    visible.setAttribute("stroke-linejoin", "round");
    visible.setAttribute("stroke-linecap", "round");
    visible.setAttribute("stroke-width", "1.5");
    visible.setAttribute("fill", "none");
    visible.setAttribute("pointer-events", "none");
    g.appendChild(visible);

    const hit = document.createElementNS("http://www.w3.org/2000/svg", "path");
    hit.setAttribute("d", d);
    hit.setAttribute("stroke", "transparent");
    hit.setAttribute("stroke-width", "10");
    hit.setAttribute("fill", "none");
    hit.setAttribute("pointer-events", "stroke");
    g.appendChild(hit);

    g.dataset.from = nodeIdA;
    g.dataset.to = nodeIdB;
    g.dataset.type = "wire";

    // Occupancy from the final corner list (all nodes sit on the 4-unit grid).
    recordOccupancy(key, nodeIdA, nodeIdB, finalPts);

    svgRoot.appendChild(g);
    wireRegistry.set(key, g);
    g._wireId = wireId; // identity used by updateAllColors for palette fallback
    return g;
  }

  // Resolve current value for a node's net; tolerate missing Netlist in tests.
  function resolveNodeValue(nodeId) {
    if (typeof Netlist !== "undefined" && Netlist && typeof Netlist.getNetValue === "function") {
      const v = Netlist.getNetValue(nodeId);
      return v === 1 || v === 0 ? v : null;
    }
    return null;
  }

  // Recolor the visible path of a wire group (hit path stays transparent).
  function applyColor(groupEl, value) {
    const vis = (groupEl && groupEl.querySelector) ? groupEl.querySelector("path") : groupEl;
    if (!vis) return;
    if (!circuitPowered) {
      const wireId = groupEl && groupEl._wireId
        ? groupEl._wireId
        : getWireId([groupEl?.dataset?.from || "", groupEl?.dataset?.to || ""].sort().join("||"));
      vis.setAttribute("stroke", getUniqueWireColor(wireId));
      return;
    }
    if (value === 1 || value === 0) {
      vis.setAttribute("stroke", colorForValue(value)); // powered: HIGH/LOW
    } else if (groupEl && groupEl.dataset) {
      // Powered but floating: retain the existing identity color behavior.
      vis.setAttribute("stroke", getWireColor(groupEl.dataset.from, groupEl.dataset.to, groupEl._wireId, null));
    }
  }

  // Remove the <g> for this wire pair. Canonical key means both
  // (A→B) and (B→A) resolve to the same registry entry.
  function removeWire(nodeIdA, nodeIdB) {
    const key = wireKey(nodeIdA, nodeIdB);
    const el = wireRegistry.get(key);
    if (!el) return false;
    if (el.parentNode) el.parentNode.removeChild(el);
    wireRegistry.delete(key);
    releaseOccupancy(key);         // keep occupancy in sync
    _wireIds.delete(key);          // free the identity so colors don't drift
    return true;
  }

  // After resolveCircuit() — re-color every wire from the state map.
  // stateMap may be a Map (nodeId → 0|1|null) or plain object.
  function lookupState(stateMap, nodeId) {
    if (!stateMap) return null;
    if (typeof stateMap.get === "function") return stateMap.get(nodeId);
    return Object.prototype.hasOwnProperty.call(stateMap, nodeId) ? stateMap[nodeId] : null;
  }

  function updateAllColors(stateMap, powered) {
    circuitPowered = powered !== false;
    for (const [key, el] of wireRegistry) {
      const parts = key.split("||");
      const vA = lookupState(stateMap, parts[0]);
      const vB = lookupState(stateMap, parts[1]);
      const value = (vA === 1 || vA === 0) ? vA : ((vB === 1 || vB === 0) ? vB : null);
      applyColor(el, value);
    }
  }

  // Returns the <g> DOM element for a wire, or null.
  function getWireElement(nodeIdA, nodeIdB) {
    return wireRegistry.get(wireKey(nodeIdA, nodeIdB)) || null;
  }

  // Array of all { from, to } pairs currently drawn.
  function getAllWires() {
    const list = [];
    for (const [, el] of wireRegistry) {
      list.push({ from: el.dataset.from, to: el.dataset.to });
    }
    return list;
  }

  // Remove every wire <g> we own from the SVG and empty the registry.
  function clearAll() {
    for (const [, el] of wireRegistry) {
      if (el.parentNode) el.parentNode.removeChild(el);
    }
    wireRegistry.clear();
    clearOccupancy();          // drop all occupancy records
    _wireIds.clear();          // reset identity colors after full clear
  }

  return {
    init: init,
    drawWire: drawWire,
    removeWire: removeWire,
    updateAllColors: updateAllColors,
    getWireElement: getWireElement,
    getAllWires: getAllWires,
    clearAll: clearAll,
    registerRoutingPoints: registerRoutingPoints,
    clearRoutingPoints: clearRoutingPoints,
  };
})();

// Export for headless tooling/tests; harmless in the browser.
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    Wires, buildWirePath, colorForValue, routeBetween, finalizeRoute,
    // test hooks
    recordOccupancy, releaseOccupancy, clearOccupancy, registerRoutingPoints, clearRoutingPoints,
    HITBOXES, TOP_TRACKS, BOTTOM_TRACKS, isLaneY,
  };
}
