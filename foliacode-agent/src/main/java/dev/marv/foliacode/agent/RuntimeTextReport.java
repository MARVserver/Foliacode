package dev.marv.foliacode.agent;

import java.util.List;

/**
 * Renders a runtime report as text for a human reader.
 *
 * <p>Ordered by what a reader needs first: what actually ran on the wrong kind of
 * thread, then what ran at all, then what the run never reached. The last group is
 * a statement about test coverage, not about safety, and is labelled that way.</p>
 */
public final class RuntimeTextReport {

    /** How many call sites to list per section. */
    private static final int MAX_SITES_PER_SECTION = 10;

    private static final String SEPARATOR = "=".repeat(72);

    private RuntimeTextReport() {
    }

    /**
     * Renders a runtime report.
     *
     * @param report the runtime report
     * @return the rendered text
     */
    public static String render(RuntimeReport report) {
        StringBuilder out = new StringBuilder();

        out.append(SEPARATOR).append('\n');
        out.append("FoliaCode runtime observations").append('\n');
        out.append(SEPARATOR).append('\n');
        out.append("Classes instrumented : ").append(report.instrumentedClasses()).append('\n');
        out.append("Call sites watched   : ").append(report.instrumentedSites()).append('\n');
        out.append("Call sites executed  : ").append(report.executed().size()).append('\n');
        if (report.methodReferenceSites() > 0) {
            out.append("Method references    : ").append(report.methodReferenceSites())
               .append(" (not instrumentable; static analysis only)").append('\n');
        }
        out.append('\n');

        if (report.instrumentedSites() == 0) {
            out.append("Nothing was instrumented. Either the plugin has no calls the rule set\n");
            out.append("flags, or the include filter did not match its packages.\n");
            return out.toString();
        }

        renderOffTickThread(report, out);
        renderExecuted(report, out);
        renderNeverExecuted(report, out);
        renderFooter(out);

        return out.toString();
    }

    /**
     * Writes the sites that ran off a tick thread.
     *
     * @param report the runtime report
     * @param out    the destination
     */
    private static void renderOffTickThread(RuntimeReport report, StringBuilder out) {
        List<SiteObservation> offTick = report.offTickThreadExecutions();
        if (offTick.isEmpty()) {
            out.append("No watched call site ran off a tick thread during this run.\n\n");
            return;
        }

        out.append("Ran off a tick thread\n");
        out.append("-".repeat(72)).append('\n');
        int shown = 0;
        for (SiteObservation observation : offTick) {
            if (shown++ >= MAX_SITES_PER_SECTION) {
                out.append("  ... and ").append(offTick.size() - MAX_SITES_PER_SECTION)
                   .append(" more\n");
                break;
            }
            CallSite site = observation.site();
            out.append("● ").append(site.api().displayName())
               .append("  [").append(site.api().severity().label()).append("]  ")
               .append(observation.executionCount()).append(" call")
               .append(observation.executionCount() == 1 ? "" : "s").append('\n');
            out.append("    at ").append(site.location()).append('\n');
            for (ThreadObservation thread : observation.threads()) {
                out.append("      ").append(thread.verdict().label())
                   .append(" — \"").append(thread.threadName()).append("\" ×")
                   .append(thread.count()).append('\n');
            }
        }
        out.append('\n');
    }

    /**
     * Writes the sites that executed.
     *
     * @param report the runtime report
     * @param out    the destination
     */
    private static void renderExecuted(RuntimeReport report, StringBuilder out) {
        List<SiteObservation> executed = report.executed();
        if (executed.isEmpty()) {
            out.append("No watched call site executed during this run.\n\n");
            return;
        }

        out.append("Executed call sites (").append(executed.size()).append(")\n");
        out.append("-".repeat(72)).append('\n');
        int shown = 0;
        for (SiteObservation observation : executed) {
            if (shown++ >= MAX_SITES_PER_SECTION) {
                out.append("  ... and ").append(executed.size() - MAX_SITES_PER_SECTION)
                   .append(" more\n");
                break;
            }
            out.append(String.format("  %-10s %-38s ×%d%n",
                    observation.site().api().severity().label(),
                    observation.site().api().displayName(),
                    observation.executionCount()));
        }
        out.append('\n');
    }

    /**
     * Writes the sites that were watched but never ran.
     *
     * @param report the runtime report
     * @param out    the destination
     */
    private static void renderNeverExecuted(RuntimeReport report, StringBuilder out) {
        List<SiteObservation> never = report.neverExecuted();
        if (never.isEmpty()) {
            return;
        }
        out.append("Watched but never executed: ").append(never.size()).append('\n');
        out.append("  This says the run did not reach them. It does not say they are safe —\n");
        out.append("  a code path nobody triggered is untested, not proven.\n");
        out.append('\n');
    }

    /**
     * Writes the closing caveats.
     *
     * @param out the destination
     */
    private static void renderFooter(StringBuilder out) {
        out.append("-".repeat(72)).append('\n');
        out.append("What this can and cannot tell you\n");
        out.append("  Can: whether a flagged call actually runs, how often, and whether the\n");
        out.append("       server considered that thread a tick thread.\n");
        out.append("  Cannot: whether it ran on the correct region's thread. Folia owns entities\n");
        out.append("       and chunks per region, and a call can be on a tick thread and still\n");
        out.append("       be on the wrong one.\n");
    }
}
