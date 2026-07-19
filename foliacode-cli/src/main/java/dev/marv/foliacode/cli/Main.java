package dev.marv.foliacode.cli;

import dev.marv.foliacode.analysis.JarAnalyzer;
import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.UnsafeApi;
import dev.marv.foliacode.report.JsonReport;
import dev.marv.foliacode.report.TextReport;
import dev.marv.foliacode.rules.UnsafeApiRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Command-line entry point for FoliaCode.
 *
 * <p>For now this only analyses; it never rewrites a JAR. Diagnosis has to be
 * trustworthy and legible first — transformation can come once that
 * foundation is in place.</p>
 */
public final class Main {

    /** Success: nothing at or above the threshold. */
    private static final int EXIT_OK = 0;

    /** Findings at or above the threshold. */
    private static final int EXIT_FINDINGS = 1;

    /** Execution error. */
    private static final int EXIT_ERROR = 2;

    private Main() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(run(args, out, err));
    }

    /**
     * Runs a command.
     *
     * @param args command-line arguments
     * @param out  standard output
     * @param err  standard error
     * @return the exit code
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || isHelpRequest(args[0])) {
            printUsage(out);
            return args.length == 0 ? EXIT_ERROR : EXIT_OK;
        }

        String command = args[0];
        return switch (command) {
            case "analyze" -> runAnalyze(args, out, err);
            case "verify" -> VerifyCommand.run(args, Path.of("."), out, err);
            case "transform" -> TransformCommand.run(args, out, err);
            case "agent" -> AgentCommand.run(args, out, err);
            case "rules" -> runRules(out);
            default -> {
                err.println("Unknown command: " + command);
                printUsage(err);
                yield EXIT_ERROR;
            }
        };
    }

    /**
     * Runs the {@code analyze} command.
     *
     * @param args the arguments
     * @param out  standard output
     * @param err  standard error
     * @return the exit code
     */
    private static int runAnalyze(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Invalid arguments: " + e.getMessage());
            printUsage(err);
            return EXIT_ERROR;
        }

        List<Path> targets;
        try {
            targets = resolveTargets(options.target());
        } catch (IOException e) {
            err.println("Cannot read target: " + e.getMessage());
            return EXIT_ERROR;
        }

        if (targets.isEmpty()) {
            err.println("No JAR found to analyse: " + options.target());
            return EXIT_ERROR;
        }

        JarAnalyzer analyzer = new JarAnalyzer();
        List<AnalysisReport> reports = new ArrayList<>();
        for (Path target : targets) {
            try {
                reports.add(analyzer.analyze(target));
            } catch (IOException e) {
                err.println("Analysis failed (" + target.getFileName() + "): " + e.getMessage());
                return EXIT_ERROR;
            }
        }

        for (AnalysisReport report : reports) {
            out.print(TextReport.render(report, options.verbose()));
            out.println();
        }

        if (options.jsonOutput() != null) {
            try {
                writeJson(options.jsonOutput(), reports);
                out.println("Wrote JSON to: " + options.jsonOutput());
            } catch (IOException e) {
                err.println("Failed to write JSON: " + e.getMessage());
                return EXIT_ERROR;
            }
        }

        boolean tripped = reports.stream().anyMatch(r -> r.hasFindingsAtLeast(options.failOn()));
        return tripped ? EXIT_FINDINGS : EXIT_OK;
    }

    /**
     * Writes several reports out as JSON.
     *
     * @param path    the output path
     * @param reports the reports
     * @throws IOException if writing fails
     */
    private static void writeJson(Path path, List<AnalysisReport> reports) throws IOException {
        StringBuilder json = new StringBuilder();
        if (reports.size() == 1) {
            json.append(JsonReport.render(reports.get(0)));
        } else {
            json.append("[\n");
            for (int i = 0; i < reports.size(); i++) {
                json.append(JsonReport.render(reports.get(i)).stripTrailing());
                json.append(i < reports.size() - 1 ? ",\n" : "\n");
            }
            json.append("]\n");
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Runs the {@code rules} command, listing every registered rule.
     *
     * @param out standard output
     * @return the exit code
     */
    private static int runRules(PrintStream out) {
        UnsafeApiRegistry registry = new UnsafeApiRegistry();
        out.println("Registered rules: " + registry.size());
        out.println();
        out.printf("%-10s %-18s %s%n", "SEVERITY", "CATEGORY", "API");
        out.println("-".repeat(72));
        registry.rules().stream()
                .sorted(Comparator
                        .comparingInt((UnsafeApi r) -> r.severity().ordinal())
                        .thenComparing(UnsafeApi::owner)
                        .thenComparing(UnsafeApi::methodName))
                .forEach(rule -> out.printf("%-10s %-18s %s%n",
                        rule.severity().label(),
                        rule.category().displayName(),
                        rule.displayName()));
        return EXIT_OK;
    }

    /**
     * Resolves what to analyse. A directory expands to the JARs directly
     * inside it.
     *
     * @param target the target path
     * @return the JAR paths
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> resolveTargets(Path target) throws IOException {
        if (Files.isRegularFile(target)) {
            return List.of(target);
        }
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(target)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Tests whether an argument asks for help.
     *
     * @param arg the argument
     * @return true if it is a help request
     */
    private static boolean isHelpRequest(String arg) {
        return "--help".equals(arg) || "-h".equals(arg) || "help".equals(arg);
    }

    /**
     * Prints usage information.
     *
     * @param out the destination
     */
    private static void printUsage(PrintStream out) {
        out.println("""
                FoliaCode — Folia compatibility analyzer for Bukkit plugins

                Usage:
                  foliacode analyze   <JAR or directory> [options]
                  foliacode verify    <JAR> [options]
                  foliacode transform <JAR> [options]
                  foliacode agent     [JAR]
                  foliacode rules
                  foliacode --help

                analyze — static bytecode analysis. Fast, offline, no side effects.
                  --json <file>         Write the analysis result as JSON
                  --fail-on <severity>  Exit with code 1 when a finding at or above this
                                        severity is present
                                        (CRITICAL / HIGH / MEDIUM / INFO, default: CRITICAL)
                  --verbose             List every call site instead of truncating

                verify — boot a real Folia server in a throwaway sandbox and watch the
                  plugin load, then shut it down and delete it. Downloads and executes a
                  third-party server jar, so it is opt-in.
                  --yes                 Consent to downloading and running a Folia server
                  --mc-version <ver>    Minecraft version (default: 1.21.4)
                  --memory <MB>         Server heap in MB (default: 1024)
                  --timeout <seconds>   How long to wait for the boot (default: 180)
                  --with <jar>          Install a dependency plugin alongside; repeatable
                  --keep-server         Do not delete the sandbox directory afterwards
                  --instrument          Attach the runtime agent and report which flagged
                                        calls actually ran, and on which threads

                transform — rewrite the call sites that can be rewritten safely, and report
                  the ones that cannot. The original JAR is never modified.
                  --out <file>          Where to write (default: <name>-folia.jar beside it)
                  --json <file>         Write the transform result as JSON
                  --dry-run             Report what would change without writing a JAR

                agent — print the -javaagent line that attaches the runtime agent to a
                  server you run yourself. Pass your plugin JAR to have the package filter
                  worked out for you.

                Configuration:
                  foliacode.yml in the working directory can enable verification
                  permanently:

                    serverVerification:
                      enabled: true
                      minecraftVersion: "1.21.4"
                      memoryMb: 2048

                Exit codes:
                  0  Success — nothing at or above the threshold / the plugin loaded
                  1  The plugin is at fault — findings, or a boot failure caused by it
                  2  Execution error — bad arguments, missing file, consent not given
                  3  The environment is at fault — server ran out of memory or timed out

                Examples:
                  foliacode analyze MyPlugin.jar
                  foliacode analyze plugins/ --fail-on HIGH --json report.json
                  foliacode verify MyPlugin.jar --yes --memory 2048
                  foliacode verify MyPlugin.jar --yes --with Vault.jar
                  foliacode verify MyPlugin.jar --yes --instrument
                  foliacode transform MyPlugin.jar --dry-run
                  foliacode transform MyPlugin.jar --out MyPlugin-folia.jar
                """);
    }

    /**
     * Options for the {@code analyze} command.
     *
     * @param target     what to analyse
     * @param jsonOutput where to write JSON, or {@code null} if not requested
     * @param failOn     the severity threshold that produces exit code 1
     * @param verbose    whether to list every call site
     */
    record Options(Path target, Path jsonOutput, Severity failOn, boolean verbose) {

        /**
         * Parses the arguments.
         *
         * @param args the argument array, including {@code analyze}
         * @return the parsed options
         * @throws IllegalArgumentException if the arguments are invalid
         */
        static Options parse(String[] args) {
            if (args.length < 2) {
                throw new IllegalArgumentException("No analysis target given");
            }
            Path target = Path.of(args[1]);
            Path json = null;
            Severity failOn = Severity.CRITICAL;
            boolean verbose = false;

            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--json" -> {
                        json = Path.of(requireValue(args, ++i, "--json"));
                    }
                    case "--fail-on" -> {
                        failOn = Severity.parse(requireValue(args, ++i, "--fail-on"));
                    }
                    case "--verbose" -> verbose = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new Options(target, json, failOn, verbose);
        }

        /**
         * Reads the value that follows an option.
         *
         * @param args   the argument array
         * @param index  position of the value
         * @param option the option name, for the error message
         * @return the value
         * @throws IllegalArgumentException if no value is present
         */
        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
