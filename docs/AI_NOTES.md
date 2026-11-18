# AI circuit generation notes

## Exchange schema

The model returns exactly one JSON object with these required fields:

- \`summary\`: string.
- \`inputs\`: ordered array of unique primary-input names. The first input is the truth-table most-significant bit.
- \`outputs\`: array of \`{ "name": string, "expression": string }\`.
- \`truthTable\`: array of \`{ "output": string, "bits": string }\`; each bits string has \(2^n\) characters, only 0/1, row zero is all inputs low.

The application rejects malformed/extra fields, invalid names, duplicate names, undeclared or unused inputs, over 6 inputs, over 8 outputs, expressions over 1000 characters, malformed bit strings, and circuits over 200 gates.

## System prompt

The current prompt in \`AiCircuitService.systemPrompt()\` instructs Gemini to:

1. Treat the user's description as the circuit specification and return JSON only.
2. Provide the primary inputs in truth-table order (MSB first), requested outputs only, and an independently reasoned truth table.
3. Use exactly the parser's syntax:
   - Highest: prefix \`!\`, \`~\`, \`NOT\`; postfix \`'\`.
   - Then \`&\`, \`*\`, \`AND\`, \`NAND\`.
   - Then \`^\`, \`XOR\`, \`XNOR\`.
   - Lowest: \`|\`, \`+\`, \`OR\`, \`NOR\`.
   - Parentheses override precedence; binary operators are left-associative.
4. Use at most 6 inputs, 8 outputs, and 1000 characters per expression. Only declared inputs may be referenced.
5. Represent every requested output as \`Name = expression\`. Do not define intermediate named signals; inline them. Identical subexpressions are shared automatically.
6. Never return gates, a netlist, wires, or schematic instructions. The application parses expression text and performs construction and verification.

## Named-signal verification result

The headless integration test runs the two supplied examples through the real
\`ExpressionCompiler\` -> \`SchematicLayout\` -> \`SchematicExpressionVerifier\`
path. The \`:=\` internal signal creates no output pin, and the repeated
\`A^B\` subexpression in the second example is represented by one shared XOR
gate. Both placed schematics pass engine verification against their ASTs.

The first example exposes an important distinction: a later reference to a
previous \`=\` output name is treated as a primary input, not as a reference to
the earlier output expression. The test expected only \`A, B\`, but the actual
netlist inputs were \`A, B, S0\`. Therefore the AI prompt must not treat a
previous \`=\` output as an intermediate. It continues to inline all
intermediate expressions.

**\`:=\` not supported yet by the AI response format.** The parser/compiler do
support \`:=\` for internal signals, but the current AI JSON schema contains
only output expression records and the first required integration example
demonstrates that reusing an \`=\` output name does not create an internal
reference. Until the exchange schema explicitly represents internal signals,
AI-generated answers must inline intermediate expressions.

## Validation and trust boundary

Model JSON is untrusted. The service validates the JSON shape and limits,
parses every expression with \`ExpressionParser\`, checks declared variables,
builds a netlist through \`ExpressionCompiler\`, lays it out through
\`SchematicLayout\`, verifies the placed schematic through
\`SchematicExpressionVerifier\`, and compares the netlist engine truth table
with the model's claimed bits. The model's truth table is not trusted as a
source for circuit construction.

A failed answer is returned to the model with the previous JSON and concrete
errors (including parser positions or up to eight mismatching rows). There are
at most three attempts. After three failures, the result is UNVERIFIED and
retains the attempt with the fewest mismatching rows.


## Current prompt text

The implementation sends the following system instruction (the runtime source of
truth is \`AiCircuitService.systemPrompt()\`):

\`\`\`text
You are a Boolean-circuit expression author. Treat the user's description as
the specification. Return ONLY one JSON object matching the supplied schema;
no Markdown, code fences, commentary, or extra properties.

JSON fields:
- summary: short plain-language description.
- inputs: every true primary input exactly once, in first-appearance order across
  output expressions. This is the truth-table order; the first input is the
  most-significant bit.
- outputs: only the outputs the user requested, each with a unique name and
  one Boolean expression.
- truthTable: independently reason from the user's description and provide
  one entry per output. Each bits string has exactly 2^input-count characters,
  uses only 0 and 1, and starts with the row where all inputs are zero.
  Do NOT derive the table by merely copying/evaluating your expressions;
  independently determine the intended behavior from the description.

Expression syntax must be exactly the LogicBench expression parser syntax.
Identifiers are ASCII names matching [A-Za-z][A-Za-z0-9_]*. Constants are 0
and 1. Operators, highest precedence first:
1. NOT: prefix !, ~, NOT; postfix apostrophe (').
2. AND family: &, *, AND, NAND.
3. XOR family: ^, XOR, XNOR.
4. OR family: |, +, OR, NOR.
Parentheses override precedence; binary operators are left-associative.

Every expression may reference only names declared in inputs. Use at most
6 inputs and 8 outputs. Each expression must be at most 1000 characters.
Names must use the identifier syntax above.

Each output is represented as "Name = expression" and therefore creates an
output pin. Do NOT define intermediate named signals; inline sub-expressions
instead. Identical subexpressions are shared automatically by the compiler.
Do not output netlists, gates, wiring, or schematic instructions. The
application parses your expressions and constructs and verifies the circuit.
\`\`\`
