# Expression-to-schematic layout

The UI expression workflow uses io.github.evilalee.gatelab.synth.ExpressionCompiler
and then converts its validated Netlist into ordinary PlacedComponent and
WireSegment values. The layout package
io.github.evilalee.gatelab.synth.layout has no Swing or AWT dependency.

## Layer assignment and ordering

Primary input pins and constant-source pins occupy column 0. A gate's depth is
one plus the maximum depth of its gate/input predecessors (primary inputs have
depth 0); gate components occupy that depth column. Output probes occupy one
column after the deepest gate. Each edge that skips columns receives one
dummy routing node in every skipped column. A dummy reserves a vertical slot
but is not serialized as a component.

The initial order is stable by netlist order. Four down/up barycenter sweep
pairs then reorder each layer using its predecessor or successor positions.
Equal barycenters are broken by a stable node key, so the same netlist and
catalogue always produce the same ordering.

Vertical coordinates are compacted independently for each column. Column 0
keeps primary inputs in expression first-appearance order at a fixed 60-unit
pitch (constant-source switches follow the declared inputs). For every later
column, a forward placement pass targets the average of the actual upstream
source-pin rows. Nodes are processed in the existing column order; when two
component bounds would collide, the later node is pushed down until the
40-unit minimum body clearance is restored, snapped to the 10-unit grid. The
column is then shifted uniformly so its mean is as close as possible to the
mean desired row without changing any clearance. Dummies use the same rule
with a 20-unit virtual slot. Gate/output rows are based on GateGeometry pin
offsets rather than assuming that every pin is at the component origin.

Coordinate placement runs three alternating sweeps: left-to-right from
predecessor source pins, right-to-left from successor destination pins, then
left-to-right again. The candidate with the shortest total vertical trunk
length is retained; equal lengths keep the earlier candidate deterministically.
Output probes are resolved in their declaration order and stay at their source
pin row when no overlap prevents it. After routing, all components and wires
are translated together so the drawing's bounding-box top-left is at (0, 0).
Components use EAST facing and actual GateDef dimensions and pin offsets from
gates.json.

## Orthogonal routing

For each adjacent-column channel, each distinct signal receives one vertical
trunk. Trunk lanes are placed 20 units after the source column's right edge
and 20 units apart, with a 20-unit margin after the final trunk. Thus a channel
with one trunk uses 40 units and a channel with two trunks uses 60 units,
rather than reserving an unused extra pitch. Sources connect horizontally to
their trunk; the trunk spans every sink row; horizontal branches connect to
each sink pin. Dummy nodes reserve their layer's own row so long-edge segments
do not pass through gate bodies.

All generated coordinates are multiples of the 10-unit schematic grid.
Connectivity.analyze is run after placement. The self-check requires each
expected primary-input, gate-output, gate-input, and output-probe pin to be
present, all pins for one signal to share exactly one geometric net, and no
geometric net to contain pins from different netlist signals. Thus T-junctions,
endpoint touches, pin-on-wire contacts, and collinear overlaps are checked
using the same connectivity rules as ordinary schematics. Plain crossings
remain independent. A failed connectivity/overlap check retries once with
doubled row, band, and channel spacing; the final exception reports the
involved nets.

## Constant-only outputs

The catalogue currently has input and output components, but no permanent
logic-high/logic-low source components. A constant output therefore uses a
normal input component with label 0 or 1; the zero source starts LOW and the
one source starts HIGH. These are ordinary editable switches if the user
later chooses to change the generated circuit. Constant source switches are
not included in the expression's declared-variable list.

## Verification and persistence

The live dialog preview uses TruthTableVerifier from the synthesis core and
displays at most 10 input columns (up to 1024 rows). The Generate action then
uses SchematicExpressionVerifier: it applies every declared input assignment
to the generated input components, calls the normal
SchematicCompiler.compileAndSettle path for the placed geometry, and compares
the output probes against the independent AST evaluator. The UI only installs
a circuit after every row matches and none oscillates. A generated circuit is
one normal schematic-history step.

Input and output components may carry an optional free-text label. Empty
labels are omitted from version-1 JSON. Older version-1 files without label
load with an empty label. Labels do not affect pin IDs or electrical
connectivity.
