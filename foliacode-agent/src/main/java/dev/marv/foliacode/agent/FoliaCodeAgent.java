package dev.marv.foliacode.agent;

import dev.marv.foliacode.rules.UnsafeApiRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the runtime agent.
 *
 * <p>Static analysis says what a plugin <em>could</em> do. This says what it did.
 * The two answer different questions, and the gap between them is where the
 * interesting cases live: a CRITICAL finding on a code path nobody ever calls, or
 * an INFO finding behind reflection that turns out to run on every tick.</p>
 *
 * <p>The agent is read-only with respect to plugin behaviour. It adds counters and
 * writes a file at shutdown; it never blocks a call, changes a result, or throws
 * into plugin code. A diagnostic that alters what it measures is worthless, and one
 * that stops a server from booting is worse than worthless.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * java -javaagent:foliacode.jar=report=runtime.json,include=com.example -jar folia.jar
 * </pre>
 */
public final class FoliaCodeAgent {

    private static volatile InstrumentingTransformer transformer;

    private static volatile boolean started;

    private FoliaCodeAgent() {
    }

    /**
     * Called by the JVM before {@code main} when the agent is loaded with
     * {@code -javaagent}.
     *
     * @param args            the agent argument string
     * @param instrumentation the instrumentation handle
     */
    public static void premain(String args, Instrumentation instrumentation) {
        start(args, instrumentation, System.out);
    }

    /**
     * Called when the agent is attached to an already-running JVM.
     *
     * <p>Classes loaded before the attach are not retransformed, so a late attach
     * sees only what loads afterwards. That is stated in the output rather than
     * left for the reader to discover from a suspiciously empty report.</p>
     *
     * @param args            the agent argument string
     * @param instrumentation the instrumentation handle
     */
    public static void agentmain(String args, Instrumentation instrumentation) {
        start(args, instrumentation, System.out);
    }

    /**
     * Installs the transformer and arranges for the report to be written.
     *
     * @param args            the agent argument string
     * @param instrumentation the instrumentation handle
     * @param out             where the agent's own messages go
     */
    static synchronized void start(String args, Instrumentation instrumentation, PrintStream out) {
        if (started) {
            return;
        }
        started = true;

        AgentOptions options = AgentOptions.parse(args);
        InstrumentingTransformer installed =
                new InstrumentingTransformer(new UnsafeApiRegistry(), options.includePackages());
        transformer = installed;
        instrumentation.addTransformer(installed);

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> writeReport(options, out), "foliacode-agent-report"));

        if (!options.quiet()) {
            out.println("[FoliaCode] Runtime agent active. Report: "
                    + options.reportPath().toAbsolutePath());
            if (!options.includePackages().isEmpty()) {
                out.println("[FoliaCode] Instrumenting: "
                        + String.join(", ", options.includePackages()).replace('/', '.'));
            }
        }
    }

    /**
     * Takes a snapshot of what was observed.
     *
     * @return the report
     */
    public static RuntimeReport report() {
        InstrumentingTransformer current = transformer;
        return new RuntimeReport(
                current == null ? 0 : current.instrumentedClassCount(),
                current == null ? 0 : current.methodReferenceSiteCount(),
                RuntimeRecorder.snapshot());
    }

    /**
     * Writes the report at shutdown.
     *
     * <p>Runs on the shutdown path, where throwing would produce a confusing stack
     * trace at the end of an otherwise clean server stop. Failures are reported as
     * one line and swallowed.</p>
     *
     * @param options the agent options
     * @param out     where messages go
     */
    private static void writeReport(AgentOptions options, PrintStream out) {
        try {
            RuntimeReport report = report();
            Path path = options.reportPath();
            writeTo(path, RuntimeJsonReport.render(report));
            if (options.textPath() != null) {
                writeTo(options.textPath(), RuntimeTextReport.render(report));
            }
            if (!options.quiet()) {
                out.println("[FoliaCode] Wrote runtime report: " + path.toAbsolutePath()
                        + " (" + report.executed().size() + " of " + report.instrumentedSites()
                        + " watched call sites executed)");
            }
        } catch (IOException | RuntimeException e) {
            out.println("[FoliaCode] Could not write the runtime report: " + e);
        }
    }

    /**
     * Writes one report file, creating the directory if needed.
     *
     * @param path    where to write
     * @param content what to write
     * @throws IOException if writing fails
     */
    private static void writeTo(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /** Resets agent state. Used by tests. */
    static synchronized void resetForTesting() {
        started = false;
        transformer = null;
        RuntimeRecorder.reset();
    }
}
