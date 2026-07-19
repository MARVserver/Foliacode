package dev.marv.foliacode.verify;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Boots a real Folia server with a plugin installed and reports what happened.
 *
 * <p>The run is download, sandbox, launch, analyse, tear down. Teardown is the part that
 * matters most: this class starts an external process and writes to a temporary directory,
 * and it must leave neither behind under any exit path, including an exception, a timeout,
 * or the user pressing Ctrl-C.</p>
 *
 * <p>Three mechanisms cover those paths. A {@code finally} block covers normal returns and
 * exceptions. A JVM shutdown hook, registered only for the duration of the run, covers
 * abnormal termination of the calling process. Cleanup itself escalates to
 * {@link Process#destroyForcibly()} so a server that ignores {@code stop} still dies. All
 * three funnel through one idempotent cleanup, so running twice is harmless.</p>
 */
public final class ServerVerifier {

    /** How long the server is given to stop gracefully before it is destroyed. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(15);

    /** How often the boot loop checks whether a conclusion has been reached. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    private final ServerVerificationConfig config;
    private final FoliaDownloader downloader;
    private final Consumer<String> logConsumer;
    private final List<String> jvmOptions;

    /**
     * Creates a verifier with the default downloader and no log forwarding.
     *
     * @param config the run settings
     */
    public ServerVerifier(ServerVerificationConfig config) {
        this(config, new FoliaDownloader(), null);
    }

    /**
     * Creates a verifier.
     *
     * @param config      the run settings
     * @param downloader  resolves and caches the server jar
     * @param logConsumer receives every server log line as it is produced; may be {@code null}
     */
    public ServerVerifier(
            ServerVerificationConfig config, FoliaDownloader downloader, Consumer<String> logConsumer) {
        this(config, downloader, logConsumer, List.of());
    }

    /**
     * Creates a verifier that passes extra options to the server JVM.
     *
     * @param config      the run settings
     * @param downloader  resolves and caches the server jar
     * @param logConsumer receives every server log line as it is produced; may be {@code null}
     * @param jvmOptions  options for the server JVM, such as a {@code -javaagent} that
     *                    instruments the plugin under test
     */
    public ServerVerifier(
            ServerVerificationConfig config,
            FoliaDownloader downloader,
            Consumer<String> logConsumer,
            List<String> jvmOptions) {
        this.config = Objects.requireNonNull(config, "config");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.logConsumer = logConsumer == null ? line -> { } : logConsumer;
        this.jvmOptions = jvmOptions == null ? List.of() : List.copyOf(jvmOptions);
    }

    /** The configuration this verifier runs with. */
    public ServerVerificationConfig config() {
        return config;
    }

    /** Extra options handed to the server JVM. */
    public List<String> jvmOptions() {
        return jvmOptions;
    }

    /**
     * Boots a server with the plugin installed and classifies the result.
     *
     * @param pluginJar the plugin to verify
     * @param pluginName the plugin's declared name, used to detect whether it was enabled;
     *                   may be {@code null}
     * @param mainClass the plugin's main class, used to attribute stack traces;
     *                  may be {@code null}
     * @return the classified result
     * @throws IllegalStateException if the configuration is not enabled
     * @throws IOException           if the server jar or sandbox cannot be prepared
     * @throws InterruptedException  if the calling thread is interrupted
     */
    public ServerVerificationResult verify(Path pluginJar, String pluginName, String mainClass)
            throws IOException, InterruptedException {

        Objects.requireNonNull(pluginJar, "pluginJar");
        requireEnabled();

        Path serverJar = downloader.download(config.minecraftVersion());
        ServerSandbox sandbox = ServerSandbox.create(pluginJar, config.extraPlugins());

        BootLogAnalyzer analyzer =
                new BootLogAnalyzer(pluginName, mainClass, config.memoryMb());
        ServerLauncher launcher = new ServerLauncher(
                serverJar, sandbox.root(), config.memoryMb(), logConsumer,
                ServerLauncher.DEFAULT_MAX_RETAINED_LINES, jvmOptions);

        // One idempotent cleanup, reachable from every exit path.
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> {
            if (cleaned.compareAndSet(false, true)) {
                launcher.shutdown(SHUTDOWN_GRACE);
                if (!config.keepServerDirectory()) {
                    sandbox.delete();
                }
            }
        };

        Thread shutdownHook = new Thread(cleanup, "foliacode-verify-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        Instant started = Instant.now();
        try {
            launcher.start();
            boolean timedOut = awaitConclusion(launcher, analyzer);
            Duration bootDuration = Duration.between(started, Instant.now());

            // Stop the server before analysing so the log stops moving under us.
            launcher.shutdown(SHUTDOWN_GRACE);

            ServerVerificationResult result =
                    analyzer.analyze(launcher.logTail(), bootDuration, timedOut);
            return withVersion(result);
        } finally {
            cleanup.run();
            removeShutdownHook(shutdownHook);
        }
    }

    /**
     * Waits until the log settles the question, the process dies, or the timeout expires.
     *
     * @return {@code true} if the wait ended because of the timeout
     */
    private boolean awaitConclusion(ServerLauncher launcher, BootLogAnalyzer analyzer)
            throws InterruptedException {

        Instant deadline = Instant.now().plus(config.bootTimeout());
        int examined = 0;

        while (Instant.now().isBefore(deadline)) {
            List<String> lines = launcher.logTail();
            // Only look at lines we have not judged yet; the tail is bounded, so if it has
            // rotated past us the boot is producing far more output than any conclusion needs.
            for (int i = Math.min(examined, lines.size()); i < lines.size(); i++) {
                if (analyzer.isDecisive(lines.get(i))) {
                    return false;
                }
            }
            examined = lines.size();

            if (!launcher.isRunning()) {
                return false;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        return true;
    }

    /**
     * Fails unless the caller explicitly opted in.
     *
     * @throws IllegalStateException if the configuration is not enabled
     */
    private void requireEnabled() {
        if (!config.enabled()) {
            throw new IllegalStateException(
                    "Server verification is disabled. It downloads and executes a third party Folia "
                            + "server jar, so it is opt-in: set enabled=true in ServerVerificationConfig "
                            + "to run it.");
        }
    }

    private ServerVerificationResult withVersion(ServerVerificationResult result) {
        return new ServerVerificationResult(
                result.outcome(),
                result.bootDuration(),
                result.issues(),
                result.missingDependencies(),
                result.logTail(),
                config.minecraftVersion());
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down, so the hook is running or has run. Nothing to do.
        }
    }
}
