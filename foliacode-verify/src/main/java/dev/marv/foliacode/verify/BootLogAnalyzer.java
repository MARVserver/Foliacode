package dev.marv.foliacode.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a server boot by reading its log.
 *
 * <p>The analyzer never guesses. Every conclusion is backed by log lines that are carried
 * into the result so the report can quote them.</p>
 *
 * <p><strong>Priority matters more than detection here.</strong> A Folia server cannot boot
 * in a small heap; it dies during VM initialisation before any plugin is touched. If that
 * were classified as a plugin failure the tool would tell users to fix code that is fine,
 * which is the worst thing this tool could do. Environment failures therefore outrank every
 * plugin-attributable signal in {@link #selectOutcome}.</p>
 */
public final class BootLogAnalyzer {

    /** Heap size, in megabytes, below which a Folia boot is not realistically possible. */
    public static final int MINIMUM_VIABLE_HEAP_MB = 1024;

    /** Paper and Folia print this once the server is fully started. */
    private static final Pattern BOOT_COMPLETE =
            Pattern.compile("Done \\([^)]*\\)! For help, type \"help\"");

    /** Bukkit's dependency resolver lists everything it could not find in one bracketed group. */
    private static final Pattern UNKNOWN_DEPENDENCIES =
            Pattern.compile("Unknown/missing dependency plugins:\\s*\\[([^\\]]*)\\]");

    /** Paper's newer plugin loader names dependencies individually and quoted. */
    private static final Pattern QUOTED_DEPENDENCY =
            Pattern.compile("(?:missing|unknown|required)\\s+dependency\\s+'([^']+)'",
                    Pattern.CASE_INSENSITIVE);

    /** Generic dependency phrasing that carries no parseable names. */
    private static final Pattern GENERIC_DEPENDENCY =
            Pattern.compile("missing a dependency|missing dependenc", Pattern.CASE_INSENSITIVE);

    /** A load failure that mentions dependencies without using the standard wording. */
    private static final Pattern LOAD_FAILURE_WITH_DEPEND =
            Pattern.compile("Could not load '[^']*'.*depend", Pattern.CASE_INSENSITIVE);

    /** Folia refuses plugins that have not opted in. */
    private static final Pattern FOLIA_UNSUPPORTED =
            Pattern.compile("is not marked as supporting Folia");

    /** Linkage and class resolution failures. */
    private static final Pattern CLASS_ERROR = Pattern.compile(
            "NoClassDefFoundError|ClassNotFoundException|UnsupportedClassVersionError"
                    + "|IncompatibleClassChangeError|NoSuchMethodError|NoSuchFieldError");

    /** Folia throws this from APIs that cannot work on a regionised server. */
    private static final Pattern RUNTIME_EXCEPTION =
            Pattern.compile("UnsupportedOperationException");

    /**
     * Memory exhaustion, both the in-flight kind and the "the JVM never started" kind.
     *
     * <p>The last three alternatives are printed by the JVM itself, before the server has
     * produced a single line of its own.</p>
     */
    private static final Pattern OUT_OF_MEMORY = Pattern.compile(
            "OutOfMemoryError"
                    + "|Could not reserve enough space"
                    + "|Error occurred during initialization of VM"
                    + "|There is insufficient memory");

    /** A stack trace frame. */
    private static final Pattern STACK_FRAME = Pattern.compile("^\\s*at\\s+([\\w.$]+)");

    private final String pluginName;
    private final String mainPackage;
    private final int configuredMemoryMb;

    /**
     * Creates an analyzer that does not know the configured heap size.
     *
     * @param pluginName the name of the plugin under test; may be {@code null}
     * @param mainClass  the fully qualified main class of the plugin; may be {@code null}
     */
    public BootLogAnalyzer(String pluginName, String mainClass) {
        this(pluginName, mainClass, 0);
    }

    /**
     * Creates an analyzer.
     *
     * @param pluginName         the name of the plugin under test; may be {@code null}
     * @param mainClass          the fully qualified main class of the plugin; may be {@code null}
     * @param configuredMemoryMb the heap the server was given, or {@code 0} if unknown.
     *                           Used only to make the out-of-memory advice concrete
     */
    public BootLogAnalyzer(String pluginName, String mainClass, int configuredMemoryMb) {
        this.pluginName = blankToNull(pluginName);
        this.mainPackage = packageOf(mainClass);
        this.configuredMemoryMb = Math.max(0, configuredMemoryMb);
    }

    /**
     * Whether a line already settles the outcome, so the caller can stop waiting.
     *
     * <p>Used to end a run as soon as the answer is known instead of always waiting for
     * the full boot timeout.</p>
     *
     * @param line a single log line; may be {@code null}
     * @return {@code true} when no further output can change the verdict
     */
    public boolean isDecisive(String line) {
        if (line == null) {
            return false;
        }
        return BOOT_COMPLETE.matcher(line).find()
                || OUT_OF_MEMORY.matcher(line).find()
                || UNKNOWN_DEPENDENCIES.matcher(line).find()
                || FOLIA_UNSUPPORTED.matcher(line).find();
    }

    /**
     * Classifies a complete boot log.
     *
     * @param logLines     every line the server produced, in order; may be {@code null}
     * @param bootDuration how long the boot ran; may be {@code null}
     * @param timedOut     whether the caller gave up waiting
     * @return the classification, with evidence attached to every issue
     */
    public ServerVerificationResult analyze(List<String> logLines, Duration bootDuration, boolean timedOut) {
        List<String> lines = logLines == null ? List.of() : List.copyOf(logLines);

        List<VerificationIssue> issues = new ArrayList<>();
        Set<String> missingDependencies = new LinkedHashSet<>();

        boolean bootCompleted = anyMatch(lines, BOOT_COMPLETE);
        boolean pluginEnabled = detectPluginEnabled(lines);

        detectOutOfMemory(lines, issues);
        detectMissingDependencies(lines, issues, missingDependencies);
        detectFoliaUnsupported(lines, issues);
        detectClassErrors(lines, issues);
        detectRuntimeExceptions(lines, issues);

        if (timedOut) {
            issues.add(new VerificationIssue(
                    VerificationOutcome.TIMEOUT,
                    IssueSeverity.FATAL,
                    "The server did not finish booting within the timeout. Increase the boot timeout, "
                            + "or check the log tail for a boot step that is hanging.",
                    lastLines(lines, 3)));
        }

        VerificationOutcome outcome =
                selectOutcome(issues, bootCompleted, pluginEnabled, timedOut);

        if (outcome == VerificationOutcome.PLUGIN_NOT_LOADED) {
            issues.add(new VerificationIssue(
                    VerificationOutcome.PLUGIN_NOT_LOADED,
                    IssueSeverity.ERROR,
                    "The server finished booting but "
                            + (pluginName == null ? "the plugin" : "'" + pluginName + "'")
                            + " was never enabled. Check that the jar is a valid plugin and that its "
                            + "plugin.yml declares the expected name.",
                    lastLines(lines, 3)));
        }

        List<VerificationIssue> ordered = orderByOutcomePriority(issues);
        return new ServerVerificationResult(
                outcome, bootDuration, ordered, List.copyOf(missingDependencies), lines, null);
    }

    /**
     * Chooses the single outcome that best describes the run.
     *
     * <p>The order is deliberate. Environment failures come first so that a server which
     * could not run is never reported as a broken plugin. Class and runtime errors are only
     * promoted to the outcome when the plugin did not otherwise load cleanly, or when the
     * evidence names the plugin, which keeps unrelated noise from other plugins out of the
     * verdict.</p>
     */
    private VerificationOutcome selectOutcome(
            List<VerificationIssue> issues, boolean bootCompleted, boolean pluginEnabled, boolean timedOut) {

        if (hasIssue(issues, VerificationOutcome.OUT_OF_MEMORY)) {
            return VerificationOutcome.OUT_OF_MEMORY;
        }
        if (hasIssue(issues, VerificationOutcome.MISSING_DEPENDENCY)) {
            return VerificationOutcome.MISSING_DEPENDENCY;
        }
        if (hasIssue(issues, VerificationOutcome.FOLIA_UNSUPPORTED)) {
            return VerificationOutcome.FOLIA_UNSUPPORTED;
        }

        boolean bootedCleanly = bootCompleted && pluginEnabled;
        if (shouldPromote(issues, VerificationOutcome.CLASS_ERROR, bootedCleanly)) {
            return VerificationOutcome.CLASS_ERROR;
        }
        if (shouldPromote(issues, VerificationOutcome.RUNTIME_EXCEPTION, bootedCleanly)) {
            return VerificationOutcome.RUNTIME_EXCEPTION;
        }
        if (timedOut) {
            return VerificationOutcome.TIMEOUT;
        }
        if (bootCompleted) {
            return pluginEnabled ? VerificationOutcome.SUCCESS : VerificationOutcome.PLUGIN_NOT_LOADED;
        }
        return VerificationOutcome.SERVER_FAILED;
    }

    /**
     * Decides whether an error should become the outcome or stay an advisory issue.
     *
     * @param bootedCleanly whether the server booted and the plugin was enabled anyway
     */
    private boolean shouldPromote(
            List<VerificationIssue> issues, VerificationOutcome type, boolean bootedCleanly) {
        List<VerificationIssue> matching = issues.stream().filter(i -> i.type() == type).toList();
        if (matching.isEmpty()) {
            return false;
        }
        if (!bootedCleanly) {
            return true;
        }
        // The plugin loaded and the server started; only blame it if its own name is in the trace.
        return matching.stream()
                .flatMap(issue -> issue.evidence().stream())
                .anyMatch(this::mentionsPlugin);
    }

    private void detectOutOfMemory(List<String> lines, List<VerificationIssue> issues) {
        List<String> evidence = collect(lines, OUT_OF_MEMORY);
        if (evidence.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(
                "The server JVM ran out of memory. This is a server configuration problem, "
                        + "not a defect in the plugin. ");
        if (configuredMemoryMb > 0) {
            message.append("The server was given ").append(configuredMemoryMb).append(" MB of heap");
            if (configuredMemoryMb < MINIMUM_VIABLE_HEAP_MB) {
                message.append(", which is below the ").append(MINIMUM_VIABLE_HEAP_MB)
                        .append(" MB a Folia server needs to start at all");
            }
            message.append(". ");
        }
        message.append("Raise the configured heap (memoryMb) to at least ")
                .append(MINIMUM_VIABLE_HEAP_MB)
                .append(" MB and run the verification again.");

        issues.add(new VerificationIssue(
                VerificationOutcome.OUT_OF_MEMORY, IssueSeverity.FATAL, message.toString(), evidence));
    }

    private void detectMissingDependencies(
            List<String> lines, List<VerificationIssue> issues, Set<String> missing) {

        List<String> evidence = new ArrayList<>();

        for (String line : lines) {
            if (line == null) {
                continue;
            }
            Matcher bracketed = UNKNOWN_DEPENDENCIES.matcher(line);
            while (bracketed.find()) {
                addNames(missing, bracketed.group(1));
                evidence.add(line);
            }
            Matcher quoted = QUOTED_DEPENDENCY.matcher(line);
            while (quoted.find()) {
                addName(missing, quoted.group(1));
                evidence.add(line);
            }
            if (evidence.contains(line)) {
                continue;
            }
            if (GENERIC_DEPENDENCY.matcher(line).find() || LOAD_FAILURE_WITH_DEPEND.matcher(line).find()) {
                evidence.add(line);
            }
        }

        if (evidence.isEmpty()) {
            return;
        }

        String message = missing.isEmpty()
                ? "The plugin depends on another plugin that is not installed, but the server did not "
                + "name it. Check the depend and softdepend entries in plugin.yml."
                : "The plugin requires " + describe(missing) + ", which "
                + (missing.size() == 1 ? "is" : "are") + " not installed. Install "
                + (missing.size() == 1 ? "it" : "them")
                + " alongside the plugin, or pass the jar(s) as extra plugins to the verifier.";

        issues.add(new VerificationIssue(
                VerificationOutcome.MISSING_DEPENDENCY, IssueSeverity.ERROR, message, evidence));
    }

    private void detectFoliaUnsupported(List<String> lines, List<VerificationIssue> issues) {
        List<String> evidence = collect(lines, FOLIA_UNSUPPORTED);
        if (evidence.isEmpty()) {
            return;
        }
        issues.add(new VerificationIssue(
                VerificationOutcome.FOLIA_UNSUPPORTED,
                IssueSeverity.ERROR,
                "Folia refused to load the plugin because it is not marked as supporting Folia. "
                        + "Add 'folia-supported: true' to plugin.yml once the plugin is actually "
                        + "region-thread safe.",
                evidence));
    }

    private void detectClassErrors(List<String> lines, List<VerificationIssue> issues) {
        List<String> evidence = collect(lines, CLASS_ERROR);
        if (evidence.isEmpty()) {
            return;
        }
        issues.add(new VerificationIssue(
                VerificationOutcome.CLASS_ERROR,
                IssueSeverity.ERROR,
                "A class failed to load, link or verify. This usually means the plugin was built "
                        + "against a different server or Java version than the one it is running on, "
                        + "or that a shaded library is missing.",
                evidence));
    }

    private void detectRuntimeExceptions(List<String> lines, List<VerificationIssue> issues) {
        List<String> evidence = new ArrayList<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            if (RUNTIME_EXCEPTION.matcher(line).find()) {
                evidence.add(line);
                continue;
            }
            // Stack frames only count when they point into the plugin under test.
            Matcher frame = STACK_FRAME.matcher(line);
            if (frame.find() && mentionsPlugin(line)) {
                evidence.add(line);
            }
        }
        if (evidence.isEmpty()) {
            return;
        }
        issues.add(new VerificationIssue(
                VerificationOutcome.RUNTIME_EXCEPTION,
                IssueSeverity.ERROR,
                "The plugin threw while loading. On Folia this is often an API that only exists on "
                        + "Paper, or a scheduler call that is not valid on a regionised server.",
                evidence));
    }

    /**
     * Detects whether the plugin under test reached the enabled state.
     *
     * <p>Paper logs {@code Enabling <name> v<version>} for each plugin it starts.</p>
     */
    private boolean detectPluginEnabled(List<String> lines) {
        if (pluginName == null) {
            return false;
        }
        Pattern enabling = Pattern.compile(
                "Enabling\\s+" + Pattern.quote(pluginName) + "(\\s|$|\\sv)", Pattern.CASE_INSENSITIVE);
        for (String line : lines) {
            if (line != null && enabling.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a line refers to the plugin under test, by name or by package. */
    private boolean mentionsPlugin(String line) {
        if (line == null) {
            return false;
        }
        if (mainPackage != null && line.contains(mainPackage)) {
            return true;
        }
        return pluginName != null && line.toLowerCase(Locale.ROOT).contains(pluginName.toLowerCase(Locale.ROOT));
    }

    /** Orders issues so the most decisive appear first. */
    private static List<VerificationIssue> orderByOutcomePriority(List<VerificationIssue> issues) {
        List<VerificationOutcome> priority = List.of(
                VerificationOutcome.OUT_OF_MEMORY,
                VerificationOutcome.MISSING_DEPENDENCY,
                VerificationOutcome.FOLIA_UNSUPPORTED,
                VerificationOutcome.CLASS_ERROR,
                VerificationOutcome.RUNTIME_EXCEPTION,
                VerificationOutcome.TIMEOUT,
                VerificationOutcome.PLUGIN_NOT_LOADED);
        List<VerificationIssue> sorted = new ArrayList<>(issues);
        sorted.sort((a, b) -> Integer.compare(indexOf(priority, a.type()), indexOf(priority, b.type())));
        return List.copyOf(sorted);
    }

    private static int indexOf(List<VerificationOutcome> priority, VerificationOutcome outcome) {
        int index = priority.indexOf(outcome);
        return index < 0 ? priority.size() : index;
    }

    private static boolean hasIssue(List<VerificationIssue> issues, VerificationOutcome type) {
        return issues.stream().anyMatch(issue -> issue.type() == type);
    }

    private static boolean anyMatch(List<String> lines, Pattern pattern) {
        return lines.stream().anyMatch(line -> line != null && pattern.matcher(line).find());
    }

    private static List<String> collect(List<String> lines, Pattern pattern) {
        return lines.stream().filter(line -> line != null && pattern.matcher(line).find()).toList();
    }

    private static List<String> lastLines(List<String> lines, int count) {
        if (lines.size() <= count) {
            return lines;
        }
        return List.copyOf(lines.subList(lines.size() - count, lines.size()));
    }

    /** Splits a comma separated dependency list into individual names. */
    private static void addNames(Set<String> target, String rawList) {
        if (rawList == null) {
            return;
        }
        for (String candidate : rawList.split(",")) {
            addName(target, candidate);
        }
    }

    private static void addName(Set<String> target, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = candidate.trim();
        if (!trimmed.isEmpty()) {
            target.add(trimmed);
        }
    }

    private static String describe(Set<String> names) {
        return names.size() == 1
                ? "'" + names.iterator().next() + "'"
                : names.stream().map(name -> "'" + name + "'").reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Extracts the package from a fully qualified class name. */
    private static String packageOf(String mainClass) {
        String trimmed = blankToNull(mainClass);
        if (trimmed == null) {
            return null;
        }
        int lastDot = trimmed.lastIndexOf('.');
        return lastDot <= 0 ? null : trimmed.substring(0, lastDot);
    }
}
