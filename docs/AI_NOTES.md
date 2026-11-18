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
