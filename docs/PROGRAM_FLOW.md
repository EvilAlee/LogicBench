# LogicBench — High-Level Program Flow

The diagram follows the normal desktop lifecycle. The schematic is authoritative; simulation is rebuilt from the schematic rather than being the saved document itself.

```mermaid
flowchart TD
    A([Start]) --> B["Main.main()"]
    B --> C["Set up Swing / FlatLaf"]
    C --> D["Create Main window and CanvasPanel"]
    D --> E["GateLibrary loads and validates gates.json"]
    E --> F["Load SVG definitions and create Schematic"]
    F --> G["Connect UI listeners, menus, explorer, attribute table"]
    G --> H["User interacts with canvas or menus"]

    H --> I{"What action?"}
    I --> J["Place / move / rotate / wire / poke / edit attributes"]
    I --> K["Undo / redo"]
    I --> L["Open document"]
    I --> M["Save document"]

    J --> N["Schematic validates and commits mutation"]
    K --> O["Restore snapshot from SchematicHistory"]
    O --> N
    N --> P["Resolve GateDef variant and calculate pin positions"]
    P --> Q["Connectivity derives nets from pins + wire geometry"]
    Q --> R["SchematicCompiler builds a fresh engine Circuit"]
    R --> S["Create InputPin, Gate and OutputProbe objects"]
    S --> T["Connect engine pins using derived net groups"]
    T --> U["Simulator builds union-find nets and settles event queue"]
    U --> V["SimulationResult: net, wire, input and output values"]
    V --> W["Canvas repaints; attributes, status and history UI refresh"]
    W --> H

    L --> X["Read .lbc JSON with SchematicJson"]
    X --> Y{"Valid document?"}
    Y -- No --> Z["Show error; keep current schematic"]
    Z --> H
    Y -- Yes --> AA["Replace schematic; reset transient view and history"]
    AA --> N

    M --> AB["Serialize schematic to versioned JSON"]
    AB --> AC["Write .lbc file and update saved revision"]
    AC --> H
```

## Read the flow

- **Startup:** `Main` creates the window. `CanvasPanel` loads the component catalogue, SVG renderer, and empty editable schematic.
- **Editing:** mouse, keyboard, toolbar, tree, and attribute-table actions are translated into model operations on `Schematic`. Undo/redo restores model snapshots.
- **Geometry and connectivity:** resolved component variants determine pin geometry. `Connectivity` derives nets from the wire segments and pin locations. During geometry-changing edits, the model checks auto-follow paths against the pre-edit nets to avoid accidental shorts.
- **Simulation:** `SchematicCompiler` converts those derived nets to engine pin connections. `Simulator` propagates three-valued logic (`ZERO`, `ONE`, `X`) through scheduled gate evaluations. The UI renders the returned result.
- **Files:** opening validates a detached schematic before replacing the current one. Saving writes the editable schematic; it does not serialize derived nets or simulation caches.

**Important distinction:** `Schematic` is the editable design model; `Circuit` is a derived engine model for one simulation rebuild. Keeping those roles separate lets the editor handle placement, geometry, attributes, files, and history without putting Swing or persistence logic inside the simulator.
