# AI circuit generation and verification

## Response schema

A new model answer has these required fields:

- \`summary\`: a short description.
- \`inputs\`: every primary input exactly once, in any order.
- \`outputs\`: an array of \`{"name":"S","expression":"A ^ B"}\`. Each output becomes a pin.
  
Optional fields:

- \`signals\`: internal named expressions, each \`{"name":"C1","expression":"A & B"}\`. These compile as \`C1 := A & B\`, create no output pin, and must reference declared inputs or earlier signals only; forward references are rejected.
- instances: ordered boxed library calls, each {name: U0, component: FA, args: [A, B, CIN]}. Argument order is the definition's ordered IN-pin order. An instance may use declared inputs, earlier signals and earlier instances only. Multi-output instances require an explicit selector such as U0.S or U0.COUT; they must not be used as scalars.
  Signals and instances share one dependency-ordered namespace. The service preserves order within both arrays and interleaves their declarations by dependency. A signal may select an output pin from an earlier instance, for example `C1 := U0.COUT`; a subsequent instance may use that signal as an argument. An instance may use only inputs, already-declared signals and earlier instances. Outputs are declared after all signals and instances.
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

Library instances are available only when the dialog's **Use library components** checkbox is enabled (default on). The prompt catalog is generated from ComponentLibrary and lists combinational components with ordered input/output pins, primary output and a one-line description. DLATCH, DFF, TFF and JKFF are labelled sequential: not available yet. When the catalog exceeds 12 entries, combinational entries are filtered by component-name/description overlap; the four built-in sequential-unavailable notices remain visible. The prompt includes a worked four-FA ripple-adder example with C0..C3 signal aliases (C1 := U0.COUT, then U1 := FA(A1, B1, C1)). Turning the checkbox off omits the catalog and rejects instances.

When the user's request explicitly names a catalogued component (for example "full adder" resolves to `FA`, "half adder" to `HA`, or a user component's exact name/description), component use is a hard requirement. The service skips gate-level template shortcuts and cached answers that do not contain the requested box, sends an expressions-only structured schema with `summary`, `inputs`, `outputs`, and `instances` required, and omits the competing top-level `template` field entirely. A template-shaped or gate-only answer gets a targeted retry asking for the named boxed component. Ordinary requests retain the combined expressions/template response mode and deterministic template matching.

## Sequential response schema and verification

The generation service selects the `sequential` answer kind before the provider call when the
request mentions memory, counter, register, accumulator, shift register, state machine,
flip-flop, or latch. It uses a separate strict provider schema. Every sequential answer
must contain `kind`, `summary`, `clock`, `inputs`, `outputs`, `signals`,
`instances`, and `sequence`; additional fields are rejected. This schema is not a hybrid
with the expressions/template schema. All non-sequential requests retain the existing
schema and generation pipeline.

`clock` names one Clock source. It is not a primary input, cannot be assigned by an
expression, and every DFF `CLK` argument must use that exact identifier. Sequence steps
have the exact shape `{set, cycles, expect}`: `set` assigns non-clock inputs,
`cycles` is 0..8, and `expect` assigns at least one output. Values are numeric 0 or 1;
X is never a valid expectation. The sequence has 1..40 steps.

Only DFF and DLATCH are accepted as sequential boxes. TFF and JKFF are rejected because
their state cannot be forced to a known value from the simulator's all-X start. A toggle
is expressed with a DFF, using `D = Q XOR T` and reset gating, for example
`D = RESET_N & (Q ^ T)`. Each DFF/DLATCH D path must depend on a declared reset input.
The first step must assign every non-clock input, hold reset at its active level (low for
RESET_N/RST_N/CLEAR_N/CLR_N, high for RESET/RST/CLEAR/CLR), and run at least one full
cycle. The first reset observation is checked for unknown outputs as well as for the
answer's expected values.

Signals and component instances share an unrestricted dependency namespace, so feedback
may refer to an instance's own output or to later signals/instances. A dependency-graph
check rejects any combinational cycle that does not pass through a DFF or DLATCH. The
component-aware compiler builds the boxed schematic, SchematicCompiler flattens it, and
SequenceRunner starts every non-clock input/state at X with the named clock low. It applies
each step, executes complete high/low clock cycles, and compares expected with observed
values by output name. X never matches 0 or 1. Retry feedback is named and capped at eight
lines; a verified answer is labelled `VERIFIED: matches the sequence in the answer`.

Sequential limits are 8 non-clock inputs, 8 outputs, 16 instances, 400 flattened gates,
40 sequence steps and 8 cycles per step. Verified sequential answers use the normal
AiCache description normalisation and are revalidated through the same sequential schema,
component checks, reset checks and simulator on replay. A cache entry that refers to a
component no longer present in the library is ignored with a notice.

The dialog displays a sequence table with Step, Inputs, Cycles, Expected and Observed
columns. Expected/observed cells for mismatching rows are highlighted. Placing a verified
result uses the existing generated-schematic replacement action as one undoable change;
the Clock source is included in the schematic.

The sequential prompt is appended after the existing combinational system prompt. It
contains worked JSON examples for a 2-bit DFF counter (reset plus four count cycles) and
a 4-bit accumulator built from four FA boxes and four DFF boxes (reset, add 0011, then
add 0101).

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
| Boxed component instances | 32 |
| Gates after flattening | 400 |
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

1. Validate JSON shape and all size limits. When component use is enabled, instances is optional and limited to 32. The legacy truthTable property is ignored. Invalid-field feedback names allowed fields, fields sent, missing/unexpected fields and the expected expressions-answer shape.
2. Enforce the 300-character expression/argument limit, 160-signal limit and 3,000-character total expression text. Signals and instances share one ordered namespace. Instance arguments may use declared inputs, earlier signals and earlier instances only. Unknown components, sequential calls, wrong argument counts, scalar use of multi-output components and unknown output pins receive named feedback.
3. Confirm that the declared primary inputs match the variables used by the answer. A misspelled identifier that becomes an undeclared input is named in the error.
4. For boxed answers, compile through ComponentExpressionCompiler with BOXED_INSTANCES and raw feedback disabled. Flatten the generated schematic through SchematicCompiler, then enforce the 400-gate limit on the flattened engine circuit. Gate-only answers retain the existing expression compiler and DAG layout path.
5. Enumerate every input combination using the executable engine. An X output or oscillation fails the attempt. Boxed-instance geometry/connectivity comes from the component-aware compiler. Disconnected declared inputs, constant outputs and unused internal signals are warnings.
6. Check the user's expected rows, when present.
7. Parse and execute the spec, when present, across every input combination.

## Status meanings

- **VERIFIED** means the built circuit agrees with an executable spec or the user's expected rows, and every independent check that was supplied passed. It is not proof that the model understood the user's intent.
- **BUILT** means JSON, parsing, build, placement, geometry and structural engine checks passed, but neither an executable spec nor user rows were supplied. Read the generated truth table before relying on it.
- **UNVERIFIED** means one or more validation checks failed after the available attempts. The best attempt is retained for review and requires the normal confirmation before placement.
- **FROM CACHE** is the result-source badge when a cached answer is replayed. A cache answer goes through the same current validation path; a legacy entry with only a claimed table is at most BUILT, never VERIFIED from that table.
- **UNAVAILABLE** means no usable live answer or valid cache answer is available. There is no placeable circuit.

Normal generation checks the normalized user cache first and then the bundled cache, after the direct-expression shortcut and before making a provider request. Every hit is revalidated through the current validation pipeline; an invalid hit is ignored and live generation continues. The dialog's Regenerate action bypasses cache lookup and cache fallback. Only a live answer that passes an independent check replaces the user cache, and its model name is stored alongside the answer.

Newly stored answers use the new schema and do not include `truthTable`. Versioned user-cache entries store the validated answer and producing model. Earlier unwrapped user-cache entries remain readable and are revalidated under the same rules; their original model is unknown. The six bundled demonstration prompts remain old-format fixtures and are also revalidated, so they show FROM CACHE and BUILT unless independent rows/spec are later added.

## Raw provider-answer diagnostics

The dialog's **Copy last raw answer** action copies the most recent provider answer after configured API-key redaction. Provider-answer text is also appended to ai_debug.log in the AI config directory. The log stores no prompt or API key, caps each UTF-8 entry at 20 KB, and rotates ai_debug.log to ai_debug.log.1 when appending would exceed 1 MB. File-write errors do not fail generation.

Gemini structured output sends the response schema using responseFormat.text.mimeType/schema first. If Gemini rejects responseFormat, the provider retries using responseMimeType plus responseJsonSchema; if that is rejected too, it retries with JSON MIME type and no schema. The top-level template field is part of the AI service response schema and prompt contract; it does not come from the user's circuit description. An exact TemplateMatcher hit may be built locally without a model call, but the model may also return kind:"template" and name any supported TemplateGenerator for broader descriptions. A bare template field without kind, or a template response when template answers are disabled, is reported as a normal retryable validation failure with allowed fields, received fields and the expected expressions-answer shape.

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

The following is the static base instruction sent by `AiCircuitService.systemPrompt()`:

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
