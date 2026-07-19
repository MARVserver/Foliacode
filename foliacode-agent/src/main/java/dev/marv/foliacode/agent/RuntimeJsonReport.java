package dev.marv.foliacode.agent;

import dev.marv.foliacode.report.JsonReport;

import java.util.List;

/**
 * Renders a runtime report as JSON.
 *
 * <p>Assembled by hand for the same reason the static report is: this code runs
 * inside a server process alongside arbitrary plugins, and adding a JSON library
 * to that classpath invites the version conflicts it exists to avoid.</p>
 */
public final class RuntimeJsonReport {

    private RuntimeJsonReport() {
    }

    /**
     * Renders a runtime report as a JSON string.
     *
     * @param report the runtime report
     * @return the JSON string
     */
    public static String render(RuntimeReport report) {
        StringBuilder out = new StringBuilder(1024);
        out.append("{\n");
        indent(out, 1).append("\"instrumentedClasses\": ").append(report.instrumentedClasses()).append(",\n");
        indent(out, 1).append("\"instrumentedSites\": ").append(report.instrumentedSites()).append(",\n");
        indent(out, 1).append("\"methodReferenceSites\": ").append(report.methodReferenceSites()).append(",\n");
        indent(out, 1).append("\"executedSites\": ").append(report.executed().size()).append(",\n");
        indent(out, 1).append("\"offTickThreadSites\": ")
                .append(report.offTickThreadExecutions().size()).append(",\n");
        appendSites(out, report.observations());
        out.append("}\n");
        return out.toString();
    }

    /**
     * Writes the array of observed sites.
     *
     * @param out          the destination
     * @param observations the observations
     */
    private static void appendSites(StringBuilder out, List<SiteObservation> observations) {
        indent(out, 1).append("\"sites\": [");
        if (observations.isEmpty()) {
            out.append("]\n");
            return;
        }
        out.append('\n');
        for (int i = 0; i < observations.size(); i++) {
            appendSite(out, observations.get(i));
            out.append(i < observations.size() - 1 ? ",\n" : "\n");
        }
        indent(out, 1).append("]\n");
    }

    /**
     * Writes one observed site.
     *
     * @param out         the destination
     * @param observation the observation
     */
    private static void appendSite(StringBuilder out, SiteObservation observation) {
        CallSite site = observation.site();
        indent(out, 2).append("{\n");
        appendString(out, 3, "severity", site.api().severity().name());
        appendString(out, 3, "category", site.api().category().name());
        appendString(out, 3, "api", site.api().displayName());
        appendString(out, 3, "calleeOwner", site.calleeOwner());
        appendString(out, 3, "class", site.className());
        appendString(out, 3, "method", site.methodName());
        appendString(out, 3, "descriptor", site.methodDescriptor());
        indent(out, 3).append("\"line\": ").append(site.lineNumber()).append(",\n");
        indent(out, 3).append("\"executionCount\": ").append(observation.executionCount()).append(",\n");
        indent(out, 3).append("\"ranOffTickThread\": ").append(observation.ranOffTickThread()).append(",\n");
        appendThreads(out, observation.threads());
        indent(out, 2).append('}');
    }

    /**
     * Writes the per-thread breakdown.
     *
     * @param out     the destination
     * @param threads the observations
     */
    private static void appendThreads(StringBuilder out, List<ThreadObservation> threads) {
        indent(out, 3).append("\"threads\": [");
        if (threads.isEmpty()) {
            out.append("]\n");
            return;
        }
        out.append('\n');
        for (int i = 0; i < threads.size(); i++) {
            ThreadObservation thread = threads.get(i);
            indent(out, 4).append("{\"name\": \"").append(JsonReport.escape(thread.threadName()))
                    .append("\", \"verdict\": \"").append(thread.verdict().name())
                    .append("\", \"count\": ").append(thread.count()).append('}');
            out.append(i < threads.size() - 1 ? ",\n" : "\n");
        }
        indent(out, 3).append("]\n");
    }

    /**
     * Writes a string field with a trailing comma.
     *
     * @param out   the destination
     * @param level indentation level
     * @param key   the key
     * @param value the value
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
