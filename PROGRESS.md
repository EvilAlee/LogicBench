# feature-chip-power progress

## Implementation choice

Selected the internal X-valued source plus ordinary engine gates during
`SchematicCompiler` flattening. Each chip instance gets a shared
`powerGood = VCC AND NOT GND` network. Every public output is masked through
`(powerGood AND value) OR (NOT powerGood AND X)`. The one X-valued `InputPin`
is internal to the flattened engine graph and is never exposed in the component
explorer or schematic.

This is smaller and safer than adding a new simulator primitive: it reuses the
engine's existing three-valued `AND`, `NOT`, and `OR` semantics and ordinary
event scheduling. The simulator, tick/settle limit, oscillation handling, and
"did not settle" behavior remain unchanged. The generated gate ids are stable
hierarchical ids, so existing gate-state carry-over also applies to the output
mask gates.

Only chip outputs are masked. Internal gates continue to run normally while a
chip is unpowered; their sequential state is neither erased nor frozen. This is
an intentional simulation simplification. When valid power returns, the
internal state is visible at the outputs again.

## Settings and exports

`Simulate > Chips need power` is transient, defaults on at application start,
and is not stored in .lbc files. Turning it off compiles chip instances using
the previous ideal-logic boundary connections. Expression and structural VHDL
exports remain ideal-powered logical descriptions; VHDL retains explicit VCC
and GND entity/instance ports.

## Tests changed

- Updated `MainSimulationOptionsTest` for the renamed default-on checkbox,
  re-settling on toggle, and unchanged schematic history.
- Added `ChipPowerSimulationTest` covering powered 7400 behavior, missing and
  invalid supply states (including VCC=X), ideal mode, X pin-dot diagnostics,
  sequential state across supply loss/restoration, and a flattened powered
  7400 SR latch.
- Existing `Ti74xxChipTest`'s supply diagnostic fixture already places VCC/GND
  drivers at the corresponding DIP pins; its internal-logic truth-table test
  exercises the definition itself rather than a placed chip instance.
