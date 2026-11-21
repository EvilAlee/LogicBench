# Black-box components

This document describes the component-definition model and its desktop GUI,
user-library workflow, persistence, and compiler flattening.

## Definitions and ports

A `ComponentDefinition` is an immutable reusable circuit. It contains:

- a unique name matching `[A-Za-z][A-Za-z0-9_]*`;
- a description and category;
- an ordered list of named ports, each with direction `IN` or `OUT` and an
  `isClock` flag;
- an optional `primaryOutput`, which identifies the output selected when a
  definition is used as a direct expression value; and
- an internal `.lbc` circuit.

The public port list is checked against the internal circuit. An internal
`Input` component contributes an `IN` port, an internal `Clock` contributes
an `IN` port with `isClock=true`, and an internal `Output` contributes an
`OUT` port. The component's Name attribute supplies the port name. Names must
be non-empty, valid identifiers, and unique within the definition. Every
declared port must match exactly one named Input, Clock, or Output component.

A definition has a SHA-256 content hash. Instances store both the definition
name and hash so a document can retain the exact definition it was built
against. The hash includes the definition metadata, ordered port contract,
and internal schematic topology.

## Instances and pin geometry

A `ComponentInstance` is a placed black box in a `Schematic`. It stores the
definition name and hash, a stable instance id (normally `U1`, `U2`, ...), an
optional label, a grid-aligned origin, and a rotation.

Pin positions are derived only from the ordered definition ports:

- input pins are on the left edge, in input-port order;
- output pins are on the right edge, in output-port order;
- adjacent pins on either side are 20 world units apart;
- the unrotated box height is
  `max(inputCount, outputCount) * 20 + 20`; and
- a clock input retains its `isClock` marker in the pin geometry.

The canvas draws a rectangle sized from this geometry, centres the definition
name in the body, and places input labels on the left and output labels on the
right. Clock pins retain their clock marker, and an optional instance label is
shown beneath the definition name. Rendering uses the geometry above without
moving or changing any electrical terminal. Rotation transforms the body and
all pins together.

## Compiler flattening

`SchematicCompiler` recursively expands each instance before constructing
the engine circuit. Internal component ids are namespaced with the instance
path: for example, a gate `g1` inside `U1`, itself nested in `U2`, becomes
`U2/U1/g1`. Stable hierarchical ids preserve sequential state across a
recompile when unrelated components are added.

The definition's boundary Input, Clock, and Output components are not emitted
as engine sources or sinks. Instead, the compiler finds each boundary
component's internal net and unions it with the corresponding external
instance-pin net:

- an `IN` port unions the internal Input/Clock output net with the outer
  instance input-pin net; the outer circuit supplies its value;
- an `OUT` port unions the internal Output input net with the outer
  instance output-pin net; and
- a direct input-to-output connection is a valid pass-through and needs no
  synthetic gate.

An unconnected instance input remains undriven. It is not replaced with a
constant; the existing three-valued simulator therefore observes `X` where
that signal is consumed. Flattening creates no extra gates and introduces no
simulation ticks or delay. Clock ports are ordinary externally driven input
nets with clock metadata retained for top-level clock/source handling.

Nesting is limited to eight component-instance levels. Direct or indirect
definition cycles are rejected with the complete cycle path. Waveform probes
remain top-level only. Tidy and expression-based netlist analysis explicitly
refuse schematics containing component instances until those workflows gain
hierarchical support.

## Built-in and user library

`ComponentLibrary` creates built-ins at runtime; no built-in circuit files are
stored. Sequential definitions are expanded from `SequentialParts` and laid
out using the existing `SchematicLayout`. Arithmetic definitions are built
from gate netlists and use the same layout engine.

| Definition | Ordered inputs | Ordered outputs | Primary output |
|---|---|---|---|
| `DLATCH` | `D, EN` | `Q, QN` | `Q` |
| `DFF` | `D, CLK` | `Q, QN` | `Q` |
| `TFF` | `T, CLK` | `Q, QN` | `Q` |
| `JKFF` | `J, K, CLK` | `Q, QN` | `Q` |
| `HA` | `A, B` | `S, C` | none |
| `FA` | `A, B, CIN` | `S, COUT` | none |

