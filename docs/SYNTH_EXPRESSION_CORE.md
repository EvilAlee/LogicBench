# Headless expression synthesis core

The package `io.github.evilalee.gatelab.synth` parses Boolean expressions, validates signal netlists, compiles expression ASTs into 2-input-gate graphs, and checks those graphs by simulating every primary-input assignment through the real LogicBench engine. It has no Swing/UI dependency and does not use the editor `Schematic`.

## Expression language

Identifiers match `[A-Za-z][A-Za-z0-9_]*`; `0` and `1` are Boolean constants. Operators are case-insensitive when written as words.

| Precedence (high to low) | Operators |
|---|---|
| NOT | prefix `!`, `~`, `NOT`; postfix `'` |
| AND family | `&`, `*`, `AND`, `NAND` |
| XOR family | `^`, `XOR`, `XNOR` |
| OR family | `|`, `+`, `OR`, `NOR` |

Parentheses override precedence. Binary operators are left-associative. `ExpressionParser.parse` returns an immutable `Expression` AST; `Expression.variables()` returns distinct identifiers in first-appearance order. `ParseException.position()` is a zero-based offset into the supplied expression. `ExpressionEvaluator` evaluates the AST using ordinary two-valued Java booleans, independently of the simulator.

A multi-output request contains one assignment per line or semicolon-delimited statement:

```text
Sum = A ^ B ^ Cin
Carry = (A & B) | (Cin & (A ^ B))
```

An unassigned expression receives output name `F`. Consequently, multiple unnamed statements conflict and are rejected; name each output when compiling more than one expression.

### Named output and internal-signal assignments

- `Name = expression` defines an output pin named `Name`.
- `Name := expression` defines an internal signal. It creates **no output pin**.
- Every name defined by either assignment form is a signal throughout the complete statement list. Definitions are collected before resolution, so references may appear before or after the defining line; only names with no definition become primary inputs.
- A self-reference or multi-line cycle is preserved as a feedback graph for the expression dialog. Acyclic definitions are expanded into the ordinary shared DAG path.
- Identical expression subtrees are interned by the compiler, so repeated logic is represented by shared gates.

Example:

```text
S0 = A ^ B
Carry0 := A & B
S1 = A1 ^ B1 ^ Carry0
```

A referenced `=` definition remains an output pin, but references to its name resolve to the defined signal rather than creating an extra input. For example, `S0 = A^B; S1 = S0^C` has primary inputs `A`, `B`, and `C`, with both `S0` and `S1` output pins. The reused-output hint remains useful when the intermediate should instead be private; use `:=` to avoid an output pin.

## Netlist JSON schema

```json
{
  "inputs": ["A", "B"],
  "outputs": [
    {"name": "F", "source": "g2"}
  ],
  "gates": [
    {"id": "g1", "type": "AND", "inputs": ["A", "B"]},
    {"id": "g2", "type": "NOT", "inputs": ["g1"]}
  ]
}
```

- `inputs` is an ordered array of unique signal names. The general Netlist model permits more than 12 inputs for layout-only uses; expression compilation and exhaustive truth-table generation remain limited to 12.
- `gates` contains unique signal ids and one of `AND`, `OR`, `NAND`, `NOR`, `XOR`, `XNOR` (exactly two inputs), `NOT`, or `BUFFER` (one input).
- A gate input references a primary input name, another gate id, or literal constant `0`/`1`. Forward references are allowed. The default Netlist constructor rejects cycles; the expression feedback path opts into cyclic graphs, while `topologicalGates()` still rejects a cycle when a DAG-only consumer requests topological order.
- `outputs` contains unique output labels and a source that references an input or gate id.
- Signal names and gate ids share one namespace and must be unique. Output labels form a separate namespace and may match a signal name.

**Constant sources:** the `outputs[].source` field and gate input references accept the literal string `"0"` or `"1"`. The layout and direct truth-table builder realize gate-input constants as fixed source pins, not user-declared inputs. Ordinary acyclic expression compilation continues to fold constants and identities where possible.

`NetlistJson` reads and writes this same schema with the existing Gson dependency; no document or netlist JSON format change is made. The JSON reader constructs the default DAG-only Netlist. `Netlist` validates duplicate names/ids, unknown references, gate arity and output sources. Feedback-enabled expression compilations carry an explicit in-memory opt-in; DAG-only consumers still detect and reject cycles through `topologicalGates()`. `ExpressionCompiler` and `TruthTableGenerator` enforce the 12-input exhaustive-verification limit; the Netlist itself can represent larger graphs for layout workflows that skip exhaustive verification.

## Compilation and engine verification

`ExpressionCompiler.compile(source)` returns a `Compilation` with both the validated `Netlist` and named ASTs. The compiler folds constants and simple Boolean identities, builds chains as left-associated two-input gates, and interns structurally identical expression nodes so shared logic is emitted once across outputs.

`TruthTableGenerator.generate(netlist)` constructs `Circuit`, `InputPin`, `Gate`, and `OutputProbe` instances directly. It does not route through the editor `Schematic` or its geometry compiler. It enumerates assignments in binary order with the first listed input as the most-significant bit, updates every source, calls `Simulator.settle()`, and records engine `Value` outputs (`ZERO`, `ONE`, or `X`) plus the oscillation flag.

`TruthTableVerifier.verify(compilation)` compares each engine output to the pure AST evaluator for the same assignment. `VerificationResult.verified()` is true only when there are no mismatching outputs or oscillating rows. `mismatches()` identifies the input row, output, expected and actual value; `mismatchingRows()` returns each affected truth-table row once.

The expression-to-schematic path is documented in [SYNTH_EXPRESSION_LAYOUT.md](SYNTH_EXPRESSION_LAYOUT.md). `SchematicLayout` creates editable components and wires; `SchematicExpressionVerifier` checks acyclic placed geometry through `SchematicCompiler.compileAndSettle` against the independent AST evaluator. For feedback compilations, `FeedbackCircuitExtractor` cuts back-edges into placeholders, and `SchematicLayout.layoutFeedback` reuses the existing feedback-lane router. It checks the final pin-to-net partition against the reconstructed cyclic netlist; combinational truth-table verification is skipped.

## Public API

- `Expression`, `ParseException`, `ExpressionParser`, `ExpressionEvaluator`
- `NamedExpression`, `ExpressionCompiler` and `ExpressionCompiler.Compilation`
- `GateOperator`, `Netlist`, `Netlist.Output`, `Netlist.Gate`, `NetlistJson`
- `TruthTable`, `TruthTable.Row`, `TruthTableGenerator`
- `TruthTableVerifier`, `TruthTableVerifier.Mismatch`, `TruthTableVerifier.VerificationResult`

All synthesis sources are in `src/main/java/io/github/evilalee/gatelab/synth`. Tests are headless and live under `src/test/java/io/github/evilalee/gatelab/synth`.
