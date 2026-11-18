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
- An internal signal may be referenced by later statements; it is resolved to its expression AST before netlist construction. Forward references are not supported.
- Identical expression subtrees are interned by the compiler, so repeated logic is represented by shared gates.

Example:

```text
S0 = A ^ B
Carry0 := A & B
S1 = A1 ^ B1 ^ Carry0
```

Important distinction: referencing a previous `=` output name does **not** refer back to that output's expression. The name is treated as a primary input in the later expression (and the compiler records the reused-output warning). For example, `S0 = A^B; S1 = S0^C` has primary inputs `A`, `B`, and `S0`. Use `:=` for a reusable intermediate signal. This behavior was verified through the real parse, netlist, layout, and placed-schematic engine-verification path.

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
- A gate input references a primary input name or another gate id. Forward references are allowed; the validated model detects cycles and exposes a topological gate order.
- `outputs` contains unique output labels and a source that references an input or gate id.
- Signal names and gate ids share one namespace and must be unique. Output labels form a separate namespace and may match a signal name.

**Constant outputs:** the `outputs[].source` field also accepts the literal string `"0"` or `"1"`. The expression compiler folds Boolean constants and identities; when an entire output reduces to a constant it emits that literal directly. This avoids fabricating an input that would appear as a free truth-table variable. Gate input references do not accept literal constants; the compiler simplifies them before netlist creation.

`NetlistJson` reads and writes this schema with the existing Gson dependency. `Netlist` validates the graph when constructed and reports duplicate names/ids, unknown references, gate arity errors, missing output sources, and combinational cycles. `ExpressionCompiler` and `TruthTableGenerator` enforce the 12-input exhaustive-verification limit; the Netlist itself can represent larger graphs for layout workflows that skip exhaustive verification.

## Compilation and engine verification

`ExpressionCompiler.compile(source)` returns a `Compilation` with both the validated `Netlist` and named ASTs. The compiler folds constants and simple Boolean identities, builds chains as left-associated two-input gates, and interns structurally identical expression nodes so shared logic is emitted once across outputs.

`TruthTableGenerator.generate(netlist)` constructs `Circuit`, `InputPin`, `Gate`, and `OutputProbe` instances directly. It does not route through the editor `Schematic` or its geometry compiler. It enumerates assignments in binary order with the first listed input as the most-significant bit, updates every source, calls `Simulator.settle()`, and records engine `Value` outputs (`ZERO`, `ONE`, or `X`) plus the oscillation flag.

`TruthTableVerifier.verify(compilation)` compares each engine output to the pure AST evaluator for the same assignment. `VerificationResult.verified()` is true only when there are no mismatching outputs or oscillating rows. `mismatches()` identifies the input row, output, expected and actual value; `mismatchingRows()` returns each affected truth-table row once.

The expression-to-schematic path is documented in [SYNTH_EXPRESSION_LAYOUT.md](SYNTH_EXPRESSION_LAYOUT.md). `SchematicLayout` creates editable components and wires; `SchematicExpressionVerifier` checks the placed geometry through `SchematicCompiler.compileAndSettle` against the independent AST evaluator.

## Public API

- `Expression`, `ParseException`, `ExpressionParser`, `ExpressionEvaluator`
- `NamedExpression`, `ExpressionCompiler` and `ExpressionCompiler.Compilation`
- `GateOperator`, `Netlist`, `Netlist.Output`, `Netlist.Gate`, `NetlistJson`
- `TruthTable`, `TruthTable.Row`, `TruthTableGenerator`
- `TruthTableVerifier`, `TruthTableVerifier.Mismatch`, `TruthTableVerifier.VerificationResult`

All synthesis sources are in `src/main/java/io/github/evilalee/gatelab/synth`. Tests are headless and live under `src/test/java/io/github/evilalee/gatelab/synth`.
