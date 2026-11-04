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
| Canvas | Custom-painted `JPanel` using Java2D |
| SVG | JSVG renders gate artwork; JFreeSVG for export (late milestone) |
| Persistence | JSON via Gson |
| Tests | JUnit 5; model and sim must be testable headless |
| Look | Flat, plain, utilitarian. Match docs/reference/ screenshots |

New dependencies require a note in the PR description.

## 3. Architecture
Root package: `io.github.evilalee.LogicBench`

- `model`: Circuit, Component, Pin, Wire, Value, Facing (pure data)
- `sim`: Net building and propagation engine
- `ui`: Swing frame, canvas, tools, panels
- `io`: JSON save/load, SVG export

Dependency direction: `ui -> sim -> model`, `io -> model`.
`model` and `sim` must never import `java.awt` or `javax.swing`.
`reference/js/` is read-only legacy code. Port the logic, never copy it line by line.

## 4. Geometry conventions
- Y axis points down. Model coordinates are integers; the grid is 10 units.
- Everything (component origins, pins, wire endpoints) sits on the grid.
- Zoom and pan live only in the canvas, never in the model.
- Component origin = its output pin position.
- Default facing is EAST: inputs on the left, output on the right.
  Other facings (NORTH, WEST, SOUTH) rotate the component about its origin.
- Variable-input gates place inputs on the left edge at 10-unit spacing,
  vertically centered on the origin row.
- Pin positions and sizes come from `gates.json`; do not parse them from SVG.

## 5. Data formats
### gates.json (src/main/resources/gates.json)
One entry per component type:
`id`, `svg` (path in resources), `width`, `height`, `output` {x,y},
`inputs` (fixed list of {x,y}, or `"variable"` with `minInputs`/`maxInputs`).

### Circuit file (*.gatelab.json)
`version`, `components[]` {`id`, `type`, `x`, `y`, `facing`, `attrs`},
`wires[]` {`x1`,`y1`,`x2`,`y2`}.
Nets are NOT stored; they are rebuilt on load.

## 6. Simulation (ENGINE_NOTES.md becomes authoritative once written)
- Values: ZERO, ONE, X.
- Nets: pins joined by wires form one net (union-find). Rebuild all nets when
  wire topology changes.
- Propagation: event-driven, one tick of delay per gate.
- `settle()` has a tick cap; on hitting it, set the oscillating flag and return.
- Conflicting drivers on one net: follow the reference JS if it has an
  equivalent, otherwise X. Record the choice in the PR.

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
- Keys: arrows rotate the preview or selection, Delete removes, Ctrl+Z / Ctrl+Y undo and redo.
- Wire colors (tune to screenshots): ONE bright green, ZERO dark green,
  X blue, error red. Inputs are square pins, outputs are circular LEDs.

## 9. Milestones and acceptance criteria
| # | Milestone | Done when |
|---|---|---|
| M1 | Scaffold + headless engine | `./gradlew test` green; truth tables, SR latch, inverter ring pass |
| M2 | Window skeleton | Logisim-style layout opens; canvas shows grid; pan and zoom work |
| M3 | Gate rendering | Every gate in gates.json renders crisply at any zoom and facing |
| M4 | Placement | Place, select, move, rotate, delete gates; positions snap to grid |
| M5 | Wiring | Draw orthogonal wires between pins; junctions work; nets rebuild correctly |
| M6 | Live simulation | Poke toggles inputs; wires and outputs recolor immediately |
| M7 | Persistence | Save/load round-trips a circuit; undo/redo works across all edits |
| M8 | Polish | Input-count attribute, SVG export, then flip-flops |

## 10. Decisions log
- Conflicting drivers on one net resolve to X (M1).
- Engine is pin-graph based. Wire geometry (segments, junctions, coincident
  endpoints) is translated into pin connections by a layer above the engine (M5).

## 10b. Open questions
- Final gate sizes (narrow vs wide)
- Flip-flops: built-in components or built from gates only?
