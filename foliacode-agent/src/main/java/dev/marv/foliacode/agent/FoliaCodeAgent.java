package dev.marv.foliacode.agent;

import dev.marv.foliacode.rules.UnsafeApiRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

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

        if (!makeRecorderVisibleToPlugins(instrumentation, out, options.quiet())) {
            // Weaving a call to a class the plugin's loader cannot see would replace the
            // problem being diagnosed with a NoClassDefFoundError. Better to observe
            // nothing than to break the thing under observation.
            return;
        }

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
     * The one package the woven code reaches, and which therefore has to be visible
     * from every class loader on the server.
     *
     * <p>Exactly one, for two reasons. Appending the whole FoliaCode JAR would put ASM
     * and SnakeYAML on the bootstrap class path, where they would shadow whatever
     * versions the server and its plugins expect — breaking the server in order to
     * observe it. And publishing part of a package splits it across two loaders, which
     * turns every package-private access into an {@link IllegalAccessError}; doing that
     * to {@code dev.marv.foliacode.agent} killed the JVM outright during development.</p>
     */
    private static final List<String> BOOTSTRAP_PACKAGES =
            List.of("dev/marv/foliacode/probe/");

    /**
     * Publishes the recorder where instrumented plugin code can actually reach it.
     *
     * <p>A {@code -javaagent} JAR lands on the system class path, and on a plain JVM
     * that is enough. Paper is not a plain JVM: it gives each plugin an isolated loader
     * that does not delegate arbitrary packages upwards, so a woven
     * {@code invokestatic RuntimeRecorder.observe} fails to link and the plugin dies
     * with a {@link NoClassDefFoundError} — caused entirely by the tool that was
     * supposed to be watching it.</p>
     *
     * <p>The bootstrap loader is the one loader every other loader delegates to, so a
     * minimal JAR of just the recorder's own packages goes there.</p>
     *
     * @param instrumentation the instrumentation handle
     * @param out             where messages go
     * @param quiet           whether to suppress success output
     * @return true if instrumentation can safely proceed
     */
    private static boolean makeRecorderVisibleToPlugins(
            Instrumentation instrumentation, PrintStream out, boolean quiet) {
        Path ownJar = ownJar();
        if (ownJar == null) {
            out.println("[FoliaCode] The agent is not running from a JAR, so its recorder "
                    + "cannot be published to the bootstrap class loader. Not instrumenting.");
            return false;
        }
        try {
            Path bootJar = Files.createTempFile("foliacode-agent-boot", ".jar");
            bootJar.toFile().deleteOnExit();
            int copied = extractBootstrapClasses(ownJar, bootJar);
            if (copied == 0) {
                out.println("[FoliaCode] Could not find the recorder classes inside "
                        + ownJar + ". Not instrumenting.");
                return false;
            }
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(bootJar.toFile()));
            if (!quiet) {
                out.println("[FoliaCode] Published " + copied
                        + " recorder classes to the bootstrap class loader.");
            }
            return true;
        } catch (IOException | RuntimeException e) {
            out.println("[FoliaCode] Could not publish the recorder to the bootstrap class "
                    + "loader (" + e + "). Not instrumenting.");
            return false;
        }
    }

    /**
     * Copies the recorder's packages out of the agent JAR into a minimal one.
     *
     * @param source the agent JAR
     * @param target where to write the minimal JAR
     * @return how many entries were copied
     * @throws IOException if either JAR cannot be read or written
     */
    private static int extractBootstrapClasses(Path source, Path target) throws IOException {
        int copied = 0;
        try (JarFile jar = new JarFile(source.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                boolean wanted = BOOTSTRAP_PACKAGES.stream().anyMatch(name::startsWith);
                if (!wanted) {
                    continue;
                }
                out.putNextEntry(new JarEntry(name));
                try (InputStream in = jar.getInputStream(entry)) {
                    in.transferTo(out);
                }
                out.closeEntry();
                copied++;
            }
        }
        return copied;
    }

    /**
     * Locates the JAR this agent is running from.
     *
     * @return the JAR path, or {@code null} if the agent is not running from one
     */
    private static Path ownJar() {
        try {
            CodeSource source = FoliaCodeAgent.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
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
