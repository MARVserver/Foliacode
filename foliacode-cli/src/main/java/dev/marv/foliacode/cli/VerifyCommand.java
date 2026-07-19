package dev.marv.foliacode.cli;

import dev.marv.foliacode.analysis.JarAnalyzer;
import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.verify.FoliaDownloader;
import dev.marv.foliacode.verify.ServerVerificationConfig;
import dev.marv.foliacode.verify.ServerVerificationResult;
import dev.marv.foliacode.verify.ServerVerifier;
import dev.marv.foliacode.verify.VerifyTextReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements the {@code verify} command: boot a real Folia server in a disposable
 * sandbox, observe whether the plugin actually loads, then tear it all down.
 *
 * <p>Static analysis reads bytecode. This reads reality. It catches the things
 * bytecode cannot show — a dependency plugin that is simply not installed, a class
 * compiled for a newer Java release, a plugin that throws the moment it is enabled.</p>
 *
 * <p>Because it downloads and executes a third-party server jar, it never runs
 * implicitly. Either {@code foliacode.yml} enables it or the caller passes
 * {@code --yes}. An assistant driving this tool is expected to ask its user first.</p>
 */
final class VerifyCommand {

    /** Exit code: the server booted and the plugin was enabled. */
    static final int EXIT_OK = 0;

    /** Exit code: the boot failed for a reason attributable to the plugin. */
    static final int EXIT_PLUGIN_PROBLEM = 1;

    /** Exit code: the command could not run at all. */
    static final int EXIT_ERROR = 2;

    /**
     * Exit code: the verification environment failed, not the plugin.
     *
     * <p>Kept distinct from {@link #EXIT_PLUGIN_PROBLEM} on purpose. An out-of-memory
     * server must never be reported to a caller as a broken plugin.</p>
     */
    static final int EXIT_ENVIRONMENT_PROBLEM = 3;

    private VerifyCommand() {
    }

    /**
     * Runs the command.
     *
     * @param args     the full argument array, including {@code verify}
     * @param baseDir  directory to look for {@code foliacode.yml} in
     * @param out      standard output
     * @param err      standard error
     * @return the exit code
     */
    static int run(String[] args, Path baseDir, PrintStream out, PrintStream err) {
        VerifyOptions options;
        try {
            options = VerifyOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Invalid arguments: " + e.getMessage());
            return EXIT_ERROR;
        }

        if (!Files.isRegularFile(options.pluginJar())) {
            err.println("Plugin JAR not found: " + options.pluginJar());
            return EXIT_ERROR;
        }

        FoliacodeConfig fileConfig;
        try {
            fileConfig = FoliacodeConfig.loadFrom(baseDir);
        } catch (IOException e) {
            err.println("Could not read " + FoliacodeConfig.FILE_NAME + ": " + e.getMessage());
            return EXIT_ERROR;
        }

        ServerVerificationConfig config = options.applyTo(fileConfig.serverVerification());

        if (!config.enabled()) {
            printConsentRequired(err);
            return EXIT_ERROR;
        }

        String pluginName = readPluginName(options.pluginJar());

        if (!config.hasViableHeap()) {
            out.println("Note: " + config.memoryMb() + " MB is below what a Folia server needs to start.");
            out.println("      Continuing as requested; expect an OUT_OF_MEMORY result.");
            out.println();
        }

        out.println("Booting a temporary Folia " + config.minecraftVersion() + " server ("
                + config.memoryMb() + " MB heap)...");
        out.println("The server jar is downloaded to a local cache and checksum-verified.");
        out.println();

        List<String> jvmOptions = List.of();
        Path runtimeReport = null;
        if (options.instrument()) {
            Path agentJar = SelfLocator.runningJar();
            if (agentJar == null) {
                err.println("--instrument needs the runtime agent, which lives in the FoliaCode "
                        + "JAR. Build it with ./gradlew :foliacode-cli:fatJar and run that.");
                return EXIT_ERROR;
            }
            runtimeReport = baseDir.resolve(pluginName + "-runtime.txt");
            String include = AgentCommand.readMainPackage(options.pluginJar());
            jvmOptions = List.of("-javaagent:" + agentJar + "="
                    + "report=" + baseDir.resolve(pluginName + "-runtime.json")
                    + ",text=" + runtimeReport
                    + (include == null ? "" : ",include=" + include)
                    + ",quiet=true");
            out.println("Instrumenting the plugin to record what actually runs"
                    + (include == null ? "." : ", watching " + include + "."));
            out.println();
        }

        ServerVerificationResult result;
        try {
            result = new ServerVerifier(config, new FoliaDownloader(), null, jvmOptions)
                    .verify(options.pluginJar(), pluginName, null);
        } catch (IOException e) {
            err.println("Verification could not run: " + e.getMessage());
            return EXIT_ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Verification was interrupted.");
            return EXIT_ERROR;
        }

        out.print(VerifyTextReport.render(result, pluginName, config));

        if (runtimeReport != null) {
            printRuntimeReport(runtimeReport, out);
        }

        if (result.isSuccess()) {
            return EXIT_OK;
        }
        return result.isAttributableToPlugin() ? EXIT_PLUGIN_PROBLEM : EXIT_ENVIRONMENT_PROBLEM;
    }

