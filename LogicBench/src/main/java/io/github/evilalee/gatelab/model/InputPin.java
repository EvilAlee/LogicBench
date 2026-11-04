package io.github.evilalee.gatelab.model;

import java.util.Objects;

/** An externally controlled source that drives one output pin. */
public final class InputPin extends Component {
    private final Pin output;
    private Value value = Value.X;

    public InputPin(String id) {
        super(id);
        output = createPin("out", Pin.Direction.OUTPUT);
    }

    public Pin output() {
        return output;
    }

    public Value value() {
        return value;
    }

    /** Called by the simulator API to change this external source. */
    public void setValue(Value value) {
        this.value = Objects.requireNonNull(value, "value");
    }
}
