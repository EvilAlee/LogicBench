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

Each layer is assigned a separate vertical band. Within a band, component
slots use their actual rotated height from GateGeometry.bounds; dummy slots
use 20 world units. The default empty vertical gap between slots is 40 units
and the gap between layer bands is 60 units. This band separation prevents
horizontal branch rows belonging to neighboring columns from sharing a pin
row. Components use EAST facing and actual GateDef dimensions and pin
offsets from gates.json.

## Orthogonal routing

For each adjacent-column channel, each distinct signal receives one vertical
trunk. Trunk lanes are placed 20 units after the source column's right edge,
20 units apart, with 20 units left before the next column. The channel grows
with the number of distinct routed signals. Sources connect horizontally to
their trunk; the trunk spans every sink row; horizontal branches connect to
each sink pin. Dummy nodes reserve their layer's own empty row so long-edge
segments do not pass through gate bodies.

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
