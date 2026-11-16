# LogicBench — Program Overview

**LogicBench** is a desktop logic-circuit editor and simulator. Users place logic components on a grid, connect their pins with orthogonal wires, edit component attributes, and observe the resulting logic values. The editable schematic is the source of truth; the simulation circuit is compiled from it whenever the schematic changes.

## Technology and startup

The application uses **Java 25**, **Swing** with FlatLaf for the desktop UI, **jSVG** to draw catalogue artwork, **Gson** for schematic files, and Gradle for build/run/test tasks. The entry point is `io.github.evilalee.gatelab.ui.Main`. It configures the look and feel, creates the main window, then connects the canvas, menus, component explorer, attribute table, status area, and document actions.

At startup, `CanvasPanel` loads the `GateLibrary`, creates a `Schematic`, and creates a `GateRenderer`. The library reads `src/main/resources/gates.json`, validates the component definitions, and indexes visual variants by family, size, and input count. SVG resources provide the component artwork; `GateGeometry` provides the pin offsets and rotated bounds.

## Main program layers

| Layer | Main responsibility |
|---|---|
| `ui/Main` and `ui/CanvasPanel` | Window, menus, tools, mouse/keyboard input, attribute editor, painting, and user feedback. |
| `io/GateLibrary` and `ui/GateRenderer` | Load catalogue definitions, resolve variants, and render SVG artwork. |
| `model/Schematic` | Store placed components, facing, non-default attributes, input states, and wire segments; validate and publish edits. |
| `model/Connectivity` | Derive electrical nets from pin coordinates and wire geometry. Nets are calculated, not stored in the document. |
| `sim/SchematicCompiler` and `sim/Simulator` | Translate the schematic into an engine circuit, propagate logic values, and return simulation results. |
| `io/SchematicJson` and `model/SchematicHistory` | Validate/read/write `.lbc` documents and provide snapshot-based undo/redo. |

## What happens when a user edits a circuit?

1. A user selects a component or tool, places a component, draws or moves a wire, changes Facing/Size/Number of Inputs, or changes an input's value. Add-mode attributes are remembered per component family for the current session.
2. The UI calls a method on `Schematic`; it does not maintain a second copy of the electrical design. The model validates the change and increments its modification counter when a mutation is committed.
3. Component pin coordinates are calculated from the resolved `GateDef` and its facing. `Connectivity.analyze` uses those coordinates plus the orthogonal wire segments to derive nets. A pin or wire endpoint on a segment joins it; a plain crossing without a joining point does not.
4. For component moves, rotations, and pin-moving attribute changes, the model tries safe orthogonal wire-follow routes. It checks that routes do not short previously separate nets. If a route cannot be made safely, generated links are discarded; if the destination pins themselves would create a short, the edit is rejected.
5. The schematic change listener asks `SchematicCompiler` to build a fresh engine `Circuit`. It creates engine inputs, gates (using the resolved variant's actual input count), and output probes, then connects their pins according to the derived nets.
6. `Simulator` builds its net groups using union-find and settles the circuit with an event queue. Changed outputs update their nets and schedule only affected gates for later ticks. The result is returned to the canvas for wire colours, output values, status, and repainting.

## Documents and history

A `.lbc` file is versioned JSON containing component IDs/types/positions/facing, non-default `size` and `inputs` overrides in optional `attrs`, input HIGH states, and geometric wires. Nets and computed simulation values are not saved. Opening first parses and validates a separate schematic; the active document is replaced only after a successful load. Older version-1 files without `attrs` use the default narrow/2-input settings.

Undo/redo stores schematic snapshots (up to 100 steps). A completed user gesture—such as moving a selection or changing an attribute—is one history step. Saving updates the document's saved modification revision; unsaved changes are reflected in the window title.

## Run and test

From the repository root with JDK 25 installed:

```bash
./gradlew run       # launch the desktop application
./gradlew test      # run headless model, connectivity, compiler, and UI tests
```

See [the program flowchart](PROGRAM_FLOW.md) for the same lifecycle as a diagram.
