package dev.marv.foliacode.verify;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Starts a server jar and owns the process for its lifetime.
 *
 * <p>Output is streamed line by line to a consumer and simultaneously retained in a bounded
 * buffer. The bound matters: a plugin stuck in a logging loop can emit output faster than
 * anything reads it, and a verification tool that dies of an out-of-memory error while
 * diagnosing an out-of-memory error is not useful.</p>
 *
 * <p>Shutdown escalates: {@code stop} on stdin, then {@link Process#destroy()}, then
 * {@link Process#destroyForcibly()} including descendants. The last step always runs, so no
 * stray server process outlives the run.</p>
 */
public final class ServerLauncher implements AutoCloseable {

    /** Default number of log lines retained in memory. */
    public static final int DEFAULT_MAX_RETAINED_LINES = 2000;

    /** How long each shutdown stage is given before escalating. */
    private static final Duration STAGE_TIMEOUT = Duration.ofSeconds(10);

    private final Path serverJar;
    private final Path workingDirectory;
    private final int memoryMb;
    private final Consumer<String> lineConsumer;
    private final int maxRetainedLines;
    private final List<String> jvmOptions;
    private final Deque<String> retainedLines = new ArrayDeque<>();

    private Process process;
    private Thread readerThread;

    /**
     * Creates a launcher with the default log retention.
     *
     * @param serverJar        the server jar to run
     * @param workingDirectory the directory to run it in
     * @param memoryMb         the heap size, used for both {@code -Xms} and {@code -Xmx}
     * @param lineConsumer     receives every output line; may be {@code null}
     */
    public ServerLauncher(Path serverJar, Path workingDirectory, int memoryMb, Consumer<String> lineConsumer) {
        this(serverJar, workingDirectory, memoryMb, lineConsumer, DEFAULT_MAX_RETAINED_LINES);
    }

    /**
     * Creates a launcher.
     *
     * @param serverJar        the server jar to run
     * @param workingDirectory the directory to run it in
     * @param memoryMb         the heap size, used for both {@code -Xms} and {@code -Xmx}
     * @param lineConsumer     receives every output line; may be {@code null}
     * @param maxRetainedLines how many trailing lines to keep in memory; must be positive
     */
    public ServerLauncher(
            Path serverJar,
            Path workingDirectory,
            int memoryMb,
            Consumer<String> lineConsumer,
            int maxRetainedLines) {
        this(serverJar, workingDirectory, memoryMb, lineConsumer, maxRetainedLines, List.of());
    }

    /**
     * Creates a launcher with extra options for the server JVM.
     *
     * @param serverJar        the server jar to run
     * @param workingDirectory the directory to run it in
     * @param memoryMb         the heap size, used for both {@code -Xms} and {@code -Xmx}
     * @param lineConsumer     receives every output line; may be {@code null}
     * @param maxRetainedLines how many trailing lines to keep in memory; must be positive
     * @param jvmOptions       options passed to the server JVM, such as {@code -javaagent}
     */
    public ServerLauncher(
            Path serverJar,
            Path workingDirectory,
            int memoryMb,
            Consumer<String> lineConsumer,
            int maxRetainedLines,
            List<String> jvmOptions) {
        this.serverJar = Objects.requireNonNull(serverJar, "serverJar");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.memoryMb = memoryMb;
        this.lineConsumer = lineConsumer == null ? line -> { } : lineConsumer;
        this.maxRetainedLines = Math.max(1, maxRetainedLines);
        this.jvmOptions = jvmOptions == null ? List.of() : List.copyOf(jvmOptions);
    }

    /**
     * Builds the command line used to start the server.
     *
     * @param javaExecutable the java binary to run
     * @param serverJar      the server jar
     * @param memoryMb       the heap size in megabytes
     * @return the command, ready for {@link ProcessBuilder}
     */
    static List<String> buildCommand(String javaExecutable, Path serverJar, int memoryMb) {
        return buildCommand(javaExecutable, serverJar, memoryMb, List.of());
    }

    /**
     * Builds the command line used to start the server.
     *
     * <p>The heap settings come first so that a caller-supplied option can never
     * displace them, and the jar and {@code --nogui} come last so that nothing can be
     * inserted between them.</p>
     *
     * @param javaExecutable the java binary to run
     * @param serverJar      the server jar
     * @param memoryMb       the heap size in megabytes
     * @param jvmOptions     extra options for the server JVM
     * @return the command, ready for {@link ProcessBuilder}
     */
    static List<String> buildCommand(
            String javaExecutable, Path serverJar, int memoryMb, List<String> jvmOptions) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        command.add("-Xms" + memoryMb + "M");
        command.add("-Xmx" + memoryMb + "M");
        if (jvmOptions != null) {
            command.addAll(jvmOptions);
        }
        command.add("-jar");
        command.add(serverJar.toAbsolutePath().toString());
        command.add("--nogui");
        return command;
    }

    /** The java executable running this JVM, which is the one used for the server. */
    static String currentJavaExecutable() {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    /**
     * Starts the server process and begins streaming its output.
     *
     * @throws IOException           if the process cannot be started
     * @throws IllegalStateException if this launcher was already started
     */
    public void start() throws IOException {
        if (process != null) {
            throw new IllegalStateException("This launcher has already been started");
        }
        ProcessBuilder builder = new ProcessBuilder(
                buildCommand(currentJavaExecutable(), serverJar, memoryMb, jvmOptions));
        builder.directory(workingDirectory.toFile());
        // Merge stderr into stdout so JVM level failures and server output stay in order.
        builder.redirectErrorStream(true);

        process = builder.start();
        readerThread = new Thread(this::pumpOutput, "foliacode-server-output");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Reads the merged output stream until the process closes it. */
    private void pumpOutput() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                retain(line);
                try {
                    lineConsumer.accept(line);
                } catch (RuntimeException e) {
                    // A failing consumer must not stop us from draining the process output,
                    // because a full pipe buffer would deadlock the server.
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // The stream closes when the process exits; that is the normal path out.
        }
    }

    private void retain(String line) {
        synchronized (retainedLines) {
            if (retainedLines.size() >= maxRetainedLines) {
                retainedLines.removeFirst();
            }
            retainedLines.addLast(line);
        }
    }

    /**
     * The retained tail of the server log.
     *
     * @return a snapshot of the retained lines, oldest first
     */
    public List<String> logTail() {
        synchronized (retainedLines) {
            return List.copyOf(retainedLines);
        }
    }

    /** Whether the server process is running. */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * The process exit code.
     *
     * @return the exit code, or empty while the process is still running
     */
    public OptionalInt exitCode() {
        if (process == null || process.isAlive()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(process.exitValue());
    }

    /**
     * Waits for the process to exit.
     *
     * @param timeout how long to wait
     * @return {@code true} if the process exited within the timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    public boolean awaitExit(Duration timeout) throws InterruptedException {
        if (process == null) {
            return true;
        }
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts the server down, escalating until the process is gone.
     *
     * <p>Never throws. This runs on the cleanup path, where the only unacceptable outcome is
     * leaving a server process behind.</p>
     *
     * @param gracePeriod how long the graceful {@code stop} command is given
     */
    public void shutdown(Duration gracePeriod) {
        if (process == null) {
            return;
        }
        Duration grace = gracePeriod == null ? STAGE_TIMEOUT : gracePeriod;

        if (process.isAlive()) {
            requestGracefulStop();
            awaitQuietly(grace);
        }
        if (process.isAlive()) {
            process.destroy();
            awaitQuietly(STAGE_TIMEOUT);
        }
        if (process.isAlive()) {
            // Last resort. Take the descendants too, so nothing is orphaned.
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            awaitQuietly(STAGE_TIMEOUT);
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    /** Sends the server's own {@code stop} command, which lets it save and shut down cleanly. */
    private void requestGracefulStop() {
        try {
            OutputStream stdin = process.getOutputStream();
            Writer writer = new java.io.OutputStreamWriter(stdin, StandardCharsets.UTF_8);
            writer.write("stop\n");
            writer.flush();
        } catch (IOException e) {
            // The process may already be gone, or its stdin closed. Escalation handles it.
        }
    }

    private void awaitQuietly(Duration timeout) {
        try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Shuts the server down with the default grace period. */
    @Override
    public void close() {
        shutdown(STAGE_TIMEOUT);
    }
}
