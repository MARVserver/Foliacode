package dev.marv.foliacode.transform;

import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.Severity;

import java.util.List;
import java.util.Map;

/**
 * Renders a transform result as text for a human reader.
 *
 * <p>What was refused comes before what was changed. A reader who only takes in the
 * first screen should leave knowing what is still broken, not feeling reassured by a
 * list of successes.</p>
 */
public final class TransformTextReport {

    /** How many call sites to list per API. */
    private static final int MAX_LOCATIONS_PER_API = 3;

    private static final String SEPARATOR = "=".repeat(72);
    private static final String THIN_SEPARATOR = "-".repeat(72);

    private TransformTextReport() {
    }

    /**
     * Renders a transform result.
     *
     * @param report the transform result
     * @return the rendered text
     */
    public static String render(TransformReport report) {
        StringBuilder out = new StringBuilder();

        out.append(SEPARATOR).append('\n');
        out.append(report.isDryRun()
                ? "FoliaCode transform — dry run, nothing was written"
                : "FoliaCode transform").append('\n');
        out.append(SEPARATOR).append('\n');
        out.append("Source         : ").append(report.sourceName()).append('\n');
        if (!report.isDryRun()) {
            out.append("Output         : ").append(report.outputName()).append('\n');
        }
        out.append("Classes scanned: ").append(report.classesScanned())
           .append("  (").append(report.classesRewritten()).append(" rewritten)").append('\n');
        if (report.nestedJarsUntouched() > 0) {
            out.append("Shaded JARs    : ").append(report.nestedJarsUntouched())
               .append(" left untouched").append('\n');
        }
        out.append('\n');

        renderHeadline(report, out);
        renderRefusals(report, out);
        renderRewrites(report, out);
        renderCaveats(report, out);
        renderFooter(report, out);

        return out.toString();
    }

    /**
     * Writes the before-and-after summary.
     *
     * @param report the transform result
     * @param out    the destination
     */
    private static void renderHeadline(TransformReport report, StringBuilder out) {
        out.append("Verdict: ").append(report.before().verdict().name())
           .append("  ->  ").append(report.resultingVerdict().name()).append('\n');
        out.append('\n');
        out.append(String.format("  %-10s %8s %8s%n", "SEVERITY", "BEFORE", "AFTER"));
        out.append("  ").append("-".repeat(28)).append('\n');

        List<Finding> remaining = report.remainingFindings();
        for (Severity severity : Severity.values()) {
            long after = remaining.stream().filter(f -> f.severity() == severity).count();
            out.append(String.format("  %-10s %8d %8d%n",
                    severity.label(), report.before().count(severity), after));
        }
        out.append('\n');
    }

    /**
     * Writes what was left alone and why.
     *
     * @param report the transform result
     * @param out    the destination
     */
    private static void renderRefusals(TransformReport report, StringBuilder out) {
        Map<String, List<TransformAction>> grouped = report.refusalsByApi();
        if (grouped.isEmpty()) {
            out.append("Nothing was refused.\n\n");
            return;
        }

        out.append("Not rewritten — these still need a person (").append(report.refused().size())
           .append(" call site").append(report.refused().size() == 1 ? "" : "s").append(")\n");
        out.append(THIN_SEPARATOR).append('\n');

        for (Map.Entry<String, List<TransformAction>> entry : grouped.entrySet()) {
            List<TransformAction> actions = entry.getValue();
            TransformAction first = actions.get(0);

            out.append("● ").append(entry.getKey())
               .append("  [").append(first.severity().label()).append("]  ")
               .append(actions.size()).append(" call site")
               .append(actions.size() == 1 ? "" : "s").append('\n');
            out.append("  Why not: ").append(first.refusal().explanation()).append('\n');
            out.append("  Fix    : ").append(first.api().remedy()).append('\n');

            int shown = 0;
            for (TransformAction action : actions) {
                if (shown++ >= MAX_LOCATIONS_PER_API) {
                    out.append("    ... and ").append(actions.size() - MAX_LOCATIONS_PER_API)
                       .append(" more\n");
                    break;
                }
                out.append("    - ").append(action.location()).append('\n');
            }
            out.append('\n');
        }
    }

    /**
     * Writes what was changed.
     *
     * @param report the transform result
     * @param out    the destination
     */
    private static void renderRewrites(TransformReport report, StringBuilder out) {
        List<TransformAction> rewritten = report.rewritten();
        if (rewritten.isEmpty()) {
            out.append("Nothing was rewritten.\n\n");
            return;
        }

        out.append("Rewritten (").append(rewritten.size()).append(" call site")
           .append(rewritten.size() == 1 ? "" : "s").append(")\n");
        out.append(THIN_SEPARATOR).append('\n');
        for (TransformAction action : rewritten) {
            out.append("● ").append(action.api().displayName())
               .append("  at ").append(action.location()).append('\n');
            out.append("  ").append(action.rule().summary()).append('\n');
        }
        out.append('\n');
    }

    /**
     * Writes the behaviour changes the rewrites introduce.
     *
     * @param report the transform result
     * @param out    the destination
     */
    private static void renderCaveats(TransformReport report, StringBuilder out) {
        Map<String, TransformRule> caveats = report.caveats();
        if (caveats.isEmpty()) {
            return;
        }
        out.append("What changed in behaviour\n");
        out.append(THIN_SEPARATOR).append('\n');
        for (TransformRule rule : caveats.values()) {
            out.append("● ").append(rule.summary()).append('\n');
            out.append("  ").append(rule.caveat()).append('\n');
        }
        out.append('\n');
    }

    /**
     * Writes the closing statement about what this result is worth.
     *
     * @param report the transform result
     * @param out    the destination
     */
    private static void renderFooter(TransformReport report, StringBuilder out) {
        out.append(THIN_SEPARATOR).append('\n');

        if (report.foliaSupportedDeclared()) {
            out.append("plugin.yml now declares folia-supported: true, because the rewritten JAR\n");
            out.append("was read back and no CRITICAL, HIGH or MEDIUM finding remained.\n\n");
        } else if (!report.isDryRun()) {
            out.append("plugin.yml was left alone. folia-supported is only declared once the\n");
            out.append("rewritten JAR comes back clean, and this one did not.\n\n");
        }

        out.append("This is a rewrite of call sites, not a proof of correctness. It was checked\n");
        out.append("by re-analysing the output, which is the same static analysis with the same\n");
        out.append("blind spots — reflection above all. Test the result on a real server.\n");

        if (!report.isDryRun()) {
            out.append('\n');
            out.append("The original JAR is untouched. Keep it until the rewritten one has run.\n");
        }
    }
}
