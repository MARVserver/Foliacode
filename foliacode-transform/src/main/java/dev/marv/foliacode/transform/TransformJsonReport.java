package dev.marv.foliacode.transform;

import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.report.JsonReport;

import java.util.List;

/**
 * Renders a transform result as JSON.
 *
 * <p>Assembled by hand, like the other reports, so that nothing on a user's plugin
 * classpath can conflict with a JSON library of ours.</p>
 */
public final class TransformJsonReport {

    private TransformJsonReport() {
    }

    /**
     * Renders a transform result as a JSON string.
     *
     * @param report the transform result
     * @return the JSON string
     */
    public static String render(TransformReport report) {
        StringBuilder out = new StringBuilder(2048);
        out.append("{\n");
        appendString(out, 1, "source", report.sourceName());
        appendString(out, 1, "output", report.outputName());
        indent(out, 1).append("\"dryRun\": ").append(report.isDryRun()).append(",\n");
        indent(out, 1).append("\"classesScanned\": ").append(report.classesScanned()).append(",\n");
        indent(out, 1).append("\"classesRewritten\": ").append(report.classesRewritten()).append(",\n");
        indent(out, 1).append("\"nestedJarsUntouched\": ")
                .append(report.nestedJarsUntouched()).append(",\n");
        appendString(out, 1, "verdictBefore", report.before().verdict().name());
        appendString(out, 1, "verdictAfter", report.resultingVerdict().name());
        indent(out, 1).append("\"foliaSupportedDeclared\": ")
                .append(report.foliaSupportedDeclared()).append(",\n");

        appendSummary(out, report);
        appendActions(out, report.actions());
        out.append("}\n");
        return out.toString();
    }

    /**
     * Writes the before-and-after counts per severity.
     *
     * @param out    the destination
     * @param report the transform result
     */
    private static void appendSummary(StringBuilder out, TransformReport report) {
        List<Finding> remaining = report.remainingFindings();
        indent(out, 1).append("\"summary\": {\n");
        Severity[] severities = Severity.values();
        for (int i = 0; i < severities.length; i++) {
            Severity severity = severities[i];
            long after = remaining.stream().filter(f -> f.severity() == severity).count();
            indent(out, 2).append('"').append(severity.name()).append("\": {\"before\": ")
                    .append(report.before().count(severity)).append(", \"after\": ")
                    .append(after).append('}');
            out.append(i < severities.length - 1 ? ",\n" : "\n");
        }
        indent(out, 1).append("},\n");
    }

    /**
     * Writes the array of call-site actions.
     *
     * @param out     the destination
     * @param actions the actions
     */
    private static void appendActions(StringBuilder out, List<TransformAction> actions) {
        indent(out, 1).append("\"callSites\": [");
        if (actions.isEmpty()) {
            out.append("]\n");
            return;
        }
        out.append('\n');
        for (int i = 0; i < actions.size(); i++) {
            appendAction(out, actions.get(i));
            out.append(i < actions.size() - 1 ? ",\n" : "\n");
        }
        indent(out, 1).append("]\n");
    }

    /**
     * Writes one call-site action.
     *
     * @param out    the destination
     * @param action the action
     */
    private static void appendAction(StringBuilder out, TransformAction action) {
        indent(out, 2).append("{\n");
        appendString(out, 3, "outcome", action.isRewritten() ? "REWRITTEN" : "REFUSED");
        appendString(out, 3, "severity", action.severity().name());
        appendString(out, 3, "api", action.api().displayName());
        appendString(out, 3, "calleeOwner", action.calleeOwner());
        appendString(out, 3, "class", action.className());
        appendString(out, 3, "method", action.methodName());
        appendString(out, 3, "descriptor", action.methodDescriptor());
        indent(out, 3).append("\"line\": ").append(action.lineNumber()).append(",\n");

        if (action.isRewritten()) {
            appendString(out, 3, "rewrittenTo",
                    action.rule().shimOwner().replace('/', '.') + "." + action.rule().shimName());
            appendString(out, 3, "summary", action.rule().summary());
            indent(out, 3).append("\"caveat\": ");
            if (action.rule().hasCaveat()) {
                out.append('"').append(JsonReport.escape(action.rule().caveat())).append("\"\n");
            } else {
                out.append("null\n");
            }
        } else {
            appendString(out, 3, "refusal", action.refusal().name());
            appendString(out, 3, "why", action.refusal().explanation());
            indent(out, 3).append("\"remedy\": \"")
                    .append(JsonReport.escape(action.api().remedy())).append("\"\n");
        }
        indent(out, 2).append('}');
    }

    /**
     * Writes a string field with a trailing comma.
     *
     * @param out   the destination
     * @param level indentation level
     * @param key   the key
     * @param value the value; {@code null} becomes JSON null
     */
    private static void appendString(StringBuilder out, int level, String key, String value) {
        indent(out, level).append('"').append(key).append("\": ");
        if (value == null) {
            out.append("null,\n");
        } else {
            out.append('"').append(JsonReport.escape(value)).append("\",\n");
        }
    }

    /**
     * Appends indentation.
     *
     * @param out   the destination
     * @param level indentation level
     * @return the destination, for chaining
     */
    private static StringBuilder indent(StringBuilder out, int level) {
        return out.append("  ".repeat(level));
    }
}
