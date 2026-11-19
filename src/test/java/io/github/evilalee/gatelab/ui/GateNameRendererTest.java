package io.github.evilalee.gatelab.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GateNameRendererTest {
    @Test
    void returnsTheCompactNameForEveryBuiltInGateType() {
        assertEquals("AND", GateNameRenderer.textForType("and"));
        assertEquals("OR", GateNameRenderer.textForType("or"));
        assertEquals("NOT", GateNameRenderer.textForType("not"));
        assertEquals("NAND", GateNameRenderer.textForType("nand"));
        assertEquals("NOR", GateNameRenderer.textForType("nor"));
        assertEquals("XOR", GateNameRenderer.textForType("xor"));
        assertEquals("XNOR", GateNameRenderer.textForType("xnor"));
        assertEquals("BUF", GateNameRenderer.textForType("buffer"));
    }

    @Test
    void nonGateComponentsAndUnknownTypesHaveNoGateName() {
        assertEquals("", GateNameRenderer.textForType("input"));
        assertEquals("", GateNameRenderer.textForType("output"));
        assertEquals("", GateNameRenderer.textForType("unknown"));
        assertEquals("", GateNameRenderer.textForType(null));
    }
}
