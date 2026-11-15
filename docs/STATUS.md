# LogicBench — status (update in every PR)

## Done (merged to main)
- M1: headless engine (model/, sim/), union-find nets, event-driven ticks, tests
- M1 fix: gate outputs seed to X; tick limit scales with gate count; conflicts resolve to X
- M2: Swing window shell (FlatLaf), Logisim-style layout, pan/zoom canvas, ViewTransform
- M3: gates.json-driven SVG library, rotation geometry, GateRenderer, 10 × 4 gallery
- Assets: src/main/resources/gates.json + svg/*.svg (narrow Logisim sizes; do not edit geometry)

## In progress
- M4: placed-component Schematic, canvas add/select/move/rotate/delete interactions
  - Arrow facing shortcuts use the canvas `WHEN_FOCUSED` map; Escape/Delete/Backspace/Space/G remain in `WHEN_IN_FOCUSED_WINDOW`.
  - Branch: m4-placement   PR: #8

## Next
- M5 wiring → M6 live simulation → M7 persistence + undo/redo → M8 polish

## Decisions
- Engine is pin-graph based; wire geometry → pin connections happens in a layer above it (M5)
- M4 Schematic/PlacedComponent is separate from the M1 engine Circuit; M6 converts it
- Component origin = output pin (or the single input pin for "output"); rotation about origin, y down
- Pins and positions on a 10-unit grid; variable-input gates deferred to M8
- Arrow keys set facing directly: Right=EAST, Down=SOUTH, Left=WEST, Up=NORTH
- M7 undo/redo wraps the Schematic mutation methods
- Open: flip-flops built-in vs from gates; wire tool = drag segments (Logisim) vs A* routing

## Workflow
- One task, one branch from fresh main, one PR, base always main. Check the base before merging.
- ./gradlew test must be green. No screenshots, capture scripts, or throwaway workflows committed.
- User merges PRs; user wants to understand and write the engine core and routing himself.
