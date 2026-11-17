# LogicBench — Program Overview

**LogicBench** is a Java desktop application for drawing and simulating logic circuits. Users place components on a grid, connect pins with orthogonal wires, edit component attributes, and observe logic values. The editable schematic is the source of truth; the simulation circuit is rebuilt from it after changes.

## Technology and startup

The project uses **Java 25**, **Swing/FlatLaf** for the interface, **jSVG** for component artwork, **Gson** for files, and Gradle. The entry point is `io.github.evilalee.gatelab.ui.Main`. It creates the window and connects the canvas, menus, component explorer, attribute table, status area, and file actions.

On startup, `CanvasPanel` loads the `GateLibrary`, creates the editable `Schematic`, and prepares the `GateRenderer`. The library reads `src/main/resources/gates.json`, validates definitions, and indexes variants by family, size, and input count. SVG files supply artwork; `GateGeometry` calculates rotated pin positions and bounds.

## Main layers

| Code | Responsibility |
|---|---|
| `ui/Main`, `ui/CanvasPanel` | Window, tools, user input, table editing, drawing, and feedback. |
| `io/GateLibrary`, `ui/GateRenderer` | Component catalogue, variant selection, and SVG rendering. |
| `model/Schematic` | Components, attributes, input states, wires, and validated edits. |
| `model/Connectivity` | Derives electrical nets from pin coordinates and wire geometry. |
| `sim/SchematicCompiler`, `sim/Simulator` | Builds the engine circuit and propagates logic values. |
| `io/SchematicJson`, `model/SchematicHistory` | Validated `.lbc` files and snapshot-based undo/redo. |

## How a circuit edit works

1. A user places, moves, or rotates a component; draws or moves a wire; changes an attribute; or pokes an input. Add-mode settings are remembered per component family for the session.
2. The UI calls `Schematic`. The model validates the operation, updates its data, and notifies listeners. Completed gestures are grouped into one undo step.
3. The model resolves each component's `GateDef` variant and calculates its pins. `Connectivity.analyze` derives nets from those pins and the wire segments. Pins or endpoints on a wire join it; a plain crossing does not.
4. For moves, rotations, and pin-moving attribute changes, safe orthogonal wire-follow routes are tested against the previous net groups. Unsafe generated links are discarded; an edit whose destination pins would create a short is rejected.
5. `SchematicCompiler` builds a fresh engine `Circuit`, creates inputs, gates (with the selected input count), and output probes, then connects their pins using the derived nets.
6. `Simulator` groups engine pins into nets with union-find and settles an event queue. Changed outputs schedule affected gates. The resulting wire and probe values are sent back to the canvas for rendering.

## Files, history, and commands

A `.lbc` file is versioned JSON containing component IDs/types/positions/facing, optional non-default `attrs` (size and input count), input HIGH states, and wire geometry. Derived nets and simulation caches are not saved. Opening validates a detached schematic before replacing the active one; older v1 files without `attrs` retain the narrow/2-input defaults. Undo/redo stores up to 100 schematic snapshots.

From the repository root with JDK 25:

```bash
./gradlew run       # launch LogicBench
./gradlew test      # run the headless and UI-model tests
```

See [the flowchart](PROGRAM_FLOW.md) for the full high-level execution path.
