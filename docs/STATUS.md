# LogicBench — status (update in every PR)

## Done (merged to main)
- M1: headless engine, union-find nets, event-driven ticks, three-valued gates and tests
- M2: Swing window shell (FlatLaf), Logisim-style layout, pan/zoom canvas
- M3: gates.json SVG library, rotation geometry, GateRenderer and gallery
- M4: placed-component Schematic, add/select/move/rotate/delete interactions
- M5: orthogonal wires, selection/movement/deletion, geometric connectivity, junctions and tests

## In progress
- M6: live schematic simulation, fresh Circuit rebuilds, Poke tool, value-colored wires and I/O digits
  - Branch: m6-simulation

## Next
- M7 persistence + undo/redo → M8 polish

## Decisions
- Schematic wire geometry is translated to engine pin links by SchematicCompiler.
- Every Schematic change builds a fresh Circuit and Simulator and settles it; mouse movement never recomputes.
- Previous settled gate-driver values seed the fresh simulator so cross-coupled latches hold state; normal engine startup still defaults to X.
- Input state is stored per input in Schematic, default LOW.
- ONE=(0,210,0), ZERO=(0,100,0), X=(0,0,255); selected wires use an orange translucent halo.
- Component origin, rotation, 10-unit grid and M5 wire independence remain unchanged.
- M7 undo/redo wraps Schematic mutation methods.

## Workflow
- One task, one branch from fresh main, one PR, base always main.
- `./gradlew test` must be green. No screenshots or throwaway workflows committed.
- User reviews and merges each milestone PR.
