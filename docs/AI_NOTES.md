# AI circuit generation and verification

## Response schema

A new model answer has these required fields:

- \`summary\`: a short description.
- \`inputs\`: every primary input exactly once, in any order.
- \`outputs\`: an array of \`{"name":"S","expression":"A ^ B"}\`. Each output becomes a pin.
  
Optional fields:

- \`signals\`: internal named expressions, each \`{"name":"C1","expression":"A & B"}\`. These compile as \`C1 := A & B\`, create no output pin, and must reference declared inputs or earlier signals only; forward references are rejected.
- \`spec\`: an executable integer specification of the expected behavior:

  \`\`\`json
  {
    "buses": {"A": ["A1", "A0"], "B": ["B1", "B0"], "S": ["S1", "S0"]},
    "rules": [
      {"out": "S", "expression": "A + B"},
      {"out": "COUT", "expression": "(A + B) >> 2"}
    ]
  }
  \`\`\`

Bus pins are MSB first. A pin not listed in a bus is implicitly its own one-bit bus. Each rule assigns one output bus, and every output pin must be covered exactly once. Rule expressions may refer to input bus names and individual input pin names.

The model must not return a truth table. The new response schema does not contain a \`truthTable\` field. For compatibility, the parser tolerates that field in old answers/cache entries, ignores its contents completely, and never treats it as verification evidence.

## AI Explain mode

The AI dialog has a separate **Explain** mode; **Generate** remains the default and
continues to use its existing templates, cache, regeneration, layout, and verification
pipeline. Explain always snapshots the schematic currently open in the editor and
exports it with `CircuitDescriber`, including boxed component calls and output
selectors. An export failure, including the 400-gate limit, is shown locally and
prevents any provider request.

The request contains the user's question, the exported circuit expression, and
engine-derived facts: gate and instance counts, input/output names, feedback and
sequential classification, and simulation rows. Combinational designs with up to
six inputs include every row; designs with more inputs include 64 evenly spread
rows. Feedback or sequential designs explicitly report
`contains feedback; truth table not shown`. Truth-table values are obtained by
the LogicBench engine, not by the model.

Explain has its own response schema: required `summary` and `steps`, with optional
`notable`. Malformed responses are retried using the existing attempt and provider
model-fallback policy. Transport failure is shown as `UNAVAILABLE`. The result is
labelled **AI explanation, not verified**. Explain neither creates nor places a
schematic and never reads or writes the verified-generation cache. The **Show what
will be sent** preview displays the exact system prompt and user message sent to
the provider.

The model may describe relationships visible in the exported expression and
engine facts only. It must not be treated as an independent verification or as a
source of unreported timing, electrical, or physical behavior. Users should inspect
the actual schematic and its engine facts.

## Limits

All limits live in \`AiCircuitLimits\`:

| Item | Limit |
|---|---:|
| Primary inputs | 10 |
| Outputs | 16 |
| Intermediate signals | 160 |
| Individual expression length (outputs, signals and spec rules) | 300 characters |
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
2. Enforce the 300-character expression limit and 160-signal limit. Parse each output and internal signal expression with parser positions; signals may reference declared inputs and earlier signals only.
3. Confirm the declared input list is a permutation of the actual expression variables. A misspelled identifier that becomes an undeclared input is named in the error.
4. Compile with raw feedback disabled and lay out the circuit.
5. Run the existing placed-geometry verifier, then enumerate every engine input combination. An \`X\` output or oscillation fails the attempt. Disconnected declared inputs, constant outputs, and internal signals not transitively used by an output are warnings.
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

Normal generation checks the normalized user cache first and then the bundled cache, after the direct-expression shortcut and before making a provider request. Every hit is revalidated through the current validation pipeline; an invalid hit is ignored and live generation continues. The dialog's Regenerate action bypasses cache lookup and cache fallback. Only a live answer that passes an independent check replaces the user cache, and its model name is stored alongside the answer.

Newly stored answers use the new schema and do not include `truthTable`. Versioned user-cache entries store the validated answer and producing model. Earlier unwrapped user-cache entries remain readable and are revalidated under the same rules; their original model is unknown. The six bundled demonstration prompts remain old-format fixtures and are also revalidated, so they show FROM CACHE and BUILT unless independent rows/spec are later added.

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

The following is the complete system instruction sent by `AiCircuitService.systemPrompt()`:

```text
You design digital logic circuits. Reply with JSON only, shaped as {summary, inputs, outputs, signals, spec}.
inputs: single-bit pin names.
outputs: list of {name, expression} for ONLY the pins the user asked for.
signals: ordered list of {name, expression} for every intermediate value (propagate, generate, carries, partial products).
A signal may use inputs and EARLIER signals only. Reuse earlier signals: never expand an earlier carry inside a later formula; write C2 from C1 and C3 from C2.
Output expressions use inputs and signals. Every expression is at most 300 characters.
Operators: & | ^ ! parentheses and the words NAND NOR XNOR.
Default to ripple carry for adders (each carry from the previous carry).
If the user explicitly asks for carry lookahead, write each carry directly from P, G and CIN using named product signals.
spec: {buses: {A: [A3,A2,A1,A0]}, rules: [{out, expression}]} using integer arithmetic over bus names, for example S = A + B + CIN, and it must cover every output.