User definitions are stored in the platform configuration directory under
`components/NAME.lbcomp.json`. The default directory is
`%APPDATA%/LogicBench/components` on Windows and
`$XDG_CONFIG_HOME/logicbench/components` (or `~/.config/logicbench/components`)
on Linux/macOS. A user definition cannot shadow a built-in name.

## Desktop GUI workflow

The Component Explorer has four top-level groups: **Gates**, **Pins**,
**Built-in**, and **My components**. The Built-in folder lists the definitions
generated by the component library; My components lists definitions stored in
the user's component directory. Selecting a component enters placement mode
and shows its preview on the canvas. Dragging a tree item onto the canvas
places it at the drop point. Each placement is one undoable operation.

To create a reusable definition from the current schematic, choose
**Circuit > Create component from circuit...**. The dialog lists each Input,
Output, and Clock component as a potential port. Named ports are included by
default. Unnamed ports are explicitly marked and excluded by default; enable a
row and enter a valid name to expose it. The Include column controls whether a
source component becomes a public port, and Move up / Move down controls the
public port order. At least one input (Input or Clock) and one output must be
included. The component name must be a non-empty identifier, unique in the
library, and must not use a built-in name. Port names must also be valid and
unique. Creating the definition saves it through the user library persistence
and refreshes My components immediately.

Double-click a placed component instance, or right-click it and choose
**View definition**, to open its read-only internal schematic. The viewer
supports pan and zoom. Nested instances can be opened in another read-only
viewer using the same double-click or context-menu action. The source document
is not changed by viewing a definition.

In the Edit tool, clicking inside a component box selects the instance; clicking
on one of its pins starts a wire instead. Marquee selection includes instances
whose box bounds intersect the rectangle, and Shift/Ctrl can add or toggle
instances in mixed selections with gates and wires. Dragging a selected instance
moves it with its selected wires, while connected stationary wires use the
same validated orthogonal follow-routing as gates. Arrow keys rotate selected
instances to the corresponding facing; attached wires follow the changed pin
positions. Delete removes an instance and the wire nets attached to its pins.
These edits are undoable and redoable. Selecting an instance does not alter its
simulation result.

The Attribute table currently does not expose component-instance name or
rotation fields; instance selection and geometry operations work without those
editor fields.

A standalone definition JSON document has this version-1 shape:

```json
{
  "version": 1,
  "name": "HA",
  "description": "Half adder",
  "category": "Arithmetic",
  "ports": [
    {"name": "A", "direction": "IN", "isClock": false},
    {"name": "B", "direction": "IN", "isClock": false},
    {"name": "S", "direction": "OUT", "isClock": false},
    {"name": "C", "direction": "OUT", "isClock": false}
  ],
  "hash": "<sha256>",
  "internalCircuit": {
    "version": 1,
    "components": [],
    "wires": []
  }
}
```

The `internalCircuit` member uses the ordinary versioned `.lbc` document
format. The example above abbreviates its component and wire arrays.

## Document embedding and compatibility

The existing `.lbc` root `components` array continues to contain ordinary
placed components; its meaning and shape are unchanged. Documents containing
black-box instances add two optional root-level arrays:

- `instances`: instance id, definition name and hash, optional label,
  position, and rotation;
- `componentDefinitions`: the complete definitions used by those instances,
  including each definition's internal circuit.

Both arrays are omitted when the document has no component instances. This
keeps the serialized output of instance-free documents unchanged. Older files
without either array continue to load as before.

On load, embedded definitions are resolved before library definitions. If an
embedded definition and a library definition share a name but have different
hashes, the embedded version wins and the loader returns a notice describing
the conflict. If a document references a definition that is neither embedded
nor present in the supplied library, loading fails with the missing definition
name. Definition hashes are checked before an instance is accepted. The desktop
Open action supplies the current component library to this loader and displays
the returned notice when an embedded definition overrides a different installed
library version. The existing save action embeds the complete definitions used
by instances; files without instances continue to omit both optional sections.
