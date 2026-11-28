# feature-displays progress

## Implementation

- Added `seg7` and `hexdigit` as additive `gates.json` component types with
  input-only geometry and SVG resources. No `ComponentLibrary`, TI-chip
  definitions, chip resources, or AI implementation was changed.
- Relaxed `GateDef.origin()` for output-less components with multiple inputs;
  the first input defines the component's fixed geometry origin.
- Added Swing-free `DisplayLogic` helpers for three-valued segment state,
  active-high/active-low decoding, and the complete 0-F hexadecimal glyph map.
  Unconnected Seg7 segments are dim; connected X segments are marked unknown.
  Unknown HexDigit data is rendered with the dim dash/X marker; dp remains
  independent.
- Added Active level (High/Low), On colour (red/green/yellow/blue), and the
  existing Name-label mechanism to both display families.
- The canvas paints displays with `DisplayRenderer`, derives their values
  from the normal pin-state diagnostics, shows a display-state tooltip, and
  leaves generic selection, rotation, copy/paste, and pin dots intact.
- `SchematicCompiler` creates one `OutputProbe` for every display input.
  Probes have no driver and displays do not add simulation logic.
- Expression export projects each display pin to a coincident named Output
  probe, then maps the exported signal IDs back to the original display ID.
  VHDL export emits each display pin as a top-level `out` port. Since the
  simulation testbench currently binds one output component per signal, a
  display-containing VHDL export deliberately omits the generated testbench.
- Combinational and feedback extraction treat display input pins as independent
  output sinks. Tidy rebuilds each sink as an Output component; feedback tidy
  verifies exact pin-to-net connectivity using a pin-level projection map.
- Displays are excluded from gate counts and from the "has tidyable gate"
  calculation. The Pins explorer group includes both display families.

## Tests added/changed

- Updated `GateLibraryTest` and `GalleryRenderTest` for the two additional
  catalogue entries; both SVGs are parsed with JSVG and painted in four facings.
- Added `DisplayLogicTest`: Seg7 high/low polarity, X versus unconnected,
  digit tooltip, full 0-F glyph table, weighted data pins, unknown nibble,
  independent dp, and colour helper functions. No pixel/image-based painting
  tests are used.
- Added `DisplayComponentTest`: input-only definition contract, .lbc
  save/load, unchanged legacy-file loading, clipboard copy/paste of names and
  attributes, rotation pin coordinates, and compiler read-only probes.
- Added `DisplayExporterTest`: expression and VHDL port names for both
  display families, named signal-to-component mapping, testbench capability,
  and Tidy's conversion of all display pins into named sinks.

## Human checks

- Connect a counter's Q0..Q3 to HexDigit D0..D3 and use **Simulate > Tick Once**
  to observe the hexadecimal value advance.
- Select Active level **Low** and confirm the display polarity changes as
  expected for common-anode wiring.
- Also inspect Seg7's decimal point, X/unconnected pin rendering, all four
  On colour options, tooltips, and rotation.
