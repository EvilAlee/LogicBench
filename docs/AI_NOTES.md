# AI circuit generation notes

## Exchange schema

The model returns exactly one JSON object with these required fields:

- \`summary\`: string.
- \`inputs\`: array of unique primary-input names. Any order is accepted; the array order defines truth-table bit positions (first input is MSB).
- \`outputs\`: array of \`{ "name": string, "expression": string }\`.
- \`truthTable\`: array of \`{ "output": string, "bits": string }\`; each bits string has \(2^n\) characters, only 0/1, row zero is all inputs low.

The application rejects malformed/extra fields, invalid names, duplicate names, undeclared or unused inputs, over 6 inputs, over 8 outputs, expressions over 1000 characters, malformed bit strings, and circuits over 200 gates.

## System prompt

The current prompt in \`AiCircuitService.systemPrompt()\` instructs Gemini to:

1. Treat the user's description as the circuit specification and return JSON only.
2. List every primary input exactly once in any order; truth-table row bits follow the inputs array (first input is MSB). Include requested outputs only and an independently reasoned truth table.
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

A model answer that fails verification is returned to the model with the
previous JSON and concrete errors (including parser positions or up to eight
mismatching rows). There are at most three verification attempts. After three
failed answers, the result is UNVERIFIED and retains the attempt with the
fewest mismatching rows. Provider transport failures are separate: they are
retried across models/rounds and end as UNAVAILABLE or use a verified cache
answer; they never consume verification attempts.


## Model configuration and transport handling

The configuration file `ai.json` accepts an optional ordered `fallbackModels`
array. Older files without this property remain valid.

```json
{
  "apiKey": "YOUR_KEY",
  "model": "gemini-3.8-flash",
  "fallbackModels": ["gemini-3.5-flash-lite", "another-supported-model"]
}
```

The primary `model` is tried first, followed by `fallbackModels` in array
order. Empty entries, duplicates, and a duplicate of the primary model are
ignored. `GEMINI_MODEL` still overrides the primary model from the file; the
fallback list remains file-configured.

A model round calls each model not already marked unavailable once. HTTP 404
marks only that model skipped for the rest of the current circuit-generation
request. HTTP 429, HTTP 5xx, request timeout, DNS/unknown-host, and connection
failures move to the next model. If all remaining models fail retryably, the
service waits **2 seconds** and tries another round, then waits **6 seconds**
before the final round. There are at most two retry rounds. The wait is
interruptible, so Cancel interrupts it. HTTP 401/403 and an HTTP 400 response
that identifies an invalid API key stop immediately; trying another model
cannot repair the same rejected credential.

The HTTP connect timeout is **5 seconds** and each request timeout is
**20 seconds**.

### Result statuses

- **VERIFIED** — a model answered and the engine verified its expressions and truth table. The result header names the model, for example `VERIFIED via gemini-3.5-flash-lite`.
- **UNVERIFIED** — a model answer arrived, but the answer failed expression/schema/engine verification after up to three verification attempts. The best attempted answer is retained for review.
- **FROM CACHE** — the displayed answer came from the user cache or bundled cache after cache-only mode, or after live provider failure. The cached answer is engine-verified before display. Following a live provider failure, the result header shows the reason and model, e.g. `AI unavailable (HTTP 503, model gemini-3.8-flash) - showing cached result`.
- **UNAVAILABLE** — no usable model answer or valid cache answer is available, or the API key was rejected. No circuit is placeable on this result.

Live mode tries the configured models before looking for a cache fallback. On exhausted transport failure or rejected credentials, it looks up the normalized description in the user cache first, then the bundled cache. Cache-only mode checks those same caches immediately and makes no network request. A miss returns UNAVAILABLE.

The Test connection action runs off the Swing EDT. It tries the primary model and then each configured fallback model, reports one line per model with its HTTP status (or a no-response reason), and stops immediately on key rejection. 429/503 messages name the affected model. The configured API key is redacted from diagnostics and UI text; it is only sent in the Gemini request header and stored in `ai.json`.

## Current prompt text

The implementation sends the following system instruction (the runtime source of
truth is \`AiCircuitService.systemPrompt()\`):

\`\`\`text
You are a Boolean-circuit expression author. Treat the user's description as
the specification. Return ONLY one JSON object matching the supplied schema;
no Markdown, code fences, commentary, or extra properties.

JSON fields:
- summary: short plain-language description.
- inputs: every true primary input exactly once. The inputs array may be in
  any order; each truth-table row's bits follow the order of that inputs array.
  The first input is the most-significant bit.
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
