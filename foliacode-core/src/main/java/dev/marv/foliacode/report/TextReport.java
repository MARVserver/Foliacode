package dev.marv.foliacode.report;

import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.Category;
import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.PluginDescriptor;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.UnsafeApi;
import dev.marv.foliacode.model.Verdict;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an analysis result as text for a human reader.
 *
 * <p>The first thing anyone wants to know is "so, will this run?", so the
 * verdict comes first and the detail follows.</p>
 *
 * <p>When the same API is called from dozens of places, listing every one of
 * them adds nothing. Findings are grouped per rule and only a few
 * representative call sites are shown.</p>
 */
public final class TextReport {

    /** How many call sites to show per rule. */
    private static final int MAX_LOCATIONS_PER_RULE = 3;

    private static final String SEPARATOR = "=".repeat(72);
    private static final String THIN_SEPARATOR = "-".repeat(72);

    private TextReport() {
    }

    /**
     * Renders an analysis result.
     *
     * @param report  the analysis result
     * @param verbose true to list every call site instead of truncating
     * @return the rendered text
     */
    public static String render(AnalysisReport report, boolean verbose) {
        StringBuilder out = new StringBuilder();

        renderHeader(report, out);
        renderVerdict(report, out);

        if (report.findings().isEmpty()) {
            out.append('\n')
               .append("No Folia-incompatible calls were detected.\n")
               .append('\n')
               .append("Note: static analysis cannot follow reflection or dynamically generated\n")
               .append("      classes. Confirm the behaviour on a real server as well.\n");
            return out.toString();
        }

        renderSummary(report, out);
        renderDetails(report, verbose, out);
        renderFooter(out);

        return out.toString();
    }

    /**
     * Writes the header: the JAR under analysis and its plugin metadata.
     *
     * @param report the analysis result
     * @param out    the destination
     */
    private static void renderHeader(AnalysisReport report, StringBuilder out) {
        out.append(SEPARATOR).append('\n');
        out.append("FoliaCode analysis report").append('\n');
        out.append(SEPARATOR).append('\n');
        out.append("JAR            : ").append(report.jarName()).append('\n');

        PluginDescriptor plugin = report.plugin();
        if (plugin.isPresent()) {
            out.append("Plugin         : ").append(plugin.displayName()).append('\n');
            if (plugin.mainClass() != null) {
                out.append("Main class     : ").append(plugin.mainClass()).append('\n');
            }
            out.append("folia-supported: ")
               .append(describeFoliaFlag(plugin))
               .append('\n');
        } else {
            out.append("Plugin         : no plugin.yml found\n");
        }

        out.append("Classes scanned: ").append(report.classesScanned());
        if (report.classesFailed() > 0) {
            out.append(" (").append(report.classesFailed()).append(" failed to parse)");
        }
        out.append('\n');
        if (report.nestedJarsScanned() > 0) {
            out.append("Nested JARs    : ").append(report.nestedJarsScanned())
               .append(" unpacked and analysed\n");
        }
    }

    /**
     * Describes the state of the {@code folia-supported} declaration.
     *
     * @param plugin the plugin metadata
     * @return the description
     */
    private static String describeFoliaFlag(PluginDescriptor plugin) {
        if (plugin.foliaSupported() == null) {
            return "not declared";
        }
        return plugin.declaresFoliaSupport()
                ? "true (declares Folia support)"
                : "false";
    }

    /**
     * Writes the overall verdict.
     *
     * @param report the analysis result
     * @param out    the destination
     */
    private static void renderVerdict(AnalysisReport report, StringBuilder out) {
        Verdict verdict = report.verdict();
        out.append(SEPARATOR).append('\n');
        out.append("Verdict: ").append(verdict.label())
           .append(" — ").append(verdict.description()).append('\n');

        PluginDescriptor plugin = report.plugin();
        if (plugin.declaresFoliaSupport() && verdict != Verdict.READY) {
            out.append('\n');
            out.append("WARNING: this plugin declares folia-supported: true, "
                    + "but incompatible calls were found.\n");
            out.append("         Folia trusts that declaration and loads the plugin anyway, "
                    + "so these will fail at runtime.\n");
        }
        out.append(SEPARATOR).append('\n');
    }

