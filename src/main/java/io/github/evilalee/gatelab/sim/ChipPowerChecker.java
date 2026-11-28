package io.github.evilalee.gatelab.sim;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Connectivity;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Power-good diagnostics based on the real simulated values of chip boundary nets. */
public final class ChipPowerChecker {
    public record PowerIssue(
            String instanceId, String definitionName, Value vccValue, Value gndValue) {
        public PowerIssue {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(definitionName, "definitionName");
            Objects.requireNonNull(vccValue, "vccValue");
            Objects.requireNonNull(gndValue, "gndValue");
        }

        public String message() {
            return instanceId + " (" + definitionName + "): no power";
        }
    }

    private ChipPowerChecker() { }

    /** Disabled checks return no issues and never change simulator behavior. */
    public static List<PowerIssue> check(
            Schematic schematic, SchematicCompiler.SimulationResult simulation, boolean enabled) {
        Objects.requireNonNull(schematic, "schematic");
        Objects.requireNonNull(simulation, "simulation");
        if (!enabled) return List.of();

        List<PowerIssue> issues = new ArrayList<>();
        for (ComponentInstance instance : schematic.componentInstances()) {
            ComponentDefinition definition = schematic.componentDefinitionFor(instance);
            if (!definition.isChipStyle()) continue;

            Value vcc = valueOfPin(simulation, instance, definition, "VCC");
            Value gnd = valueOfPin(simulation, instance, definition, "GND");
            if (vcc != Value.ONE || gnd != Value.ZERO) {
                issues.add(new PowerIssue(instance.id(), definition.name(), vcc, gnd));
            }
        }
        return List.copyOf(issues);
    }

    public static Set<String> unpoweredInstanceIds(
            Schematic schematic, SchematicCompiler.SimulationResult simulation, boolean enabled) {
        Set<String> ids = new LinkedHashSet<>();
        for (PowerIssue issue : check(schematic, simulation, enabled)) {
            ids.add(issue.instanceId());
        }
        return Set.copyOf(ids);
    }

    public static String statusText(
            Schematic schematic, SchematicCompiler.SimulationResult simulation, boolean enabled) {
        return check(schematic, simulation, enabled).stream()
                .map(PowerIssue::message)
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static Value valueOfPin(
            SchematicCompiler.SimulationResult simulation,
            ComponentInstance instance,
            ComponentDefinition definition,
            String portName) {
        Connectivity.PinPosition position = instance.pinPositions(definition).stream()
                .filter(pin -> pin.pinId().equals(portName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "chip " + instance.definitionName() + " is missing " + portName));
        for (Connectivity.Net net : simulation.topology().nets()) {
            if (net.pins().contains(position)) {
                return simulation.netValues().getOrDefault(net, Value.X);
            }
        }
        return Value.X;
    }
}
