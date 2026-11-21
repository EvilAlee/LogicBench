# Deterministic circuit generators

This document defines the headless generator API in
src/main/java/io/github/evilalee/gatelab/synth/generators/. A generator has a stable
id, an explicit parameter domain, and build(params). It returns a GeneratedCircuit
whose JSON has the same summary, inputs, outputs, ordered signals, and executable
spec fields accepted by the AI answer pipeline. Internal definitions are emitted before
outputs and use the existing name := expression syntax. The generated source is compiled,
laid out, and checked by the same production pipeline; the integer spec is evaluated by
our SpecEvaluator, never trusted from a model claim.

## Supported generators

| Generator id | Parameters | Input pins | Output pins |
|---|---|---|---|
| ripple_adder | n=1..4, carryIn=yes/no | A0..A(n-1), B0..B(n-1), optional CIN | S0..S(n-1), COUT |
| subtractor | n=1..4 | A0..A(n-1), B0..B(n-1) | D0..D(n-1), BOUT |
| comparator | n=1..4 | A0..A(n-1), B0..B(n-1) | LT, EQ, GT |
| array_multiplier | n=1..4, m=1..4 | A0..A(n-1), B0..B(m-1) | P0..P(n+m-1) |
| mux | k=1..2 | D0..D(2^k-1), S0..S(k-1) | Y |
| decoder | n=1..3, enable=yes/no | A0..A(n-1), optional EN | Y0..Y(2^n-1) |
| parity | n=2..8, mode=even/odd | A0..A(n-1) | PARITY |
| majority | n=3 or 5 | A0..A(n-1) | Y |
| gray_to_binary | n=2..4 | G0..G(n-1) | B0..B(n-1) |
| binary_to_gray | n=2..4 | B0..B(n-1) | G0..G(n-1) |

The index zero is always the least-significant bit. For executable specs, multi-bit
buses are listed MSB first: for example, A is [A3,A2,A1,A0]. The input pin order
used for generated expression text is LSB-first (A0 upward); the evaluator binds by
pin name, not by relying on that declaration order.

## Circuit conventions

- The adder is unsigned ripple carry. CIN is present only for carryIn=yes;
  COUT is the carry beyond bit n-1.
- The subtractor computes D = A - B modulo 2^n; BOUT=1 exactly when A < B.
- The comparator is unsigned and mutually exclusive: exactly one of LT, EQ, GT
  is high.
- The multiplier emits every partial product A_i & B_j, then adds shifted rows
  through ordered propagate, generate, sum, and carry signals.
- The mux uses S0 as the least-significant select bit. It is an ordinary active-high
  selector with Y = D[S].
- The decoder is active-high; when enabled, exactly Y_A is high. With enable=no,
  no EN pin is present and the decoder is always enabled.
- Even parity sets PARITY=1 when the number of high inputs is even; odd parity sets
  it when that count is odd.
- Majority asserts when at least half-plus-one inputs are high.
- Gray conversion uses the standard binary-reflected Gray code.

## Shared limits and the 8:1 mux conflict

Generators stay within the merged AI limits: 10 primary inputs, 16 outputs, 160
signals, 300 characters per expression, 3,000 expression characters, and 400 compiled
gates. All current combinations are exhaustively engine-checked by tests against
their integer specs. The 4-bit adder and 4x4 multiplier are additionally layout- and
placed-geometry-verified.

An 8:1 mux with individual single-bit data/select pins needs eleven inputs:
eight data pins plus three select pins. That cannot fit the existing hard
MAX_INPUTS=10 limit. This implementation therefore exposes mux k=1..2 only;
requesting k=3 is rejected with the supported range instead of increasing the
global cap or returning a circuit that the pipeline would reject. Supporting 8:1
requires a separate product decision to raise the input limit or change the pin
interface; neither is made here.

No engine, gates.json, SVG, schematic file field, or Swing code is changed by
the generator package.
