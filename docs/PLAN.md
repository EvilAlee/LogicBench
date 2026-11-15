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

### Circuit file (*.gatelab.json)
\`version\`, \`components[]\` { \`id\`, \`type\`, \`x\`, \`y\`, \`facing\`, \`attrs\` },
\`wires[]\` { \`x1\`, \`y1\`, \`x2\`, \`y2\` }.
Nets are NOT stored; they are rebuilt on load. Persistence is deferred to M7.

## 6. Simulation (ENGINE_NOTES.md is authoritative)
- Values: ZERO, ONE, X.
- Nets: pins joined by wires form one net (union-find). Rebuild all nets when
  wire topology changes.
- Propagation: one logical tick of delay per gate.
- \`settle()\` has a tick cap; on hitting it, set the oscillating flag and return.
- Conflicting drivers on one net resolve to X.

## 7. Wiring
- Wires are horizontal or vertical segments between grid points.
- Endpoints connect when they coincide with a pin or another endpoint.
- An endpoint landing on another wire's interior splits that wire (junction).
- Dragging from a pin starts a wire; the path is routed orthogonally using the
  ported routing algorithm. Deleting a wire triggers a net rebuild.

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
| M7 | Persistence | Save/load round-trips a circuit; undo/redo works across all edits |
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

## 10b. Open questions
- Final gate sizes (narrow vs wide)
- Flip-flops: built-in components or built from gates only?
