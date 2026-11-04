# Headless engine notes

## Reference JavaScript data structures

The electrical graph in reference/js/netlist.js is stored as several Maps:

- nodes maps a node ID to its type, metadata, and current value (0, 1, or null).
- edges maps each node ID to a Set of adjacent node IDs. Connections are bidirectional.
- permanentEdges records simulator-owned connections separately so clearing user wires does not remove panel straps or IC pin-to-hole ties.
- switches and externalSources retain source values. icInstances maps each placed IC to its pin-node mapping. Sequential and counter state is kept in per-instance Maps.
- reference/js/ics.js describes IC packages as pin numbers, power pins, and arrays of gate descriptors. Each gate descriptor has input pin numbers, one output pin number, and an operation name.
- reference/js/board.js and board-extra.js define board node coordinates and metadata. Those are presentation/connection-point records, not gate state.

The legacy netlist discovers a net by breadth-first search over the adjacency Sets. The Java engine instead treats each model Pin as a graph vertex and uses union-find to build disjoint net groups from Circuit wires.

## Reference evaluation order

A call to resolveCircuit() follows this broad order:

1. Reset derived node values and restore source values for VCC, GND, switches, and simulator-controlled external sources. Values are propagated over each connected graph.
2. Detect VCC/GND shorts and determine whether each IC is powered by checking its VCC and GND pin nets.
3. Collect IC output pins and restore stored sequential outputs.
4. Evaluate powered combinational IC behavior in IC-library order. Standard gates, adders, and decoders are evaluated repeatedly for up to 20 passes, stopping when the node-value snapshot no longer changes.
5. Sample every flip-flop and counter from the same settled combinational snapshot, then commit sequential state together. Repeat the sequential solve for up to 50 passes so ripple stages can react to changed outputs.
6. Sample LED nodes and return a Map of node IDs to values.

The legacy gate evaluator supports AND, OR, NAND, NOR, XOR, and NOT. If any input is null, it returns null before evaluating the operation. Thus, for example, AND(0, null) is null in the reference implementation. The requested Java semantics intentionally use controlling values: AND with any ZERO is ZERO, and OR with any ONE is ONE. XNOR and BUFFER are new gate types in the Java engine.

## Propagation and delay

The JavaScript engine is frame-oriented rather than event-driven. It does not model a per-gate propagation delay: all combinational devices are revisited within the same resolveCircuit() call, with a 20-pass cap. Sequential devices use remembered clock levels to detect rising or falling edges; their state is sampled and committed in batches, with an outer 50-pass bound.

The Java engine uses discrete logical ticks. Initially all gates are scheduled. During a tick, scheduled gates evaluate from the current net snapshot; changed output drivers are applied together at the end of that tick. A changed net schedules gates attached to its input pins for the next tick. This gives each gate one logical tick of propagation delay without wall-clock timing. settle() stops when the event queue is empty or the tick limit is reached. On reaching the limit with work still queued, it sets the oscillating flag and returns; pending events remain available to a later settle() call.

The initial-state policy deliberately leaves ambiguity unknown. Whenever topology is built or rebuilt, each gate output driver is seeded to X and all gates are still scheduled for the first tick. InputPin drivers retain their externally assigned values. Undriven nets and nets whose only drivers are X remain X. A gate can establish a concrete output only after evaluating its current inputs.

The default settle budget scales with circuit size: it is the greater of 256 ticks or four ticks per gate plus 32 ticks. The explicit Simulator(Circuit, int) overload continues to use exactly the caller-provided cap. This gives long acyclic chains enough propagation ticks while retaining a bounded return for feedback oscillators.

## Driver conflicts

The legacy getNetValue() returns null if it sees different concrete values on one net. Its output-driving path also detects disagreement between gate outputs and fixed sources, and clears non-rail nodes to null. VCC/GND shorts are likewise made null. There is no separate ERROR value in the legacy code.

The Java engine represents all of those unknown/contention outcomes as X. Multiple drivers that agree resolve to their shared value; an X driver or opposing concrete drivers make the net X. No ERROR enum member is introduced.

## DOM-coupled code dropped from the headless port

- board.js supplies fixed SVG-board coordinates, DOM annotations, click targets, and node hit-testing.
- board-extra.js registers the pulse terminal in board metadata and adds SVG elements when a document exists.
- wires.js combines a grid A* router, route occupancy and crossing rules, wire color selection, SVG path creation, hit paths, and a DOM-backed wire registry. Routing geometry and rendering are not part of the electrical engine.
- main.js owns browser initialization, document/window event handlers, SVG overlays, status/LED rendering, panel switches, clock animation frames, pulse-button events, localStorage settings, and save/load UI.
- Board pin naming, power-strip straps, IC package artwork, visual power controls, and floating-TTL input assumptions are also outside this milestone.

The Java model and sim packages contain no AWT, Swing, SVG, browser, or persistence dependencies. The port covers the requested generic gates, externally settable input components, output probes, wires, net resolution, and headless propagation only. Legacy flip-flops, counters, adders, decoders, board rails, and sequential timing remain out of scope.
