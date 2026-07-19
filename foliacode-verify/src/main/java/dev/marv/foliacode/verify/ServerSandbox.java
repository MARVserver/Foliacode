package dev.marv.foliacode.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A disposable server directory holding everything one verification run needs.
 *
 * <p>The sandbox lives outside the user's project, is tuned for the fastest possible
 * headless boot, and is designed to be deleted. Nothing here is meant to survive a run.</p>
 */
public final class ServerSandbox {

    /** The lowest port considered when a free port cannot be reserved from the OS. */
    private static final int MIN_FALLBACK_PORT = 40_000;

    /** The highest port considered when a free port cannot be reserved from the OS. */
    private static final int MAX_FALLBACK_PORT = 60_000;

    private final Path root;
    private final Path pluginsDirectory;
    private final int port;

    private ServerSandbox(Path root, Path pluginsDirectory, int port) {
        this.root = root;
        this.pluginsDirectory = pluginsDirectory;
        this.port = port;
    }

    /**
     * Creates a sandbox containing the plugin under test.
     *
     * @param pluginJar    the plugin to verify
     * @param extraPlugins dependency jars to install alongside it; may be {@code null}
     * @return the prepared sandbox
     * @throws IOException if the directory cannot be created or populated
     */
    public static ServerSandbox create(Path pluginJar, List<Path> extraPlugins) throws IOException {
        Objects.requireNonNull(pluginJar, "pluginJar");
        if (!Files.isRegularFile(pluginJar)) {
            throw new IOException("Plugin jar not found: " + pluginJar);
        }

        Path root = Files.createTempDirectory("foliacode-verify-");
        Path plugins = Files.createDirectories(root.resolve("plugins"));
        int port = reserveFreePort();

        writeEula(root);
        writeServerProperties(root, port);

        Files.copy(pluginJar, plugins.resolve(pluginJar.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING);

        if (extraPlugins != null) {
            for (Path extra : extraPlugins) {
                if (extra == null) {
                    continue;
                }
                if (!Files.isRegularFile(extra)) {
                    throw new IOException("Extra plugin jar not found: " + extra);
                }
                Files.copy(extra, plugins.resolve(extra.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return new ServerSandbox(root, plugins, port);
    }

    /** The sandbox root, which is the server's working directory. */
    public Path root() {
        return root;
    }

    /** The {@code plugins} directory inside the sandbox. */
    public Path pluginsDirectory() {
        return pluginsDirectory;
    }

    /** The port the server was configured to listen on. */
    public int port() {
        return port;
    }

    /** Whether the sandbox directory still exists. */
    public boolean exists() {
        return Files.isDirectory(root);
    }

    /**
     * Lists the plugin jars installed in the sandbox.
     *
     * @return the installed jar paths, sorted by file name
     * @throws IOException if the plugins directory cannot be read
     */
    public List<Path> installedPlugins() throws IOException {
        try (var stream = Files.list(pluginsDirectory)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    /**
     * Deletes the sandbox and everything in it.
     *
     * <p>Safe to call more than once, and never throws. A verification run must be able to
     * clean up on its way out even when something has already gone wrong.</p>
     *
     * @return {@code true} if the sandbox is gone once this returns
     */
    public boolean delete() {
        return deleteRecursively(root);
    }

    /**
     * Deletes a directory tree, ignoring anything that cannot be removed.
     *
     * @param path the tree to delete; may be {@code null} or already absent
     * @return {@code true} if nothing remains at the path
     */
    public static boolean deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return true;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Keep going: a file we cannot stat must not abort the rest of the cleanup.
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            return !Files.exists(path);
        }
        return !Files.exists(path);
    }

    /**
     * Writes {@code eula.txt}.
     *
     * <p>The sandbox is created by the user, on their machine, to test their own plugin, so
     * accepting the EULA here reflects the decision they already made by running the tool.</p>
     */
    private static void writeEula(Path root) throws IOException {
        Files.writeString(root.resolve("eula.txt"),
                "# Generated by FoliaCode for a disposable verification server\neula=true\n",
                StandardCharsets.UTF_8);
    }

    /** Writes a {@code server.properties} tuned for the fastest possible headless boot. */
    private static void writeServerProperties(Path root, int port) throws IOException {
        List<String> lines = new ArrayList<>(List.of(
                "# Generated by FoliaCode for a disposable verification server",
                "online-mode=false",
                "level-type=flat",
                "level-name=world",
                "spawn-protection=0",
                "max-players=1",
                "view-distance=4",
                "simulation-distance=4",
                "server-port=" + port,
                "query.port=" + port,
                "motd=foliacode",
                "sync-chunk-writes=false",
                "spawn-npcs=false",
                "spawn-animals=false",
                "spawn-monsters=false",
                "generate-structures=false",
                "enable-jmx-monitoring=false"));
        Files.write(root.resolve("server.properties"), lines, StandardCharsets.UTF_8);
    }

    /**
     * Reserves a free high port.
     *
     * <p>Asking the OS for an ephemeral port and releasing it immediately is racy in
     * principle, but far more reliable than picking a number and hoping. The random
     * fallback only matters if the OS refuses entirely.</p>
     */
    private static int reserveFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            return ThreadLocalRandom.current().nextInt(MIN_FALLBACK_PORT, MAX_FALLBACK_PORT);
        }
    }
}
