package io.github.evilalee.gatelab.synth.layout;

import io.github.evilalee.gatelab.model.Facing;
import io.github.evilalee.gatelab.model.GateDef;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.SchematicSnapshot;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.SchematicNetlistExtractor;
import io.github.evilalee.gatelab.synth.FeedbackCircuitExtractor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Extracts, re-lays out and engine-verifies an existing schematic without Swing. */
public final class TidyCircuitService {
    public static final int MAX_EXHAUSTIVE_INPUTS = 12;

    public record TidyResult(
            Schematic schematic,
            int inputCount,
            int verificationRows,
            boolean equivalenceSkipped,
            boolean feedbackCircuit) {
        public TidyResult {
            Objects.requireNonNull(schematic, "schematic");
            if (inputCount < 0 || verificationRows < 0) {
                throw new IllegalArgumentException("verification counts must not be negative");
            }
            if (feedbackCircuit) {
                if (verificationRows != 0 || equivalenceSkipped) {
                    throw new IllegalArgumentException(
                            "feedback circuits do not run combinational equivalence");
                }
            } else if (equivalenceSkipped
                    != (verificationRows == 0 && inputCount > MAX_EXHAUSTIVE_INPUTS)) {
                throw new IllegalArgumentException("inconsistent equivalence status");
            }
        }

        public TidyResult(
                Schematic schematic, int inputCount,
                int verificationRows, boolean equivalenceSkipped) {
            this(schematic, inputCount, verificationRows, equivalenceSkipped, false);
        }

        public String verificationMessage() {
            if (feedbackCircuit) {
                return "Tidied. Feedback circuit: connections verified, behaviour not simulated. "
                        + "Gate output state restarted at X.";
            }
            return equivalenceSkipped
                    ? "Equivalence check skipped (" + inputCount
                            + " inputs exceeds the limit of " + MAX_EXHAUSTIVE_INPUTS + ")."
                    : "Equivalent for all " + verificationRows + " input combinations.";
        }
    }

    private TidyCircuitService() {
    }

    public static TidyResult tidy(Schematic original, Collection<GateDef> definitions) {
        Objects.requireNonNull(original, "original");
        if (!original.componentInstances().isEmpty()) {
            throw new IllegalArgumentException(
                    "Tidy circuit does not support component instances.");
        }
        List<GateDef> library = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        if (original.components().stream().noneMatch(component ->
                !component.typeId().equals("input")
                        && !component.typeId().equals("output")
                        && !component.typeId().equals("constant")
                        && !component.typeId().equals("vcc")
                        && !component.typeId().equals("gnd"))) {
            throw new IllegalArgumentException("Tidy circuit is available when the schematic has a gate.");
        }

        if (FeedbackCircuitExtractor.hasFeedback(original)) {
            FeedbackCircuitExtractor.Extraction feedback =
                    FeedbackCircuitExtractor.extract(original).orElseThrow();
            return tidyFeedback(original, library, feedback);
        }

        SchematicNetlistExtractor.Extraction extraction =
                SchematicNetlistExtractor.extract(original);
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(library).layout(extraction.netlist());
        Schematic tidied = preserveLabelsAndInputValues(
                original, extraction, layout, library);

        int inputCount = extraction.netlist().inputs().size();
        if (inputCount > MAX_EXHAUSTIVE_INPUTS) {
            return new TidyResult(tidied, inputCount, 0, true);
        }

        verifyEquivalent(original, tidied, extraction, layout, library);
        return new TidyResult(tidied, inputCount, 1 << inputCount, false);
    }

    private static TidyResult tidyFeedback(
            Schematic original,
            List<GateDef> definitions,
            FeedbackCircuitExtractor.Extraction extraction) {
        SchematicLayout.LayoutResult layout =
                new SchematicLayout(definitions).layoutFeedback(extraction);
        Map<String, PlacedComponent> oldById = new HashMap<>();
        for (PlacedComponent component : original.components()) {
            oldById.put(component.id(), component);
        }

        Map<String, String> newToOldIds = new LinkedHashMap<>();
        Map<String, String> labelsByNewId = new HashMap<>();
        extraction.inputComponentIds().forEach((signal, oldId) -> {
            String newId = layout.inputComponentIds().get(signal);
            newToOldIds.put(newId, oldId);
            labelsByNewId.put(newId, oldById.get(oldId).label());
        });
        extraction.clockComponentIds().forEach((signal, oldId) -> {
            String newId = layout.clockComponentIds().get(signal);
            newToOldIds.put(newId, oldId);
            labelsByNewId.put(newId, oldById.get(oldId).label());
        });
        extraction.outputComponentIds().forEach((signal, oldId) -> {
            String newId = layout.outputComponentIds().get(signal);
            newToOldIds.put(newId, oldId);
            labelsByNewId.put(newId, oldById.get(oldId).label());
        });
        extraction.gateComponentIds().forEach((signal, oldId) -> {
            String newId = layout.gateComponentIds().get(signal);
            newToOldIds.put(newId, oldId);
        });
        extraction.constantComponentIds().forEach((signal, oldId) -> {
            String newId = layout.constantComponentIds().get(signal);
            newToOldIds.put(newId, oldId);
            labelsByNewId.put(newId, oldById.get(oldId).label());
        });

        List<PlacedComponent> components = new ArrayList<>();
        for (PlacedComponent component : layout.schematic().components()) {
            components.add(new PlacedComponent(
                    component.id(), component.typeId(), component.x(), component.y(),
                    Facing.EAST, component.attributes(),
                    labelsByNewId.getOrDefault(component.id(), component.label())));
        }

        Schematic replacement = new Schematic(definitions);
        replacement.restoreSnapshot(new SchematicSnapshot(components, layout.schematic().wires()));
        replacement.setUndefinedInputMode(original.undefinedInputMode());
        extraction.inputComponentIds().forEach((signal, oldId) ->
                replacement.setInputValue(
                        layout.inputComponentIds().get(signal), original.inputValue(oldId)));
        extraction.clockComponentIds().forEach((signal, oldId) ->
                replacement.setClockValue(
                        layout.clockComponentIds().get(signal), original.clockValue(oldId)));

        FeedbackConnectivityVerifier.verify(original, replacement, newToOldIds);
        int primarySourceCount = extraction.inputComponentIds().size()
                + extraction.clockComponentIds().size();
        return new TidyResult(replacement, primarySourceCount, 0, false, true);
    }