    /**
     * Writes the counts per severity and per category.
     *
     * @param report the analysis result
     * @param out    the destination
     */
    private static void renderSummary(AnalysisReport report, StringBuilder out) {
        out.append('\n').append("Findings by severity").append('\n');
        for (Severity severity : Severity.values()) {
            int count = report.count(severity);
            if (count == 0) {
                continue;
            }
            out.append(String.format("  %-9s %4d  — %s%n",
                    severity.label(), count, severity.description()));
        }

        Map<Category, Integer> categories = report.categoryCounts();
        if (!categories.isEmpty()) {
            out.append('\n').append("Findings by category").append('\n');
            categories.forEach((category, count) ->
                    out.append(String.format("  %-18s %4d%n", category.displayName(), count)));
        }
    }

    /**
     * Writes the detailed findings, most serious first.
     *
     * @param report  the analysis result
     * @param verbose whether to list every call site
     * @param out     the destination
     */
    private static void renderDetails(AnalysisReport report, boolean verbose, StringBuilder out) {
        report.groupedBySeverity().forEach((severity, findings) -> {
            out.append('\n').append(THIN_SEPARATOR).append('\n');
            out.append('[').append(severity.label()).append("]  ")
               .append(findings.size()).append(findings.size() == 1 ? " finding" : " findings")
               .append('\n');
            out.append(THIN_SEPARATOR).append('\n');

            groupByRule(findings).forEach((api, occurrences) ->
                    renderRuleGroup(api, occurrences, verbose, out));
        });
    }

    /**
     * Writes the findings for a single rule.
     *
     * @param api         the rule
     * @param occurrences its call sites
     * @param verbose     whether to list every call site
     * @param out         the destination
     */
    private static void renderRuleGroup(
            UnsafeApi api,
            List<Finding> occurrences,
            boolean verbose,
            StringBuilder out
    ) {
        out.append('\n');
        out.append("● ").append(api.displayName())
           .append("  [").append(api.category().displayName()).append("]")
           .append("  ").append(occurrences.size())
           .append(occurrences.size() == 1 ? " call site" : " call sites").append('\n');
        out.append("  Why: ").append(api.reason()).append('\n');
        out.append("  Fix: ").append(api.remedy()).append('\n');

        int limit = verbose ? occurrences.size() : Math.min(MAX_LOCATIONS_PER_RULE, occurrences.size());
        for (int i = 0; i < limit; i++) {
            Finding finding = occurrences.get(i);
            out.append("    - ").append(finding.location());
            if (finding.isViaSubtype()) {
                out.append(" [via ").append(finding.calleeSimpleName()).append("]");
            }
            if (finding.callKind() == dev.marv.foliacode.model.CallKind.METHOD_REFERENCE) {
                out.append(" [method reference]");
            }
            out.append('\n');
        }
        int remaining = occurrences.size() - limit;
        if (remaining > 0) {
            out.append("    ... and ").append(remaining)
               .append(remaining == 1 ? " more call site" : " more call sites")
               .append(" (use --verbose to list all)").append('\n');
        }
    }

    /**
     * Groups findings by the rule that produced them.
     *
     * @param findings the findings
     * @return rule to call sites
     */
    private static Map<UnsafeApi, List<Finding>> groupByRule(List<Finding> findings) {
        Map<UnsafeApi, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding finding : findings) {
            grouped.computeIfAbsent(finding.api(), key -> new java.util.ArrayList<>()).add(finding);
        }
        return grouped;
    }

    /**
     * Writes the closing note about what static analysis cannot tell you.
     *
     * @param out the destination
     */
    private static void renderFooter(StringBuilder out) {
        out.append('\n').append(SEPARATOR).append('\n');
        out.append("Limits of static analysis\n");
        out.append(SEPARATOR).append('\n');
        out.append("  - Reflection and dynamically generated classes cannot be followed\n");
        out.append("  - The caller's thread context is not yet inferred, so some HIGH findings\n");
        out.append("    may already run on the correct region\n");
        out.append("  - A clean report is not proof that the plugin is safe\n");
    }
}
