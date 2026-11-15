# LogicBench — Plan

## 1. Scope
A Logisim-Evolution-style logic gate simulator. The user can place gates,
wire them orthogonally, poke inputs, and see values update live. Nothing more.

Non-goals for v1 (do not build, do not scaffold for): subcircuits, buses or
multi-bit values, timing diagrams, memory/RAM/ROM, splitters, tunnels, HDL,
FPGA export, themes, plugins, localization, animations.

## 2. Fixed decisions
| Topic | Decision |
|---|---|
| Language | Java 25 (Gradle toolchain) |
| Build | Gradle Kotlin DSL + wrapper |
| GUI | Swing + FlatLaf (light theme, no custom rounding) |
| Canvas | Custom-painted \`JPanel\` using Java2D |
| SVG | JSVG renders gate artwork; JFreeSVG for export (late milestone) |
| Persistence | JSON via Gson |
| Tests | JUnit 5; model and sim must be testable headless |
| Look | Flat, plain, utilitarian. Match docs/reference/ screenshots |

New dependencies require a note in the PR description.

## 3. Architecture
Root package: \`io.github.evilalee.LogicBench\`

- \`model\`: M1 engine Circuit/Component/Pin/Wire/Value and the separate
  editor Schematic/PlacedComponent/Facing data
- \`sim\`: Net building and propagation engine; operates on the M1 Circuit only
- \`ui\`: Swing frame, canvas, tools, and panels
- \`io\`: component catalogue loading and future schematic persistence

Dependency direction: \`ui -> io -> model\` and \`ui -> sim -> model\`.
\`model\`, \`sim\`, and \`io\` must not import \`java.awt\` or \`javax.swing\`.
The editor Schematic is not the engine Circuit. It stores placed type ids and
world positions; M6 will explicitly convert a Schematic into an engine Circuit.
\`reference/js/\` is read-only legacy code. Port the logic, never copy it line by line.

## 4. Geometry conventions
- Y axis points down. Model coordinates are integers; the grid is 10 units.
- Everything (component origins, pins, wire endpoints) sits on the grid.
- Zoom and pan live only in the canvas, never in the model.
- Component origin = its output pin position. For an output probe, which has
  no output pin, the single input pin is its origin.
- Default facing is EAST: inputs on the left, output on the right.
  Other facings (NORTH, WEST, SOUTH) rotate the component about its origin.
- Variable-input gates place inputs on the left edge at 10-unit spacing,
  vertically centered on the origin row; variable-input rendering is deferred
  to M8.
- Pin positions and component sizes come from \`gates.json\`; do not parse them
  from SVG artwork.
- A \`PlacedComponent\` stores the world coordinate of its origin. Both origin
  coordinates are multiples of 10. M3's \`GateGeometry.bounds\` supplies the
  rotated axis-aligned bounds used for hit testing and marquee selection.

## 5. Data formats
### Component catalogue (\`src/main/resources/gates.json\`)

The root object contains a non-empty \`gates\` array. Each entry has:

| Field | Meaning |
|---|---|
| \`id\` | Unique component identifier (for example, \`and\` or \`output\`) |
| \`svg\` | Classpath resource path under \`src/main/resources\`, such as \`svg/and.svg\` |
| \`width\`, \`height\` | Positive component dimensions in world units |
| \`inputs\` | Fixed array of \`{ "x": integer, "y": integer }\` input-pin coordinates |
| \`output\` | \`{ "x": integer, "y": integer }\` output-pin coordinate, or \`null\` for an output probe |

Dimensions, pin coordinates, and the derived origin are multiples of 10.
Pin coordinates lie on or inside the inclusive rectangle from \`(0,0)\` to
\`(width,height)\`. M3 loads the fixed-input array form only; variable input
definitions and variable-input SVG layout are deferred to M8. Every referenced
SVG must exist as a classpath resource and parse successfully with JSVG.
\`GateLibrary\` validates the catalogue once at startup and exposes immutable
definitions in file order.

### Schematic file (*.lbc; legacy *.logicbench.json)
Version 1 JSON (`.lbc`; legacy `.logicbench.json` files remain readable) contains `version`, `components[]` with `id`, `type`, `x`,
`y`, `facing` and input-only `high`, plus `wires[]` with `x1`, `y1`,
`x2`, `y2`. Nets and simulation results are not stored; they are rebuilt
from the components and wire geometry after loading.

## 6. Simulation (ENGINE_NOTES.md is authoritative)
- Values: ZERO, ONE, X.
- Nets: pins joined by wires form one net (union-find). Rebuild all nets when
  wire topology changes.
- Propagation: one logical tick of delay per gate.
- \`settle()\` has a tick cap; on hitting it, set the oscillating flag and return.
- Conflicting drivers on one net resolve to X.

## 7. Wiring
- Wires are normalized horizontal or vertical segments between grid points.
- Connectivity is derived from segment geometry and component-pin positions;
  nets are not stored in the schematic.
- Segment endpoints and component pins are graph nodes. A node on a segment
  interior joins that segment, including T-junctions and pins placed on wires.
- Two segments that cross without a pin or endpoint at the crossing remain
  separate. Collinear overlapping segments join.
- A junction dot is shown where the segment-degree contribution is at least
  three (an endpoint contributes one, an interior point contributes two).
- Dragging from a pin or wire point draws an L path: the first cursor axis
  chooses the first segment direction, then the other axis reaches the target.
  No A* router is used in M5.
- Wires remain geometric and orthogonal. A committed component move or facing
  change snapshots the pre-gesture net of every pin. A moved pin attached to
  stationary geometry tries two L routes (larger axis first, then the other
  axis) followed by Z routes in both orientations. Each orientation tries
  eight middle-coordinate offsets in this order: 0, +10, -10, +20, -20, +30,
  -30, +40; the midpoint is rounded to the nearest 10-unit grid coordinate
  (half-grid ties round toward the positive coordinate). Each candidate is
  accepted only when Connectivity proves that no resulting net combines pins
  from different pre-gesture nets. After all links are tried, every pin pair
  that was connected through stationary (non-selected) wires before the edit
  must still be connected. Pins whose only original path uses a selected wire
  are intentionally excluded from this preservation check because moving that
  wire is itself an explicit user edit; the full pre-gesture net partition is
  still used to reject any new short. If any route fails or required
  connectivity is lost, all generated links for that gesture are discarded
  and the component move/facing change is committed with the original
  stationary wires left behind; the UI shows "Some wires could not follow".
  If the moved pin positions themselves would merge different before nets
  even with no generated links, reject the movement itself, since committing
  that M4 fallback would still leave a short. No route state is retained
  between gestures. Wires selected as part of the move still move with the
  selection and do not generate automatic links.

## 8. UI spec (Logisim Evolution layout)
- Menu bar: File, Edit, Project, Simulate, Window, Help (only implemented items enabled).
- Toolbar row of tool icons: Edit, Poke, then the common gates.
- Left column: component tree (top), attribute table (bottom).
- Right: canvas with grid dots. No status bar.
- Tools: Edit (select, move, drag from pin to wire), Poke (toggle inputs),
  Add (pick a component in the tree, click to place).
- Arrow keys assign the preview/selection facing directly: Right=EAST,
  Down=SOUTH, Left=WEST, Up=NORTH. Delete and Backspace remove the selection.
- Wire colors: ONE bright green, ZERO dark green, X blue, error red. Inputs
  are square pins, outputs are circular LEDs.

## 9. Milestones and acceptance criteria
| # | Milestone | Done when |
|---|---|---|
| M1 | Scaffold + headless engine | \`./gradlew test\` green; truth tables, SR latch, inverter ring pass |
| M2 | Window skeleton | Logisim-style layout opens; canvas shows grid; pan and zoom work |
| M3 | Gate rendering | Every gate in gates.json renders crisply at any zoom and facing |
| M4 | Placement | Place, select, move, rotate, delete gates; positions snap to grid |
| M5 | Wiring | Draw orthogonal wires between pins; junctions work; nets rebuild correctly |
| M6 | Live simulation | Poke toggles inputs; wires and outputs recolor immediately |
| M7a | Save/Open | Versioned .logicbench.json round-trips components, wires and input values in `.lbc` files |\n| M7b | Undo/redo | Undo/redo works across all schematic edits |
| M8 | Polish | Input-count attribute, variable-input rendering, SVG export, then flip-flops |

## 10. Decisions log
- Conflicting drivers on one net resolve to X (M1).
- Engine is pin-graph based. Wire geometry (segments, junctions, coincident
  endpoints) is translated into pin connections by a layer above the engine (M5).
- Gate output drivers seed to X whenever the topology is built or rebuilt;
  undriven nets remain X, while all gates are scheduled on the first tick.
- The default settle budget is \`max(256, 4 * gateCount + 32)\` ticks. The
  explicit tick-limit constructor overload retains its exact caller-supplied cap.
- A cross-coupled NOR latch with S=R=ZERO is a stable unknown state (Q=Qbar=X),
  not an oscillation. Setting S or R then drives the corresponding set/reset
  state; returning both controls low holds the established state.
- Gate dimensions stay exactly as specified by \`gates.json\`: M3 does not
  normalize or edit artwork geometry. Component origin is the output pin;
  for the \`output\` probe, the sole input pin is the origin. Variable-input
  rendering is deferred to M8.
- The placed-component Schematic is an editor layer separate from the M1 engine
  Circuit. M6 will convert it into an engine Circuit; M4 does not mutate the
  engine model or simulation.
- Arrow keys assign absolute facings directly (Right=EAST, Down=SOUTH,
  Left=WEST, Up=NORTH), rather than cycling relative to the current facing.
- M7 undo/redo will wrap mutations at the Schematic API boundary; UI gestures
  call Schematic methods instead of editing the component collection directly.
- M5 wires are geometric orthogonal `WireSegment` values stored by the
  Schematic; connectivity is derived from their geometry and rotated component
  pin positions.
- A crossing without a pin or segment endpoint is not a connection; T-junctions,
  pins on segment interiors, and collinear overlaps join their nets.
- Component-follow links are validated per gesture against the complete
  pre-gesture pin-net partition. Candidate order is larger-axis-first L, the
  other L, then Z paths in both orientations (eight offsets in order 0, +10,
  -10, +20, -20, +30, -30, +40, with the midpoint rounded to the grid and
  half-grid ties toward the positive coordinate). A candidate is rejected if
  any post-gesture net contains pins from different pre-gesture nets. After
  routing, every pair connected through stationary (non-selected) wires before
  the gesture must remain connected; connections relying only on selected
  wires are intentionally movable. If a pin has no safe route or required
  connectivity is lost, generated links are discarded and the component edit
  is committed with stationary wires unchanged; the UI reports
  "Some wires could not follow". If the destination pin positions themselves
  would create a short without any generated links, the entire movement is
  rejected because an M4 no-link move would still be unsafe. This guarantees
  auto-link generation cannot introduce a new short. Candidate routing uses
  only current geometry and stores no state between gestures.

## 10b. Open questions
- Final gate sizes (narrow vs wide)
- Flip-flops: built-in components or built from gates only?

- M6 rebuilds a fresh engine Circuit and settles it after each schematic mutation or input change. The previous settled gate-output snapshot seeds the fresh simulator to preserve feedback-latch hold state; ordinary engine startup still defaults gate outputs to X.
- Input switch state is retained per component in Schematic (default LOW), survives rebuilds, and Simulate > Reset clears every input.
- Live wire and pin indicators use ONE=(0,210,0), ZERO=(0,100,0), X=(0,0,255). X is blue; selected wires use a translucent orange halo.

- M7a stores only component placement/facing, input HIGH flags and geometric wire segments in version 1 `.logicbench.json`; netlists and simulation snapshots are rebuilt after load.
- The Schematic modification counter advances once for each successful document mutation and is used by the UI to track the last saved revision.
