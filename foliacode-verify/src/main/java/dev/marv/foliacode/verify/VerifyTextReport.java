package dev.marv.foliacode.verify;

import java.time.Duration;
import java.util.List;

/**
 * Renders a {@link ServerVerificationResult} as human-readable text.
 *
 * <p>The single most important job of this report is to say <em>whose fault it is</em>.
 * A server that ran out of heap and a plugin that throws on load look similar in a
 * raw log, but they demand opposite responses from the reader. Blaming the plugin for
 * an out-of-memory failure would send someone off rewriting working code, so the
 * report leads with attribution and only then shows the evidence.</p>
 */
public final class VerifyTextReport {

    private static final String SEPARATOR = "=".repeat(72);
    private static final String THIN_SEPARATOR = "-".repeat(72);

    /** How many log lines to show when quoting the tail of a failed boot. */
    private static final int LOG_TAIL_LINES = 25;

    private VerifyTextReport() {
    }

    /**
     * Renders the result.
     *
     * @param result     the verification result
     * @param pluginName the plugin that was verified, for the header
     * @param config     the configuration the run used
     * @return formatted text
     */
    public static String render(
            ServerVerificationResult result,
            String pluginName,
            ServerVerificationConfig config
    ) {
        StringBuilder out = new StringBuilder(1024);

        out.append(SEPARATOR).append('\n');
        out.append("Folia server verification").append('\n');
        out.append(SEPARATOR).append('\n');
        out.append("Plugin            : ").append(pluginName).append('\n');
        out.append("Minecraft version : ").append(result.minecraftVersion()).append('\n');
        out.append("Heap              : ").append(config.memoryMb()).append(" MB").append('\n');
        out.append("Boot time         : ").append(formatDuration(result.bootDuration())).append('\n');
        out.append(SEPARATOR).append('\n');

        renderOutcome(result, out);
        renderMissingDependencies(result, out);
        renderIssues(result, out);
        renderLogTail(result, out);
        renderClosing(config, out);

        return out.toString();
    }

    /**
     * Renders the headline outcome and who is responsible for it.
     *
     * @param result the result
     * @param out    the destination
     */
    private static void renderOutcome(ServerVerificationResult result, StringBuilder out) {
        VerificationOutcome outcome = result.outcome();
        out.append("Result: ").append(outcome.name())
           .append(" — ").append(outcome.description()).append('\n');

        if (result.isSuccess()) {
            out.append('\n');
            out.append("The server reached a running state and the plugin was enabled.\n");
            out.append("Note that booting is not the same as working. This confirms the plugin\n");
            out.append("loads on Folia; it does not exercise any of its runtime behaviour.\n");
            return;
        }

        out.append('\n');
        if (result.isAttributableToPlugin()) {
            out.append("ATTRIBUTION: this failure points at the plugin or its dependencies.\n");
        } else {
            out.append("ATTRIBUTION: this failure points at the verification environment,\n");
            out.append("             NOT at the plugin. Fix the setting below and re-run\n");
            out.append("             before drawing any conclusion about the plugin itself.\n");

            if (outcome == VerificationOutcome.OUT_OF_MEMORY) {
                out.append('\n');
                out.append("The server JVM could not run in the heap it was given.\n");
                out.append("A Folia server needs roughly 1024 MB at an absolute minimum;\n");
                out.append("2048 MB or more is realistic for anything beyond an empty world.\n");
                out.append("Re-run with a larger heap, for example: --memory 2048\n");
            } else if (outcome == VerificationOutcome.TIMEOUT) {
                out.append('\n');
                out.append("The server was still starting when the timeout elapsed.\n");
                out.append("On a cold cache the first boot also downloads and generates a world.\n");
                out.append("Re-run with a longer timeout, for example: --timeout 300\n");
            }
        }
    }

    /**
     * Names the plugins that have to be installed alongside this one.
     *
     * @param result the result
     * @param out    the destination
     */
    private static void renderMissingDependencies(ServerVerificationResult result, StringBuilder out) {
        if (!result.hasMissingDependencies()) {
            return;
        }
        out.append('\n').append(THIN_SEPARATOR).append('\n');
        out.append("Missing dependency plugins").append('\n');
        out.append(THIN_SEPARATOR).append('\n');
        for (String dependency : result.missingDependencies()) {
            out.append("  - ").append(dependency).append('\n');
        }
        out.append('\n');
        out.append("The plugin cannot load until these are present. Install them and\n");
        out.append("re-run, supplying each one with --with <jar>:\n");
        out.append("  foliacode verify MyPlugin.jar --with Vault.jar --with ProtocolLib.jar\n");
    }

    /**
     * Renders every detected issue with the log lines it was derived from.
     *
     * @param result the result
     * @param out    the destination
     */
    private static void renderIssues(ServerVerificationResult result, StringBuilder out) {
        List<VerificationIssue> issues = result.issues();
        if (issues.isEmpty()) {
            return;
        }
        out.append('\n').append(THIN_SEPARATOR).append('\n');
        out.append("Detected issues (").append(issues.size()).append(")").append('\n');
        out.append(THIN_SEPARATOR).append('\n');

        for (VerificationIssue issue : issues) {
            out.append('\n');
            out.append("[").append(issue.severity().label()).append("] ")
               .append(issue.type().name()).append('\n');
            out.append("  ").append(issue.message()).append('\n');
            if (!issue.evidence().isEmpty()) {
                out.append("  Evidence from the server log:\n");
                for (String line : issue.evidence()) {
                    out.append("    | ").append(line).append('\n');
                }
            }
        }
    }

    /**
     * Quotes the tail of the server log when the boot did not succeed.
     *
     * @param result the result
     * @param out    the destination
     */
    private static void renderLogTail(ServerVerificationResult result, StringBuilder out) {
        if (result.isSuccess() || result.logTail().isEmpty()) {
            return;
        }
        List<String> tail = result.logTail();
        int from = Math.max(0, tail.size() - LOG_TAIL_LINES);
        List<String> shown = tail.subList(from, tail.size());

        out.append('\n').append(THIN_SEPARATOR).append('\n');
        out.append("Last ").append(shown.size()).append(" server log lines").append('\n');
        out.append(THIN_SEPARATOR).append('\n');
        for (String line : shown) {
            out.append("  ").append(line).append('\n');
        }
    }

    /**
     * States what happened to the throwaway server directory.
     *
     * @param config the configuration the run used
     * @param out    the destination
     */
    private static void renderClosing(ServerVerificationConfig config, StringBuilder out) {
        out.append('\n').append(SEPARATOR).append('\n');
        if (config.keepServerDirectory()) {
            out.append("The temporary server was shut down. Its directory was KEPT because\n");
            out.append("--keep-server was requested; remember to delete it yourself.\n");
        } else {
            out.append("The temporary server was shut down and its directory deleted.\n");
        }
        out.append(SEPARATOR).append('\n');
    }

    /**
     * Formats a duration as seconds with one decimal.
     *
     * @param duration the duration, possibly {@code null}
     * @return a short human-readable form
     */
    private static String formatDuration(Duration duration) {
        if (duration == null) {
            return "unknown";
        }
        return String.format("%.1fs", duration.toMillis() / 1000.0);
    }
}
