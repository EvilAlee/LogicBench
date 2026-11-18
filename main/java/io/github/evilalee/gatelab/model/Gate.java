package io.github.evilalee.gatelab.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A combinational logic gate with one output and one to eight inputs. */
public final class Gate extends Component {
    private final GateType type;
    private final List<Pin> inputs;
    private final Pin output;

    public Gate(String id, GateType type, int inputCount) {
        super(id);
        this.type = Objects.requireNonNull(type, "type");
        if (!type.acceptsInputCount(inputCount)) {
            throw new IllegalArgumentException(
                    type + " does not accept " + inputCount + " inputs");
        }

        List<Pin> createdInputs = new ArrayList<>(inputCount);
        for (int index = 0; index < inputCount; index++) {
            createdInputs.add(createPin("in" + index, Pin.Direction.INPUT));
        }
        this.inputs = List.copyOf(createdInputs);
        this.output = createPin("out", Pin.Direction.OUTPUT);
    }

    public GateType type() {
        return type;
    }

    public List<Pin> inputs() {
        return inputs;
    }

    public Pin input(int index) {
        return inputs.get(index);
    }

    public Pin output() {
        return output;
    }

    public Value evaluate(List<Value> inputValues) {
        return type.evaluate(inputValues);
    }
}
