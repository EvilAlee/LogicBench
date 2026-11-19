# LogicBench — status (update in every PR)

## Done (merged to main)
- M1: headless engine, union-find nets, event-driven ticks, three-valued gates and tests
- M2: Swing window shell (FlatLaf), Logisim-style layout, pan/zoom canvas
- M3: gates.json SVG library, rotation geometry, GateRenderer and gallery
- M4: placed-component Schematic, add/select/move/rotate/delete interactions
- M5: orthogonal wires, selection/movement/deletion, geometric connectivity, junctions and tests
- M6: live schematic simulation, Poke tool, value-colored wires, I/O digits and oscillation reporting
- M7a: versioned .lbc Save/Open, input state persistence, dirty-document prompts
- M7b: snapshot-based undo/redo, gesture grouping, Edit menu shortcuts and headless history tests
- M8b-v2: validated move/rotation wire-follow links with no-short fallback and safe no-link behavior
- M9a: per-component Facing/Size/Input-count attributes, variant resolution, save/load, undo and attribute table
- PR #19: Logisim-compatible handling of unconnected multi-input gate pins with a strict mode
- PR #20: headless Boolean expression parser, validated netlists, shared AST synthesis and engine-verified truth tables
- PR #21: editable schematics generated from Boolean expressions, with optional input/output pin labels
- PR #22: compact expression-generated schematic layout with per-column pin-driven placement and production connectivity/overlap checks
- PR #24: Circuit > Tidy circuit — headless extraction, verified relayout, one-step undo, preserved I/O labels and switch values
- PR #25: named expression signals — `:=` internal signals with no output pin, later references inlined, and reused-output hint
- PR #26: AI circuit generation — Gemini provider, schema-validated expressions, independent engine truth-table verification, offline demos, persistent cache and AI dialog
- PR #29: visible gate names and editable per-gate labels, with app preferences and version-1 file persistence

## In progress
- feature-sequential-clock: add an app-controlled Clock source, headless tick/reset controller, version-1 clock-level persistence, and UI tick controls.

## Next
1. M9a attribute table with editable labels
2. M9b gate variants
3. Flip-flops, 7-segment display
4. M10 analysis (truth table, expression, batch mode)
5. Polish pass, distZip, demo script

## Known limitations
- Feedback circuits (including latches) remain at X until an input or Clock source establishes a state.
- Gates are 2-input only until the M9a/M9b work is complete.

## Decisions
- M9a stores only non-default size and inputs overrides in each component's attrs map; Facing remains top-level. Variant choices are derived from the matching family entries in gates.json; unavailable combinations are rejected.
- M9a tool-attribute values are remembered per component family for the current session and copied into subsequently placed components.
- Save files contain version, components (including input HIGH state), optional non-empty IO labels, and geometric wires only.
- Nets and simulation results are not serialized; loading rebuilds the simulation from the components and wires.
- Invalid files are parsed into a detached schematic and never replace the active document.
- The Schematic modification counter advances once per successful mutation; title dirty state compares it to the saved revision.
- M6 rebuilds a fresh engine Circuit and settles it on schematic changes or input changes; prior gate drivers seed fresh feedback latch state.
- Input state is stored per input in Schematic; default LOW.
- ONE=(0,210,0), ZERO=(0,100,0), X=(0,0,255); selected wires use an orange translucent halo.
- Component origin, rotation, 10-unit grid, and geometric wire representation remain unchanged.
- M8b-v2 validates generated move/rotation links against the complete pre-gesture net partition, rejects any new shorts, and falls back to committing geometry without generated links when validation fails.
- M7b history snapshots contain placed components (including optional labels) and geometric wire segments; input poke values are not history state.
- Undo/redo restoration preserves poke values for input ids still present; an input restored after deletion returns LOW.
- Undefined-input mode is a persisted document option; changing it increments the modified revision and recomputes simulation, but is excluded from topology undo/redo snapshots.
- The synthesis package is independent of UI and Schematic. It compiles validated netlists directly into the public engine Circuit API and performs reference comparison against immutable ASTs.
- Constant-folded expression outputs use literal 0 or 1 in outputs[].source; the layout realizes these with ordinary labeled input switches (0 starts LOW, 1 starts HIGH) because no fixed-rail source component exists in the current catalogue.
- Expression layout retains deterministic depth columns, dummy nodes for skipped columns, and four barycenter ordering sweep pairs; compact coordinates use a fixed 60-unit input pitch and three alternating pin-driven vertical sweeps within each later column, with a 40-unit minimum body clearance and closest-safe pin-row shifts to prevent cross-signal T-junctions. Per-net channel trunks use 20-unit margins and 20-unit pitch, sized to the actual trunk count.
- Layout verification extracts actual geometric nets and requires a one-to-one partition match with the netlist before returning a Schematic; a single retry doubles layout spacing.
- The Generate action verifies the placed Schematic through the standard geometry-to-engine compile path over every declared-input row before replacing the current topology in one history step.
- Tidy circuit extracts the current geometric nets, orders primary inputs and outputs by current y then x, preserves their visible labels and switch states, and reuses the production SchematicLayout engine. Gate facings are reset to EAST; applying a verified result is one undo step and fits the view.
- Tidy circuit compares the original and replacement through SchematicCompiler for every input combination up to 12 inputs; above 12 it explicitly skips equivalence checking. The general Netlist supports larger input sets for layout-only use, while expression compilation and exhaustive truth-table generation remain capped at 12.

## Workflow
- One task, one branch from fresh main, one PR with base main.
- ./gradlew test must be green. Do not commit screenshots or throwaway workflows.
- User reviews and merges each milestone PR.

- AI circuit generation uses `AiConfig` in `$XDG_CONFIG_HOME/logicbench/ai.json` or `~/.config/logicbench/ai.json` on Linux and `%APPDATA%\\LogicBench\\ai.json` on Windows. Verified live answers are cached in `ai_cache.json`; six bundled examples are in `demo_prompts.json`.
- AI-generated output is parsed only as expression text, laid out through `SchematicLayout`, and checked through the placed-schematic engine and netlist truth-table engine before it can be placed. The AI response format currently inlines intermediates; although the parser supports `:=`, a previous `=` output referenced by a later expression remains a primary input.
