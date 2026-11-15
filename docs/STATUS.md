# LogicBench — status (update in every PR)

## Done (merged to main)
- M1: headless engine (model/, sim/), union-find nets, event-driven ticks, tests
- M1 fix: gate outputs seed to X; tick limit scales with gate count; conflicts resolve to X
- M2: Swing window shell (FlatLaf), Logisim-style layout, pan/zoom canvas, ViewTransform
- Assets: src/main/resources/gates.json + svg/*.svg (narrow Logisim sizes; do not edit geometry)

## In progress
- M3: gate rendering (loader, rotation geometry, GateRenderer, --gallery view)
  - Branch: (fill in)   PR: (fill in)

## Next
- M4 placement/select/move/rotate/delete → M5 wiring → M6 live sim → M7 save/load + undo → M8 polish

## Decisions
- Engine is pin-graph based; wire geometry → pin connections happens in a layer above it (M5)
- Component origin = output pin (or the single input pin for "output"); rotation about origin, y down
- Pins and positions on a 10-unit grid; variable-input gates deferred to M8
- Open: flip-flops built-in vs from gates; wire tool = drag segments (Logisim) vs A* routing

## Workflow
- One task, one branch from fresh main, one PR, base always main. Check the base before merging.
- ./gradlew test must be green. No screenshots, capture scripts, or throwaway workflows committed.
- User merges PRs; user wants to understand and write the engine core and routing himself.
