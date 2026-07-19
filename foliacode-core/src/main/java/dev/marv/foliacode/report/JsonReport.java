package dev.marv.foliacode.report;

import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.PluginDescriptor;
import dev.marv.foliacode.model.Severity;

import java.util.List;

/**
 * Renders an analysis result as JSON.
 *
 * <p>This is the machine-readable output for CI. Pulling in a JSON library
 * would risk dependency conflicts with the plugins being analysed, so the
 * minimum needed is assembled by hand.</p>
 */
public final class JsonReport {

    private JsonReport() {
    }

    /**
     * Renders an analysis result as a JSON string.
     *
     * @param report the analysis result
     * @return the JSON string
     */
    public static String render(AnalysisReport report) {
        StringBuilder out = new StringBuilder(1024);
        out.append("{\n");

        appendField(out, 1, "jar", report.jarName(), true);
        appendPlugin(out, report.plugin());
        appendNumberField(out, 1, "classesScanned", report.classesScanned(), true);
        appendNumberField(out, 1, "classesFailed", report.classesFailed(), true);
        appendNumberField(out, 1, "nestedJarsScanned", report.nestedJarsScanned(), true);
        appendField(out, 1, "verdict", report.verdict().name(), true);

        appendSummary(out, report);
        appendFindings(out, report.sortedFindings());

        out.append("}\n");
        return out.toString();
    }

    /**
     * Writes the plugin metadata.
     *
     * @param out    the destination
     * @param plugin the plugin metadata
     */
    private static void appendPlugin(StringBuilder out, PluginDescriptor plugin) {
        indent(out, 1).append("\"plugin\": {\n");
        appendField(out, 2, "name", plugin.name(), true);
        appendField(out, 2, "version", plugin.version(), true);
        appendField(out, 2, "main", plugin.mainClass(), true);
        indent(out, 2).append("\"foliaSupported\": ")
                .append(plugin.foliaSupported() == null ? "null" : plugin.foliaSupported())
                .append('\n');
        indent(out, 1).append("},\n");
    }

    /**
     * Writes the counts per severity.
     *
     * @param out    the destination
     * @param report the analysis result
     */
    private static void appendSummary(StringBuilder out, AnalysisReport report) {
        indent(out, 1).append("\"summary\": {\n");
        Severity[] severities = Severity.values();
        for (int i = 0; i < severities.length; i++) {
            indent(out, 2).append('"').append(severities[i].name()).append("\": ")
                    .append(report.count(severities[i]));
            out.append(i < severities.length - 1 ? ",\n" : "\n");
        }
        indent(out, 1).append("},\n");
    }

    /**
     * Writes the array of findings.
     *
     * @param out      the destination
     * @param findings the findings
     */
    private static void appendFindings(StringBuilder out, List<Finding> findings) {
        indent(out, 1).append("\"findings\": [");
        if (findings.isEmpty()) {
            out.append("]\n");
            return;
        }
        out.append('\n');
        for (int i = 0; i < findings.size(); i++) {
            appendFinding(out, findings.get(i));
            out.append(i < findings.size() - 1 ? ",\n" : "\n");
        }
        indent(out, 1).append("]\n");
    }

    /**
     * Writes a single finding.
     *
     * @param out     the destination
     * @param finding the finding
     */
    private static void appendFinding(StringBuilder out, Finding finding) {
        indent(out, 2).append("{\n");
        appendField(out, 3, "severity", finding.severity().name(), true);
        appendField(out, 3, "category", finding.category().name(), true);
        appendField(out, 3, "api", finding.api().displayName(), true);
        appendField(out, 3, "ruleOwner", finding.api().owner(), true);
        appendField(out, 3, "calleeOwner", finding.calleeOwner(), true);
        appendField(out, 3, "class", finding.className(), true);
        appendField(out, 3, "method", finding.methodName(), true);
        appendField(out, 3, "descriptor", finding.methodDescriptor(), true);
        appendNumberField(out, 3, "line", finding.lineNumber(), true);
        appendField(out, 3, "callKind", finding.callKind().name(), true);
        indent(out, 3).append("\"viaSubtype\": ").append(finding.isViaSubtype()).append(",\n");
        appendField(out, 3, "reason", finding.api().reason(), true);
        appendField(out, 3, "remedy", finding.api().remedy(), false);
        indent(out, 2).append('}');
    }

    /**
     * Writes a string field. A {@code null} value becomes JSON {@code null}.
     *
     * @param out           the destination
     * @param level         indentation level
     * @param key           the key
     * @param value         the value
     * @param trailingComma whether to append a trailing comma
     */
    private static void appendField(StringBuilder out, int level, String key, String value, boolean trailingComma) {
        indent(out, level).append('"').append(escape(key)).append("\": ");
        if (value == null) {
            out.append("null");
        } else {
            out.append('"').append(escape(value)).append('"');
        }
        out.append(trailingComma ? ",\n" : "\n");
    }

    /**
     * Writes a numeric field.
     *
     * @param out           the destination
     * @param level         indentation level
     * @param key           the key
     * @param value         the value
     * @param trailingComma whether to append a trailing comma
     */
    private static void appendNumberField(StringBuilder out, int level, String key, long value, boolean trailingComma) {
        indent(out, level).append('"').append(escape(key)).append("\": ").append(value);
        out.append(trailingComma ? ",\n" : "\n");
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

    /**
     * Escapes a value for inclusion in a JSON string.
     *
     * <p>Public because the runtime agent and the transformer render their own
     * reports. Each hand-assembles its JSON for the same reason this class does,
     * and all of them need to escape strings the same way.</p>
     *
     * @param value the value to escape
     * @return the escaped string
     */
    public static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
