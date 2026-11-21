# AI circuit generation and verification

## Response schema

A new model answer has these required fields:

- \`summary\`: a short description.
- \`inputs\`: every primary input exactly once, in any order.
- \`outputs\`: an array of \`{"name":"S","expression":"A ^ B"}\`. Each output becomes a pin.
  
Optional fields:

- \`signals\`: internal named expressions, each \`{"name":"C1","expression":"A & B"}\`. These compile as \`C1 := A & B\`, create no output pin, and may use forward references to other signal definitions.
- \`spec\`: an executable integer specification of the expected behavior:

  \`\`\`json
  {
    "buses": {"A": ["A1", "A0"], "B": ["B1", "B0"], "S": ["S1", "S0"]},
    "rules": [
      {"out": "S", "expr": "A + B"},
      {"out": "COUT", "expr": "(A + B) >> 2"}
    ]
  }
  \`\`\`

Bus pins are MSB first. A pin not listed in a bus is implicitly its own one-bit bus. Each rule assigns one output bus, and every output pin must be covered exactly once. Rule expressions may refer to input bus names and individual input pin names.

The model must not return a truth table. The new response schema does not contain a \`truthTable\` field. For compatibility, the parser tolerates that field in old answers/cache entries, ignores its contents completely, and never treats it as verification evidence.

## Limits

All limits live in \`AiCircuitLimits\`:

| Item | Limit |
|---|---:|
| Primary inputs | 10 |
| Outputs | 16 |
| Gates | 400 |
| Total expression text (signals, outputs and spec rules) | 3,000 characters |
| Spec rules | 16 |
| User-supplied expected rows | 64 |
| Verification attempts | 3 |

## Expression and spec languages

Boolean expressions use ASCII identifiers matching \`[A-Za-z][A-Za-z0-9_]*\`, constants \`0\` and \`1\`, parentheses, prefix \`!\`, \`~\`, or \`NOT\`, postfix apostrophe, AND \`&\`, \`*\`, \`AND\`, \`NAND\`, XOR \`^\`, \`XOR\`, \`XNOR\`, OR \`|\`, \`+\`, \`OR\`, and \`NOR\`. Parentheses override precedence. NAND, NOR, and XNOR are operator words.

The spec language is a separate integer language, not the Boolean expression grammar. It supports signed 64-bit arithmetic and decimal constants with \`+\`, \`-\`, \`*\`, \`/\`, \`%\`, \`<<\`, \`>>\`, \`&\`, \`|\`, \`^\`, \`!\`, \`==\`, \`!=\`, \`<\`, \`<=\`, \`>\`, \`>=\`, \`?:\`, parentheses, and \`popcount(x)\`. Division/modulo by zero is a spec error. Arithmetic uses Java 64-bit \`long\` behavior; the final value is masked to the width of its output bus. Bus inputs are packed MSB first.

Add a spec for arithmetic, comparison, selection, parity, counting, and decoders. Omit it for irregular logic that has no compact integer description. The spec is another executable check, not an explanation or claim from the model.

The user may add rows in the dialog, one per line:

\`\`\`text
A=1 B=0 -> S=1 C=0
A=0 B=0 -> S=0 C=0
\`\`\`

Every row must assign every declared input and output exactly once. Values are \`0\` or \`1\`. A malformed row reports its line number and the offending name. An engine \`X\` never matches a user row.

## Validation pipeline and trust boundary

For each model answer, in order:

1. Validate JSON shape and all size limits. The legacy \`truthTable\` property, when present, is ignored.
2. Parse each output and internal signal expression with parser positions.
3. Confirm the declared input list is a permutation of the actual expression variables. A misspelled identifier that becomes an undeclared input is named in the error.
4. Compile with raw feedback disabled and lay out the circuit.
5. Run the existing placed-geometry verifier, then enumerate every engine input combination. An \`X\` output or oscillation fails the attempt. Disconnected declared inputs and constant outputs are warnings.
6. Check the user's expected rows, when present.
7. Parse and execute the spec, when present, across every input combination.

A spec parse error, division-by-zero error, or mismatch is a failed attempt. Retry feedback is named and capped at eight lines. Bus mismatches use decimal values, for example:

\`A=3 B=1 CIN=0: S spec 4, circuit 5. Either side may be wrong.\`

There are at most three model-validation attempts. After three failing attempts, the result is UNVERIFIED and retains the best attempt (fewest failing checks/rows), which remains placeable only after the existing confirmation. Provider transport failures are handled separately by the existing retry/model-fallback path.

## Status meanings

- **VERIFIED** means the built circuit agrees with an executable spec or the user's expected rows, and every independent check that was supplied passed. It is not proof that the model understood the user's intent.
- **BUILT** means JSON, parsing, build, placement, geometry and structural engine checks passed, but neither an executable spec nor user rows were supplied. Read the generated truth table before relying on it.
- **UNVERIFIED** means one or more validation checks failed after the available attempts. The best attempt is retained for review and requires the normal confirmation before placement.
- **FROM CACHE** is the result-source badge when a cached answer is replayed. A cache answer goes through the same current validation path; a legacy entry with only a claimed table is at most BUILT, never VERIFIED from that table.
- **UNAVAILABLE** means no usable live answer or valid cache answer is available. There is no placeable circuit.

Newly stored answers use the new schema. A successful answer is cached without \`truthTable\`; an old cache entry is still loadable and is revalidated under the same rules. The six bundled demonstration prompts remain old-format fixtures and are also revalidated, so they show FROM CACHE and BUILT unless independent rows/spec are later added.

## Inverting-gate mapping

The compiler has an opt-in \`invertingGates\` compile option. It applies deterministic NAND/NOR/XNOR and De Morgan rewrites to a fixpoint, only where each rewrite strictly reduces gate count and required inner gates have a single consumer. For up to 12 inputs, the engine truth table is compared before and after; any mismatch or table-generation failure discards the optimized candidate. AI generation defaults this option ON. The typed expression dialog defaults it OFF. With the option OFF, the previous netlist is unchanged.

## Model configuration and transport handling

The configuration file \`ai.json\` accepts an optional ordered \`fallbackModels\` array. Older files without this property remain valid.

\`\`\`json
{
  "apiKey": "YOUR_KEY",
  "model": "gemini-3.8-flash",
  "fallbackModels": ["gemini-3.5-flash-lite", "another-supported-model"]
}
\`\`\`

The primary \`model\` is tried first, followed by \`fallbackModels\` in array order. Empty entries, duplicates, and a duplicate of the primary model are ignored. \`GEMINI_MODEL\` still overrides the primary model from the file; the fallback list remains file-configured.

A model round calls each model not already marked unavailable once. HTTP 404 marks only that model skipped for the rest of the current circuit-generation request. HTTP 429, HTTP 5xx, request timeout, DNS/unknown-host, and connection failures move to the next model. If all remaining models fail retryably, the service waits **2 seconds** and tries another round, then waits **6 seconds** before the final round. There are at most two retry rounds. The wait is interruptible, so Cancel interrupts it. HTTP 401/403 and an HTTP 400 response that identifies an invalid API key stop immediately; trying another model cannot repair the same rejected credential.

The HTTP connect timeout is **5 seconds** and each request timeout is **20 seconds**.

The Test connection action runs off the Swing EDT. It tries the primary model and then each configured fallback model, reports one line per model with its HTTP status (or a no-response reason), and stops immediately on key rejection. 429/503 messages name the affected model. The configured API key is redacted from diagnostics and UI text; it is only sent in the Gemini request header and stored in \`ai.json\`.

## Runtime system prompt

The following is the complete system instruction sent by \`AiCircuitService.systemPrompt()\`:

\`\`\`text
You write Boolean circuits. Return ONLY JSON for the supplied schema:
summary, inputs, outputs, optional signals, optional spec. Never output a
truth table. Use only declared primary inputs. Limits: 10 inputs, 16 outputs,
400 gates, 3000 total expression characters, 16 spec rules; user rows max 64.

Expressions use identifiers [A-Za-z][A-Za-z0-9_]*, constants 0/1, parentheses,
prefix !, ~, NOT, postfix ', AND &, *, AND, NAND, XOR ^, XNOR, OR |, +, NOR.
NAND, NOR and XNOR are operator words. Outputs use {name, expression};
internal named signals use {name, expression} in signals and compile as
name := expression. Forward references between signals are allowed.
List every primary input exactly once; do not list internal signals as inputs.

Add an executable spec for arithmetic, comparison, selection, parity, counting
and decoders. Omit spec for irregular logic whose behavior is not compactly
described by integer rules. A spec is {buses:{bus:[pins...]},rules:[{out,expr}]};
bus pins are MSB first, and every output pin must be covered. A pin not grouped
in buses is its own one-bit bus. Specs use decimal integer arithmetic and
bitwise expressions; they are checked against every engine input combination.
The circuit expressions and the spec are both independently checked.

Example 1 — 2-bit adder with carry-in:
{"summary":"2-bit adder","inputs":["A1","A0","B1","B0","CIN"],
 "signals":[{"name":"C1","expression":"(A0 & B0) | (A0 & CIN) | (B0 & CIN)"}],
 "outputs":[
  {"name":"S0","expression":"A0 ^ B0 ^ CIN"},
  {"name":"S1","expression":"A1 ^ B1 ^ C1"},
  {"name":"COUT","expression":"(A1 & B1) | (A1 & C1) | (B1 & C1)"}],
 "spec":{"buses":{"A":["A1","A0"],"B":["B1","B0"],"S":["S1","S0"]},
         "rules":[{"out":"S","expr":"A + B + CIN"},
                  {"out":"COUT","expr":"(A + B + CIN) >> 2"}]}}

Example 2 — 2-to-4 decoder with enable:
{"summary":"enabled decoder","inputs":["A1","A0","E"],
 "outputs":[
  {"name":"D0","expression":"E & NOT A1 & NOT A0"},
  {"name":"D1","expression":"E & NOT A1 & A0"},
  {"name":"D2","expression":"E & A1 & NOT A0"},
  {"name":"D3","expression":"E & A1 & A0"}],
 "spec":{"buses":{"A":["A1","A0"],"D":["D3","D2","D1","D0"]},
         "rules":[{"out":"D","expr":"E << A"}]}}
\`\`\`

## Verification performance

The largest supported input space is 10 bits (1,024 engine rows). In GitHub Actions run 44865571316, the 9-input/5-output four-bit adder plus the 8-input/8-output 4×4 multiplier built, laid out, and passed executable-spec verification in **2,611.5 ms combined** (both cases; under the required 10-second CI bound).

A separate maximum-limit layout stress case used **10 inputs, 16 outputs, and 400 gates** arranged as a 400-stage chain with side-input connections. Its layout took **109,496.9 ms** (about **109.5 seconds**) in the same CI run. This is a deliberately deep, worst-case-shaped layout measurement, not the runtime of the arithmetic examples. Both timings are environment-specific measurements, not performance guarantees.
