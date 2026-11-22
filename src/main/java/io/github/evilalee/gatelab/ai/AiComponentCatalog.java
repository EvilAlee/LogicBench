package io.github.evilalee.gatelab.ai;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.Gate;
import io.github.evilalee.gatelab.model.Pin;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.sim.Simulator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates the request-scoped, combinational component catalog shown to the AI. */
public final class AiComponentCatalog {
    private record Entry(String line, String searchableText) { }
    private static final Set<String> SEQUENTIAL_BUILT_INS =
            Set.of("DLATCH", "DFF", "TFF", "JKFF");
    private static final int FILTER_THRESHOLD = 12;

    private static final Set<String> DESCRIPTION_NOISE = Set.of(
            "a", "an", "and", "basic", "built", "circuit", "combinational",
            "component", "from", "in", "library", "logic", "the", "user", "with");

    private AiComponentCatalog() {
    }

    /**
     * Finds library blocks explicitly named by the request. Exact component
     * identifiers take priority; otherwise meaningful description phrases such
     * as "full adder" resolve to their most specific matching library entry.
     */
    public static List<String> explicitlyRequestedComponents(
            Collection<ComponentDefinition> definitions, String request) {
        Objects.requireNonNull(definitions, "definitions");
        String normalizedRequest = normalizeWords(Objects.requireNonNull(request, "request"));
        if (normalizedRequest.isBlank()) return List.of();

        List<ComponentDefinition> ordered = definitions.stream()
                .filter(AiComponentCatalog::isCombinational)
                .sorted(java.util.Comparator.comparing(ComponentDefinition::name))
                .toList();
        List<String> exactNames = ordered.stream()
                .filter(definition -> containsPhrase(normalizedRequest,
                        normalizeWords(definition.name().replace('_', ' '))))
                .map(ComponentDefinition::name).toList();
        if (!exactNames.isEmpty()) return exactNames;

        Map<String, Integer> matchScores = new HashMap<>();
        int bestScore = 0;
        for (ComponentDefinition definition : ordered) {
            int score = descriptionMatchScore(definition.description(), normalizedRequest);
            if (score > 0) {
                matchScores.put(definition.name(), score);
                bestScore = Math.max(bestScore, score);
            }
        }
        if (bestScore == 0) return List.of();

        int selectedScore = bestScore;
        List<ComponentDefinition> bestMatches = ordered.stream()
                .filter(definition -> matchScores.getOrDefault(definition.name(), 0) == selectedScore)
                .toList();
        boolean hasBuiltInMatch = bestMatches.stream()
                .anyMatch(definition -> !definition.category().equalsIgnoreCase("User"));
        if (hasBuiltInMatch) {
            bestMatches = bestMatches.stream()
                    .filter(definition -> !definition.category().equalsIgnoreCase("User"))
                    .toList();
        }
        return bestMatches.stream().map(ComponentDefinition::name).toList();
    }

    private static int descriptionMatchScore(String description, String normalizedRequest) {
        List<String> words = java.util.Arrays.stream(normalizeWords(description).split(" "))
                .filter(word -> !word.isBlank() && !DESCRIPTION_NOISE.contains(word))
                .toList();
        int best = 0;
        for (int length = Math.min(5, words.size()); length >= 2; length--) {
            for (int start = 0; start + length <= words.size(); start++) {
                String phrase = String.join(" ", words.subList(start, start + length));
                if (containsPhrase(normalizedRequest, phrase)) best = Math.max(best, length);
            }
        }
        return best;
    }

    private static String normalizeWords(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim().replaceAll("\\s+", " ");
    }

    private static boolean containsPhrase(String text, String phrase) {
        return !phrase.isBlank() && (" " + text + " ").contains(" " + phrase + " ");
    }

    public static String promptBlock(
            Collection<ComponentDefinition> definitions, String request) {
        Objects.requireNonNull(definitions, "definitions");
        Objects.requireNonNull(request, "request");
        List<ComponentDefinition> ordered = definitions.stream()
                .sorted(java.util.Comparator.comparing(ComponentDefinition::name))
                .toList();
        List<Entry> entries = new ArrayList<>();
        for (ComponentDefinition definition : ordered) {
            if (isSequential(definition)) {
                if (SEQUENTIAL_BUILT_INS.contains(definition.name())) {
                    entries.add(new Entry(definition.name() + " — sequential: not available yet"
                            + " (inputs: " + names(definition.inputPorts())
                            + "; outputs: " + names(definition.outputPorts()) + ")",
                            definition.name() + " " + definition.description()));
                }
                continue;
            }
            if (!isCombinational(definition)) continue;
            String primary = definition.primaryOutput() == null
                    ? "none" : definition.primaryOutput();
            String description = oneLine(definition.description());
            entries.add(new Entry(definition.name() + " | IN: " + names(definition.inputPorts())
                    + " | OUT: " + names(definition.outputPorts())
                    + " | primaryOutput: " + primary + " | " + description,
                    definition.name() + " " + description));
        }
        if (entries.size() > FILTER_THRESHOLD) {
            Set<String> terms = terms(request);
            entries = entries.stream()
                    .filter(entry -> entry.line().contains("sequential: not available yet")
                            || overlaps(entry.searchableText(), terms))
                    .toList();
        }
        if (entries.isEmpty()) return "Library component catalog: no matching combinational components.";
        return "Library component catalog (arguments bind to ordered IN pins; outputs use instance.PIN):\n"
                + String.join("\n", entries.stream().map(Entry::line).toList());
    }

