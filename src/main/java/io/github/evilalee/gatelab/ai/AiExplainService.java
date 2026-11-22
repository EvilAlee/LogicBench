package io.github.evilalee.gatelab.ai;

import io.github.evilalee.gatelab.model.ComponentDefinition;
import io.github.evilalee.gatelab.model.ComponentInstance;
import io.github.evilalee.gatelab.model.PlacedComponent;
import io.github.evilalee.gatelab.model.Schematic;
import io.github.evilalee.gatelab.model.Value;
import io.github.evilalee.gatelab.sim.SchematicCompiler;
import io.github.evilalee.gatelab.synth.CircuitDescriber;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds an exact, engine-derived explanation request from the currently open schematic. */
public final class AiExplainService {
    public static final String DEFAULT_QUESTION = "What does this circuit do?";

    public static final String SYSTEM_PROMPT = """
            Explain the supplied digital logic circuit using only the circuit text and engine facts.
            If something cannot be determined from them, say so. Do not claim behaviour that the facts do not show.
            Do not invent timing, electrical properties, component capabilities, or signal values.
            The circuit text may contain reusable component calls and output selectors; explain only what their names and connections establish.
            The engine facts are authoritative for counts, ports, feedback classification, and any displayed truth-table rows.
            Reply with JSON only, shaped as {"summary":"string","steps":["string"],"notable":["string"]}.
            summary is required. steps is a required ordered list of concise explanation steps. notable is optional.
            Do not include markdown fences or fields other than summary, steps, and optional notable.
            """;

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?m)^\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(?::=|=)\\s*(.*?)\\s*$");
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]*)(?:\\.[A-Za-z][A-Za-z0-9_]*)?(?![A-Za-z0-9_])");

    public record TruthRow(String inputs, String outputs) {
        public TruthRow {
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(outputs, "outputs");
        }
    }

    public record EngineFacts(
            int gateCount,
            int instanceCount,
            List<String> inputNames,
            List<String> outputNames,
            boolean containsFeedback,
            boolean sequential,
            boolean sampled,
            BigInteger totalRows,
            List<TruthRow> truthRows) {
        public EngineFacts {
            inputNames = List.copyOf(Objects.requireNonNull(inputNames, "inputNames"));
            outputNames = List.copyOf(Objects.requireNonNull(outputNames, "outputNames"));
            Objects.requireNonNull(totalRows, "totalRows");
            truthRows = List.copyOf(Objects.requireNonNull(truthRows, "truthRows"));
        }

        public String format() {
            StringBuilder text = new StringBuilder();
            text.append("Gate count: ").append(gateCount).append('\n');
            text.append("Instance count: ").append(instanceCount).append('\n');
            text.append("Inputs: ").append(inputNames).append('\n');
            text.append("Outputs: ").append(outputNames).append('\n');
            text.append("Feedback exists: ").append(containsFeedback ? "yes" : "no").append('\n');
            text.append("Sequential elements: ").append(sequential ? "yes" : "no");
            if (containsFeedback || sequential) {
                text.append("\ncontains feedback; truth table not shown");
                return text.toString();
            }
            text.append('\n');
            if (sampled) {
                text.append("Truth table: 64 evenly spread sampled rows from ")
                        .append(totalRows).append(" possible rows\n");
            } else {
                text.append("Truth table: all ").append(totalRows).append(" rows\n");
            }
            text.append(String.join(" | ", inputNames));
            if (!inputNames.isEmpty()) text.append(" | ");
            text.append(String.join(" | ", outputNames)).append('\n');
            for (TruthRow row : truthRows) {
                appendBits(text, row.inputs());
                if (!inputNames.isEmpty()) text.append(" | ");
                appendBits(text, row.outputs());
                text.append('\n');
            }
            return text.toString().stripTrailing();
        }

        private static void appendBits(StringBuilder target, String bits) {
            for (int index = 0; index < bits.length(); index++) {
                if (index > 0) target.append(" | ");
                target.append(bits.charAt(index));
            }
        }
    }

    public record PreparedRequest(
            String systemPrompt,
            String requestText,
            String circuitText,
            EngineFacts facts) {
        public PreparedRequest {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(requestText, "requestText");
            Objects.requireNonNull(circuitText, "circuitText");
            Objects.requireNonNull(facts, "facts");
        }

        /** The exact system prompt and user message passed to the provider, in send order. */
        public String previewText() {
            return "SYSTEM PROMPT\n" + systemPrompt
                    + "\n\nUSER MESSAGE\n" + requestText;
        }
    }

    private record NamedSource(String id, String name) {
    }

    private AiExplainService() {
    }

    public static PreparedRequest prepare(Schematic schematic, String question) {
        Objects.requireNonNull(schematic, "schematic");
        String safeQuestion = question == null || question.isBlank()
                ? DEFAULT_QUESTION : question.trim();

        // CircuitDescriber owns the canonical export and its 400-gate limit.
        CircuitDescriber.Description description = CircuitDescriber.describe(schematic);
        List<NamedSource> inputs = namedSources(schematic, description, true);
        List<NamedSource> outputs = namedSources(schematic, description, false);
        int gates = countGatesForExport(schematic);
        boolean feedback = hasAssignmentCycle(description.text());
        boolean sequential = containsSequentialElement(schematic);

        EngineFacts facts = buildFacts(
                schematic, gates, inputs, outputs, feedback, sequential);
        String request = "Question:\n" + safeQuestion
                + "\n\nCircuit text:\n" + description.text()
                + "\n\nEngine facts:\n" + facts.format();
        return new PreparedRequest(SYSTEM_PROMPT, request, description.text(), facts);
    }

    private static List<NamedSource> namedSources(
            Schematic schematic, CircuitDescriber.Description description, boolean inputs) {
        List<PlacedComponent> components = schematic.components().stream()
                .filter(component -> inputs
                        ? component.typeId().equals("input") || component.typeId().equals("clock")
                        : component.typeId().equals("output"))
                .sorted(Comparator
                        .comparingInt((PlacedComponent component) ->
                                component.typeId().equals("clock") ? 1 : 0)
                        .thenComparing(PlacedComponent::id))
                .toList();
        Map<String, String> nameById = new HashMap<>();
        description.componentIds().forEach((name, id) -> nameById.putIfAbsent(id, name));
        List<NamedSource> result = new ArrayList<>();
        int fallback = 1;
        for (PlacedComponent component : components) {
            String name = nameById.get(component.id());
            if (name == null || name.isBlank()) {
                name = component.label().isBlank()
                        ? (inputs ? "In" : "Out") + fallback
                        : component.label();
            }
            result.add(new NamedSource(component.id(), name));
            fallback++;
        }
        return List.copyOf(result);
    }

    private static int countGatesForExport(Schematic schematic) {
        int count = (int) schematic.components().stream()
                .filter(component -> !isBoundaryOrConstant(component.typeId()))
                .count();
        for (ComponentInstance instance : schematic.componentInstances()) {
            ComponentDefinition definition = schematic.componentDefinitionFor(instance);
            count += (int) definition.internalCircuit().components().stream()
                    .filter(component -> !isBoundaryOrConstant(component.typeId()))
                    .count();
        }
        return count;
    }

    private static boolean isBoundaryOrConstant(String typeId) {
        return typeId.equals("input") || typeId.equals("clock")
                || typeId.equals("output") || typeId.equals("constant");
    }

    private static boolean containsSequentialElement(Schematic schematic) {
        for (PlacedComponent component : schematic.components()) {
            if (component.typeId().equals("clock")) return true;
        }
        Set<String> visited = new HashSet<>();
        for (ComponentInstance instance : schematic.componentInstances()) {
            if (containsClockPortOrNestedClock(
                    schematic.componentDefinitionFor(instance), visited)) return true;
        }
        return false;
    }

    private static boolean containsClockPortOrNestedClock(
            ComponentDefinition definition, Set<String> visited) {
        if (!visited.add(definition.hash())) return false;
        if (definition.category().equalsIgnoreCase("Sequential")
                || definition.inputPorts().stream().anyMatch(ComponentDefinition.Port::isClock)) {
            return true;
        }
        Schematic internal = definition.internalCircuit();
        if (internal.components().stream().anyMatch(component ->
                component.typeId().equals("clock"))
                || hasAssignmentCycle(CircuitDescriber.describe(internal).text())) return true;
        for (ComponentInstance nested : internal.componentInstances()) {
            if (containsClockPortOrNestedClock(
                    internal.componentDefinitionFor(nested), visited)) return true;
        }
        return false;
    }

    private static boolean hasAssignmentCycle(String text) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Matcher assignmentMatcher = ASSIGNMENT.matcher(text);
        List<String[]> assignments = new ArrayList<>();
        while (assignmentMatcher.find()) {
            assignments.add(new String[]{assignmentMatcher.group(1), assignmentMatcher.group(2)});
            graph.putIfAbsent(assignmentMatcher.group(1), new LinkedHashSet<>());
        }
        for (String[] assignment : assignments) {
            Matcher referenceMatcher = REFERENCE.matcher(assignment[1]);
            while (referenceMatcher.find()) {
                String reference = referenceMatcher.group(1);
                if (graph.containsKey(reference)) {
                    graph.get(assignment[0]).add(reference);
                }
            }
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : graph.keySet()) {
            if (hasCycle(node, graph, visiting, visited)) return true;
        }
        return false;
    }

    private static boolean hasCycle(
            String node, Map<String, Set<String>> graph,
            Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) return true;
        if (!visited.add(node)) return false;
        visiting.add(node);
        for (String dependency : graph.getOrDefault(node, Set.of())) {
            if (hasCycle(dependency, graph, visiting, visited)) return true;
        }
        visiting.remove(node);
        return false;
    }

    private static EngineFacts buildFacts(
            Schematic source, int gateCount, List<NamedSource> inputs,
            List<NamedSource> outputs, boolean feedback, boolean sequential) {
        BigInteger totalRows = BigInteger.ONE.shiftLeft(inputs.size());
        if (feedback || sequential) {
            return new EngineFacts(gateCount, source.componentInstances().size(),
                    inputs.stream().map(NamedSource::name).toList(),
                    outputs.stream().map(NamedSource::name).toList(),
                    feedback, sequential, false, totalRows, List.of());
        }

        boolean sampled = inputs.size() > 6;
        int rowCount = sampled ? 64 : totalRows.intValueExact();
        List<TruthRow> rows = new ArrayList<>(rowCount);
        BigInteger last = totalRows.subtract(BigInteger.ONE);
        for (int row = 0; row < rowCount; row++) {
            BigInteger rowIndex = sampled
                    ? last.multiply(BigInteger.valueOf(row)).divide(BigInteger.valueOf(63))
                    : BigInteger.valueOf(row);
            String inputBits = pad(rowIndex.toString(2), inputs.size());
            Schematic rowSchematic = source.copy();
            for (int index = 0; index < inputs.size(); index++) {
                rowSchematic.setInputValue(inputs.get(index).id(),
                        inputBits.charAt(index) == '1');
            }
            SchematicCompiler.SimulationResult result =
                    SchematicCompiler.compileAndSettle(rowSchematic);
            StringBuilder outputBits = new StringBuilder(outputs.size());
            for (NamedSource output : outputs) {
                Value value = result.outputValues().getOrDefault(output.id(), Value.X);
                outputBits.append(switch (value) {
                    case ZERO -> '0';
                    case ONE -> '1';
                    case X -> 'X';
                });
            }
            rows.add(new TruthRow(inputBits, outputBits.toString()));
        }
        return new EngineFacts(gateCount, source.componentInstances().size(),
                inputs.stream().map(NamedSource::name).toList(),
                outputs.stream().map(NamedSource::name).toList(),
                false, sequential, sampled, totalRows, rows);
    }

    private static String pad(String bits, int width) {
        return "0".repeat(Math.max(0, width - bits.length())) + bits;
    }
}
