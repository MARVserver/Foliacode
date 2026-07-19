package dev.marv.foliacode.cli;

import dev.marv.foliacode.analysis.JarAnalyzer;
import dev.marv.foliacode.model.AnalysisReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Implements the {@code agent} command: print the exact command line that attaches
 * the runtime agent to a server.
 *
 * <p>It prints rather than launches. Starting somebody's production server is not
 * this tool's business, and the command they need is short enough to paste.</p>
 */
final class AgentCommand {

    /** Exit code: the instructions were printed. */
    static final int EXIT_OK = 0;

    /** Exit code: the command could not run. */
    static final int EXIT_ERROR = 2;

    private AgentCommand() {
    }

    /**
     * Runs the command.
     *
     * @param args the full argument array, including {@code agent}
     * @param out  standard output
     * @param err  standard error
     * @return the exit code
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Path agentJar = SelfLocator.runningJar();
        if (agentJar == null) {
            err.println("""
                    The runtime agent lives in the FoliaCode JAR, and FoliaCode is not
                    running from one right now — this looks like a build directory or an IDE.

                    Build it first:

                      ./gradlew :foliacode-cli:fatJar

                    then run this command again from the resulting JAR.""");
            return EXIT_ERROR;
        }

        String include = null;
        if (args.length > 1) {
            Path pluginJar = Path.of(args[1]);
            if (!Files.isRegularFile(pluginJar)) {
                err.println("Plugin JAR not found: " + pluginJar);
                return EXIT_ERROR;
            }
            include = readMainPackage(pluginJar);
        }

        out.println("FoliaCode runtime agent");
        out.println("=".repeat(72));
        out.println("""
                Static analysis lists the calls that could break. The agent records which
                of them actually ran, how often, and whether the server considered that
                thread a tick thread.

                Add this to your server's start command:""");
        out.println();

        String options = "report=foliacode-runtime.json,text=foliacode-runtime.txt"
                + (include == null ? "" : ",include=" + include);
        out.println("  -javaagent:" + agentJar + "=" + options);
        out.println();

        if (include == null) {
            out.println("""
                    Without include= the agent instruments every class outside the JDK and
                    the server, which is more than you need. Pass your plugin JAR to this
                    command and it will work out the package for you:

                      foliacode agent MyPlugin.jar""");
            out.println();
        }

        out.println("""
                Reports are written when the server shuts down, so stop it cleanly.

                Options:
                  report=<file>   JSON report          (default foliacode-runtime.json)
                  text=<file>     readable report      (default: none)
                  include=<pkgs>  packages to watch, separated by ;
                  quiet           suppress the agent's own console output

                The agent only counts. It never blocks a call, changes a result, or throws
                into plugin code.

                To have `verify` do all of this for you inside a throwaway server:

                  foliacode verify MyPlugin.jar --yes --instrument""");
        return EXIT_OK;
    }

    /**
     * Works out the package to instrument from the plugin's main class.
     *
     * @param pluginJar the plugin
     * @return the package name, or {@code null} if it cannot be determined
     */
    static String readMainPackage(Path pluginJar) {
        try {
            AnalysisReport report = new JarAnalyzer().analyze(pluginJar);
            String mainClass = report.plugin().mainClass();
            if (mainClass == null || mainClass.isBlank()) {
                return null;
            }
            int lastDot = mainClass.lastIndexOf('.');
            if (lastDot < 0) {
                return null;
            }
            return mainClass.substring(0, lastDot);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