Additional response limits: at most 10 inputs, 16 outputs, 160 signals, 400 gates, 3000 total expression characters, 16 spec rules, and 64 user-supplied expected rows. Never return a truth table. Include a spec for arithmetic, comparison, selection, parity, counting and decoders; omit it for irregular logic without a compact integer description. Bus pins are MSB first, and undeclared pin groupings are one-bit buses.

Example 1 — 4-bit ripple-carry adder:
{"summary":"4-bit ripple adder","inputs":["A3","A2","A1","A0","B3","B2","B1","B0","CIN"],
 "signals":[
  {"name":"G0","expression":"A0 & B0"},{"name":"G1","expression":"A1 & B1"},
  {"name":"G2","expression":"A2 & B2"},{"name":"G3","expression":"A3 & B3"},
  {"name":"P0","expression":"A0 ^ B0"},{"name":"P1","expression":"A1 ^ B1"},
  {"name":"P2","expression":"A2 ^ B2"},{"name":"P3","expression":"A3 ^ B3"},
  {"name":"C1","expression":"G0 | (P0 & CIN)"},
  {"name":"C2","expression":"G1 | (P1 & C1)"},
  {"name":"C3","expression":"G2 | (P2 & C2)"},
  {"name":"C4","expression":"G3 | (P3 & C3)"}],
 "outputs":[
  {"name":"S0","expression":"P0 ^ CIN"},{"name":"S1","expression":"P1 ^ C1"},
  {"name":"S2","expression":"P2 ^ C2"},{"name":"S3","expression":"P3 ^ C3"},
  {"name":"COUT","expression":"C4"}],
 "spec":{"buses":{"A":["A3","A2","A1","A0"],"B":["B3","B2","B1","B0"],"S":["S3","S2","S1","S0"]},
         "rules":[{"out":"S","expression":"A + B + CIN"},
                  {"out":"COUT","expression":"(A + B + CIN) >> 4}]}}

Example 2 — 4x4 multiplier partial-product signal fragment:
{"signals":[
 {"name":"P00","expression":"A0 & B0"},{"name":"P10","expression":"A1 & B0"},
 {"name":"P20","expression":"A2 & B0"},{"name":"P30","expression":"A3 & B0"},
 {"name":"P01","expression":"A0 & B1"},{"name":"P11","expression":"A1 & B1"},
 {"name":"P21","expression":"A2 & B1"},{"name":"P31","expression":"A3 & B1"},
 {"name":"P02","expression":"A0 & B2"},{"name":"P12","expression":"A1 & B2"},
 {"name":"P22","expression":"A2 & B2"},{"name":"P32","expression":"A3 & B2"},
 {"name":"P03","expression":"A0 & B3"},{"name":"P13","expression":"A1 & B3"},
 {"name":"P23","expression":"A2 & B3"},{"name":"P33","expression":"A3 & B3"}]}
Continue these partial products with ordered carry-save/ripple sum and carry signals, then output Y0 through Y7. The multiplier spec uses buses A=[A3,A2,A1,A0], B=[B3,B2,B1,B0], Y=[Y7,Y6,Y5,Y4,Y3,Y2,Y1,Y0] and rule {out:Y, expression:"A * B"}.
```
## Verification performance

The largest supported input space is 10 bits (1,024 engine rows). In GitHub Actions run 44865571316, the 9-input/5-output four-bit adder plus the 8-input/8-output 4×4 multiplier built, laid out, and passed executable-spec verification in **2,611.5 ms combined** (both cases; under the required 10-second CI bound).

A separate maximum-limit layout stress case used **10 inputs, 16 outputs, and 400 gates** arranged as a 400-stage chain with side-input connections. Its layout took **109,496.9 ms** (about **109.5 seconds**) in the same CI run. This is a deliberately deep, worst-case-shaped layout measurement, not the runtime of the arithmetic examples. Both timings are environment-specific measurements, not performance guarantees.