    private static Schematic preserveLabelsAndInputValues(
            Schematic original,
            SchematicNetlistExtractor.Extraction extraction,
            SchematicLayout.LayoutResult layout,
            List<GateDef> definitions) {
        Map<String, PlacedComponent> oldById = new HashMap<>();
        for (PlacedComponent component : original.components()) {
            oldById.put(component.id(), component);
        }

        Map<String, String> labelsByNewId = new HashMap<>();
        extraction.inputComponentIds().forEach((name, oldId) -> {
            String newId = layout.inputComponentIds().get(name);
            labelsByNewId.put(newId, oldById.get(oldId).label());
        });
        extraction.outputComponentIds().forEach((name, oldId) -> {
            String newId = layout.outputComponentIds().get(name);
            labelsByNewId.put(newId, oldById.get(oldId).label());
        });
        original.components().stream()
                .filter(PlacedComponent::isFixedSource)
                .sorted(Comparator.comparing(PlacedComponent::id))
                .forEach(source -> {
                    String literal = source.fixedSourceValue()
                            == io.github.evilalee.gatelab.model.Value.ONE ? "1" : "0";
                    String newId = layout.constantComponentIds().get(literal);
                    if (newId != null) {
                        labelsByNewId.putIfAbsent(newId, source.label());
                    }
                });

        List<PlacedComponent> components = new ArrayList<>();
        for (PlacedComponent component : layout.schematic().components()) {
            components.add(new PlacedComponent(
                    component.id(), component.typeId(), component.x(), component.y(),
                    Facing.EAST, component.attributes(),
                    labelsByNewId.getOrDefault(component.id(), component.label())));
        }

        Schematic replacement = new Schematic(definitions);
        replacement.restoreSnapshot(new SchematicSnapshot(components, layout.schematic().wires()));
        replacement.setUndefinedInputMode(original.undefinedInputMode());
        extraction.inputComponentIds().forEach((name, oldId) ->
                replacement.setInputValue(
                        layout.inputComponentIds().get(name), original.inputValue(oldId)));
        return replacement;
    }

    private static void verifyEquivalent(
            Schematic original,
            Schematic tidied,
            SchematicNetlistExtractor.Extraction extraction,
            SchematicLayout.LayoutResult layout,
            List<GateDef> definitions) {
        List<String> inputNames = extraction.netlist().inputs();
        Map<String, String> oldOutputIds = extraction.outputComponentIds();
        Map<String, String> newOutputIds = layout.outputComponentIds();

        Schematic before = copy(original, definitions);
        Schematic after = copy(tidied, definitions);
        int rowCount = 1 << inputNames.size();
        for (int row = 0; row < rowCount; row++) {
            for (int index = 0; index < inputNames.size(); index++) {
                int shift = inputNames.size() - 1 - index;
                boolean high = ((row >>> shift) & 1) != 0;
                String name = inputNames.get(index);
                before.setInputValue(extraction.inputComponentIds().get(name), high);
                after.setInputValue(layout.inputComponentIds().get(name), high);
            }

            SchematicCompiler.SimulationResult oldResult =
                    SchematicCompiler.compileAndSettle(before);
            SchematicCompiler.SimulationResult newResult =
                    SchematicCompiler.compileAndSettle(after);
            if (oldResult.oscillating() || newResult.oscillating()) {
                throw new IllegalStateException(
                        "Tidy circuit equivalence check oscillated on input row " + row + ".");
            }
            for (String name : oldOutputIds.keySet()) {
                Value oldValue = oldResult.outputValues().get(oldOutputIds.get(name));
                Value newValue = newResult.outputValues().get(newOutputIds.get(name));
                if (oldValue != newValue) {
                    throw new IllegalStateException(
                            "Tidy circuit equivalence check failed on row " + row
                                    + " for output " + name + ": before=" + oldValue
                                    + ", after=" + newValue + ".");
                }
            }
        }
    }

    private static Schematic copy(Schematic source, List<GateDef> definitions) {
        Schematic copy = new Schematic(definitions);
        copy.restoreSnapshot(SchematicSnapshot.capture(source));
        copy.setUndefinedInputMode(source.undefinedInputMode());
        for (PlacedComponent component : source.components()) {
            if (component.typeId().equals("input")) {
                copy.setInputValue(component.id(), source.inputValue(component.id()));
            }
        }
        return copy;
    }

}
