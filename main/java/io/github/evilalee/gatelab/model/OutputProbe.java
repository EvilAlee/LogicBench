package io.github.evilalee.gatelab.model;

import java.util.Objects;

/** A readable sink that observes the value on its input pin. */
public final class OutputProbe extends Component {
    private final Pin input;
    private Value value = Value.X;

    public OutputProbe(String id) {
        super(id);
        input = createPin("in", Pin.Direction.INPUT);
    }

    public Pin input() {
        return input;
    }

    public Value value() {
        return value;
    }

    /** Updated by the simulation engine after each settle operation. */
    public void updateValue(Value value) {
        this.value = Objects.requireNonNull(value, "value");
    }
}