    public static List<ComponentDefinition> combinational(
            Collection<ComponentDefinition> definitions, String request) {
        String block = promptBlock(definitions, request);
        Set<String> allowedNames = new LinkedHashSet<>();
        for (ComponentDefinition definition : definitions) {
            if (!isSequential(definition) && isCombinational(definition)
                    && block.contains(definition.name() + " | IN:")) {
                allowedNames.add(definition.name());
            }
        }
        return definitions.stream().filter(definition -> allowedNames.contains(definition.name()))
                .sorted(java.util.Comparator.comparing(ComponentDefinition::name)).toList();
    }

    public static boolean isSequential(ComponentDefinition definition) {
        if (definition.category().toLowerCase(Locale.ROOT).contains("sequential")
                || definition.inputPorts().stream().anyMatch(ComponentDefinition.Port::isClock)) {
            return true;
        }
        return containsSequentialNested(definition.internalCircuit(), new HashSet<>());
    }

    public static boolean isCombinational(ComponentDefinition definition) {
        if (isSequential(definition)) return false;
        try {
            return !hasFeedback(definition.internalCircuit());
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    private static boolean containsSequentialNested(
            Schematic schematic, Set<String> visited) {
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("clock")) return true;
        }
        for (ComponentInstance instance : schematic.componentInstances()) {
            if (!visited.add(instance.definitionHash())) continue;
            ComponentDefinition nested = schematic.componentDefinitionFor(instance);
            if (nested.category().toLowerCase(Locale.ROOT).contains("sequential")
                    || nested.inputPorts().stream().anyMatch(ComponentDefinition.Port::isClock)
                    || containsSequentialNested(nested.internalCircuit(), visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFeedback(Schematic schematic) {
        SchematicCompiler.CompiledCircuit compiled = SchematicCompiler.compileCircuit(schematic);
        List<Gate> gates = compiled.circuit().components().stream()
                .filter(Gate.class::isInstance).map(Gate.class::cast).toList();
        Simulator simulator = new Simulator(compiled.circuit());
        Map<Gate, Set<Gate>> dependencies = new HashMap<>();
        for (Gate gate : gates) {
            Set<Gate> sources = new LinkedHashSet<>();
            for (Pin input : gate.inputs()) {
                for (Pin pin : simulator.getNetPins(input)) {
                    if (pin.id().equals("out") && pin.component() instanceof Gate source) {
                        sources.add(source);
                    }
                }
            }
            dependencies.put(gate, sources);
        }
        Map<Gate, Integer> state = new HashMap<>();
        for (Gate gate : gates) {
            if (cyclic(gate, dependencies, state)) return true;
        }
        return false;
    }

    private static boolean cyclic(
            Gate gate, Map<Gate, Set<Gate>> dependencies, Map<Gate, Integer> state) {
        int mark = state.getOrDefault(gate, 0);
        if (mark == 1) return true;
        if (mark == 2) return false;
        state.put(gate, 1);
        for (Gate dependency : dependencies.getOrDefault(gate, Set.of())) {
            if (cyclic(dependency, dependencies, state)) return true;
        }
        state.put(gate, 2);
        return false;
    }

    private static String names(List<ComponentDefinition.Port> ports) {
        return String.join(", ", ports.stream().map(ComponentDefinition.Port::name).toList());
    }

    private static String oneLine(String description) {
        String text = description == null ? "" : description.replaceAll("[\\r\\n]+", " ").trim();
        return text.isBlank() ? "Description unavailable." : text;
    }

    private static Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() >= 3) result.add(word);
        }
        return result;
    }

    private static boolean overlaps(String entry, Set<String> terms) {
        if (terms.isEmpty()) return false;
        Set<String> catalogTerms = terms(entry);
        return terms.stream().anyMatch(catalogTerms::contains);
    }
}