    /**
     * Prints what the runtime agent observed, if it produced anything.
     *
     * <p>The agent writes its report from a shutdown hook. A server killed rather than
     * stopped never reaches it, so a missing file is reported as a missing file rather
     * than as an absence of findings.</p>
     *
     * @param reportPath where the agent was told to write
     * @param out        standard output
     */
    private static void printRuntimeReport(Path reportPath, PrintStream out) {
        out.println();
        try {
            if (!Files.isRegularFile(reportPath)) {
                out.println("The runtime agent produced no report. The server did not shut down "
                        + "cleanly enough to write one.");
                return;
            }
            out.print(Files.readString(reportPath));
        } catch (IOException e) {
            out.println("Could not read the runtime report: " + e.getMessage());
        }
    }

    /**
     * Explains why nothing was run and how to opt in.
     *
     * @param err standard error
     */
    private static void printConsentRequired(PrintStream err) {
        err.println("""
                Server verification is disabled.

                This command downloads a Folia server jar and executes it on this machine,
                so it never runs unless you say so. Enable it in one of two ways:

                  1. Pass --yes on the command line, or
                  2. Create foliacode.yml next to your project:

                     serverVerification:
                       enabled: true
                       minecraftVersion: "1.21.4"
                       memoryMb: 2048

                If an AI assistant is running FoliaCode on your behalf, it should ask you
                before enabling this.
                """);
    }

    /**
     * Reads the plugin's declared name so log matching and the report can use it.
     *
     * @param jar the plugin jar
     * @return the declared name, or the file name if it cannot be read
     */
    private static String readPluginName(Path jar) {
        try {
            AnalysisReport report = new JarAnalyzer().analyze(jar);
            String name = report.plugin().name();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (IOException | RuntimeException e) {
            // Falling back to the file name is fine; this is only used for log matching.
        }
        String fileName = jar.getFileName().toString();
        return fileName.endsWith(".jar")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
    }

    /**
     * Command-line options for {@code verify}, layered on top of the config file.
     *
     * @param pluginJar    the plugin to verify
     * @param consent      whether {@code --yes} was passed
     * @param version      Minecraft version override, or {@code null}
     * @param memoryMb     heap override, or {@code null}
     * @param timeout      boot timeout override, or {@code null}
     * @param keepServer   whether to keep the sandbox directory
     * @param extraPlugins dependency jars to install alongside
     * @param instrument   whether to attach the runtime agent for the run
     */
    record VerifyOptions(
            Path pluginJar,
            boolean consent,
            String version,
            Integer memoryMb,
            Duration timeout,
            boolean keepServer,
            List<Path> extraPlugins,
            boolean instrument
    ) {

        /**
         * Parses the arguments.
         *
         * @param args the argument array, including {@code verify}
         * @return the parsed options
         * @throws IllegalArgumentException if the arguments are invalid
         */
        static VerifyOptions parse(String[] args) {
            if (args.length < 2) {
                throw new IllegalArgumentException("No plugin JAR given");
            }
            Path jar = Path.of(args[1]);
            boolean consent = false;
            String version = null;
            Integer memoryMb = null;
            Duration timeout = null;
            boolean keepServer = false;
            boolean instrument = false;
            List<Path> extras = new ArrayList<>();

            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--yes" -> consent = true;
                    case "--keep-server" -> keepServer = true;
                    case "--instrument" -> instrument = true;
                    case "--mc-version" -> version = requireValue(args, ++i, "--mc-version");
                    case "--memory" -> memoryMb = parsePositive(
                            requireValue(args, ++i, "--memory"), "--memory");
                    case "--timeout" -> timeout = Duration.ofSeconds(parsePositive(
                            requireValue(args, ++i, "--timeout"), "--timeout"));
                    case "--with" -> extras.add(Path.of(requireValue(args, ++i, "--with")));
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new VerifyOptions(
                    jar, consent, version, memoryMb, timeout, keepServer, extras, instrument);
        }

        /**
         * Layers these options over the configuration read from file.
         *
         * @param base the configuration from {@code foliacode.yml}
         * @return the effective configuration
         */
        ServerVerificationConfig applyTo(ServerVerificationConfig base) {
            ServerVerificationConfig result = new ServerVerificationConfig(
                    base.enabled() || consent,
                    version != null ? version : base.minecraftVersion(),
                    memoryMb != null ? memoryMb : base.memoryMb(),
                    timeout != null ? timeout : base.bootTimeout(),
                    keepServer || base.keepServerDirectory(),
                    base.extraPlugins());
            return extraPlugins.isEmpty() ? result : result.withExtraPlugins(extraPlugins);
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

        /**
         * Parses a strictly positive integer.
         *
         * @param value  the text
         * @param option the option name, for the error message
         * @return the parsed value
         * @throws IllegalArgumentException if it is not a positive integer
         */
        private static int parsePositive(String value, String option) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed <= 0) {
                    throw new IllegalArgumentException(option + " must be greater than zero");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " expects a number, got: " + value);
            }
        }
    }
}
