# Gate-level sequential parts and sequence runner

## Sequential parts

The sequential parts live in the Swing-free synth package. SequentialParts
appends ordinary Gate instances to an existing Circuit and returns the
resulting Q and QN output pins. It does not add a new engine component type or
store hidden state outside the circuit.

DLATCH is a transparent-high NAND latch:

- S_N = NAND(D, EN)
- R_N = NAND(NOT(D), EN)
- Q = NAND(S_N, QN)
- QN = NAND(R_N, Q)

When EN is ONE, D determines Q and QN. When EN is ZERO, both active-low
controls are ONE and the cross-coupled NAND gates hold the previous state.

DFF is a positive-edge master/slave pair of those latches. The master enable
is NOT(CLK), and the slave enable is CLK. The master is transparent during
the low phase; the slave is transparent during the high phase. The public Q
and QN pins are the slave latch outputs.

TFF uses D = T XOR Q and then the same DFF structure.

JKFF uses D = (J AND NOT(Q)) OR (NOT(K) AND Q) and then the same DFF
structure.

Each expansion has its own component-id prefix. Reusing a prefix in the same
circuit is rejected instead of sharing gates accidentally.

## SequenceRunner

SequenceRunner is in the Swing-free sim package. Its named input map includes
the designated clock input. At construction it resets every InputPin in the
circuit to X, sets the named clock to ZERO, and settles the initial low phase.
All other inputs therefore start unknown.

A Step assigns any subset of non-clock input names while the clock is low,
settles, executes N full high/low cycles, then samples all named outputs.
Inputs omitted from a step retain their previous values. The expected output
map can select a subset of the named outputs. A mismatch is exact three-valued
inequality, so X does not satisfy an expected ZERO or ONE.

The result contains one StepObservation per step, all mismatches, and the
first mismatch in sequence order. Each observation also reports whether any
settle in that step exhausted the simulator tick budget.

Example construction:

    SequenceRunner runner = new SequenceRunner(
        circuit,
        Map.of("D", data, "CLK", clock),
        "CLK",
        Map.of("Q", qProbe));
    SequenceRunner.SequenceResult result = runner.run(List.of(
        new SequenceRunner.Step(
            "capture zero",
            Map.of("D", Value.ZERO),
            1,
            Map.of("Q", Value.ZERO))));

No new dependencies or changes to the engine, parser, UI, gate catalogue,
SVG artwork, or schematic file format are required.
