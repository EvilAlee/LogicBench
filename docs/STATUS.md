# LogicBench — status (update in every PR)

## Done (merged to main)
- M1: headless engine, union-find nets, event-driven ticks, three-valued gates and tests
- M2: Swing window shell (FlatLaf), Logisim-style layout, pan/zoom canvas
- M3: gates.json SVG library, rotation geometry, GateRenderer and gallery
- M4: placed-component Schematic, add/select/move/rotate/delete interactions
- M5: orthogonal wires, selection/movement/deletion, geometric connectivity, junctions and tests
- M6: live schematic simulation, Poke tool, value-colored wires, I/O digits and oscillation reporting
- M7a: versioned .lbc Save/Open, input state persistence, dirty-document prompts
- M7b: snapshot-based undo/redo, gesture grouping, Edit menu shortcuts and headless history tests
- M8b-v2: validated move/rotation wire-follow links with no-short fallback and safe no-link behavior

## In progress
- M9a: per-component Facing/Size/Input-count attributes, variant resolution, save/load, undo and attribute table
  - Branch: m9a-attributes
  - PR pending review

## Next
- M8 polish

## Decisions
- M9a stores only non-default `size` and `inputs` overrides in each component's `attrs` map; Facing remains top-level. Variant choices are derived from the matching family entries in `gates.json`; unavailable combinations are rejected.
- M9a tool-attribute values are remembered per component family for the current session and copied into subsequently placed components.
- Save files contain version, components (including input-only HIGH state), and geometric wires only.
- Nets and simulation results are not serialized; loading rebuilds the simulation from the loaded schematic.
- Invalid files are parsed into a detached schematic and never replace the active document.
- The Schematic modification counter advances once per successful mutation; title dirty state compares it to the saved revision.
- M6 rebuilds a fresh Circuit and Simulator on changes; prior gate drivers seed fresh feedback latch state.
- Input state is stored per input in Schematic; default LOW.
- ONE=(0,210,0), ZERO=(0,100,0), X=(0,0,255); selected wires use an orange translucent halo.
- Component origin, rotation, 10-unit grid, and geometric wire representation remain unchanged.
- M8b-v2 validates generated move/rotation links against the complete pre-gesture pin-net partition; it tries L then bounded Z candidates and falls back to a move without generated links if routing or stationary-net connectivity validation fails. Selected wires move intentionally and are excluded from the connection-preservation comparison, but not from the no-new-short check. If moving pin destinations alone would already merge separate before nets, the movement itself is rejected because the no-link fallback would still short.
- M7b history snapshots contain only placed components and geometric wire segments; input poke values are not history state.
- Undo/redo restoration preserves poke values for input ids still present; an input restored after deletion returns LOW.
- New/Open clear history; Save leaves it intact.

## Workflow
- One task, one branch from fresh main, one PR with base main.
- `./gradlew test` must be green. Do not commit screenshots or throwaway workflows.
- User reviews and merges each milestone PR.
