package dev.marv.foliacode.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ServerSandbox} builds a bootable directory and disposes of it.
 */
class ServerSandboxTest {

    @Test
    @DisplayName("Creates the server directory with eula, properties and plugins")
    void createsCompleteSandbox(@TempDir Path temp) throws IOException {
        Path plugin = writeJar(temp, "MyPlugin.jar");

        ServerSandbox sandbox = ServerSandbox.create(plugin, List.of());
        try {
            assertTrue(Files.isDirectory(sandbox.root()));
            assertTrue(Files.isRegularFile(sandbox.root().resolve("eula.txt")));
            assertTrue(Files.isRegularFile(sandbox.root().resolve("server.properties")));
            assertTrue(Files.isDirectory(sandbox.pluginsDirectory()));
            assertTrue(sandbox.exists());
        } finally {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("Accepts the EULA so the server can start unattended")
    void writesAcceptedEula(@TempDir Path temp) throws IOException {
        ServerSandbox sandbox = ServerSandbox.create(writeJar(temp, "MyPlugin.jar"), List.of());
        try {
            String eula = Files.readString(sandbox.root().resolve("eula.txt"), StandardCharsets.UTF_8);
            assertTrue(eula.contains("eula=true"));
        } finally {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("Writes server.properties tuned for a fast headless boot")
    void writesFastBootProperties(@TempDir Path temp) throws IOException {
        ServerSandbox sandbox = ServerSandbox.create(writeJar(temp, "MyPlugin.jar"), List.of());
        try {
            Map<String, String> properties = readProperties(sandbox.root().resolve("server.properties"));

            assertEquals("false", properties.get("online-mode"));
            assertEquals("flat", properties.get("level-type"));
            assertEquals("0", properties.get("spawn-protection"));
            assertEquals("1", properties.get("max-players"));
            assertEquals("4", properties.get("view-distance"));
            assertEquals("4", properties.get("simulation-distance"));
            assertEquals("foliacode", properties.get("motd"));
            assertEquals(String.valueOf(sandbox.port()), properties.get("server-port"));
        } finally {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("Uses a high port so it cannot collide with a real server on 25565")
    void usesHighPort(@TempDir Path temp) throws IOException {
        ServerSandbox sandbox = ServerSandbox.create(writeJar(temp, "MyPlugin.jar"), List.of());
        try {
            assertTrue(sandbox.port() > 1024, "Port should be outside the privileged range");
            assertTrue(sandbox.port() <= 65535);
        } finally {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("Installs the plugin under test into plugins/")
    void installsPluginUnderTest(@TempDir Path temp) throws IOException {
        ServerSandbox sandbox = ServerSandbox.create(writeJar(temp, "MyPlugin.jar"), List.of());
        try {
            Path installed = sandbox.pluginsDirectory().resolve("MyPlugin.jar");
            assertTrue(Files.isRegularFile(installed));
            assertEquals("plugin-jar-content", Files.readString(installed, StandardCharsets.UTF_8));
        } finally {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("Installs the caller's extra dependency jars alongside it")
    void installsExtraPlugins(@TempDir Path temp) throws IOException {
        Path plugin = writeJar(temp, "MyPlugin.jar");
        Path vault = writeJar(temp, "Vault.jar");
        Path papi = writeJar(temp, "PlaceholderAPI.jar");

        ServerSandbox sandbox = ServerSandbox.create(plugin, List.of(vault, papi));
        try {
            List<String> names = sandbox.installedPlugins().stream()
                    .map(path -> path.getFileName().toString())
                    .toList();

            assertEquals(List.of("MyPlugin.jar", "PlaceholderAPI.jar", "Vault.jar"), names);
        } finally {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("Ignores a null entry in the extra plugin list")
    void toleratesNullExtraPlugin(@TempDir Path temp) throws IOException {
        Path plugin = writeJar(temp, "MyPlugin.jar");

        ServerSandbox sandbox = ServerSandbox.create(plugin, java.util.Arrays.asList(plugin, null));
        try {
            assertEquals(1, sandbox.installedPlugins().size());
        } finally {
            sandbox.delete();
        }
    }

    @Test
    @DisplayName("Rejects a plugin jar that does not exist")
    void rejectsMissingPluginJar(@TempDir Path temp) {
        Path missing = temp.resolve("does-not-exist.jar");
        assertThrows(IOException.class, () -> ServerSandbox.create(missing, List.of()));
    }

    @Test
    @DisplayName("Rejects an extra plugin jar that does not exist")
    void rejectsMissingExtraJar(@TempDir Path temp) throws IOException {
        Path plugin = writeJar(temp, "MyPlugin.jar");
        Path missing = temp.resolve("nope.jar");

        assertThrows(IOException.class, () -> ServerSandbox.create(plugin, List.of(missing)));
    }

    @Test
    @DisplayName("Deleting removes the directory and everything the server wrote into it")
    void deleteRemovesEverything(@TempDir Path temp) throws IOException {
        ServerSandbox sandbox = ServerSandbox.create(writeJar(temp, "MyPlugin.jar"), List.of());
        Path root = sandbox.root();

        // Simulate the tree a booted server leaves behind.
        Files.createDirectories(root.resolve("world/region"));
        Files.writeString(root.resolve("world/region/r.0.0.mca"), "chunk");
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("logs/latest.log"), "log");
        Files.createDirectories(root.resolve("plugins/MyPlugin/data"));
        Files.writeString(root.resolve("plugins/MyPlugin/data/config.yml"), "key: value");

        assertTrue(sandbox.delete());
        assertFalse(Files.exists(root));
        assertFalse(sandbox.exists());
    }

    @Test
    @DisplayName("Deleting twice is harmless")
    void deleteIsIdempotent(@TempDir Path temp) throws IOException {
        ServerSandbox sandbox = ServerSandbox.create(writeJar(temp, "MyPlugin.jar"), List.of());

        assertTrue(sandbox.delete());
        assertTrue(sandbox.delete(), "A second delete must succeed rather than fail on cleanup");
    }

    @Test
    @DisplayName("Recursive delete tolerates a path that is absent or null")
    void recursiveDeleteToleratesMissingPaths(@TempDir Path temp) {
        assertTrue(ServerSandbox.deleteRecursively(null));
        assertTrue(ServerSandbox.deleteRecursively(temp.resolve("never-existed")));
    }

    @Test
    @DisplayName("Each sandbox is independent")
    void sandboxesAreIsolated(@TempDir Path temp) throws IOException {
        Path plugin = writeJar(temp, "MyPlugin.jar");
        ServerSandbox first = ServerSandbox.create(plugin, List.of());
        ServerSandbox second = ServerSandbox.create(plugin, List.of());
        try {
            assertFalse(first.root().equals(second.root()));

            first.delete();
            assertTrue(second.exists(), "Deleting one sandbox must not affect another");
        } finally {
            first.delete();
            second.delete();
        }
    }

    private static Path writeJar(Path directory, String name) throws IOException {
        Path jar = directory.resolve(name);
        Files.writeString(jar, "plugin-jar-content", StandardCharsets.UTF_8);
        return jar;
    }

    private static Map<String, String> readProperties(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith("#") && line.contains("="))
                .collect(Collectors.toMap(
                        line -> line.substring(0, line.indexOf('=')),
                        line -> line.substring(line.indexOf('=') + 1),
                        (first, second) -> second));
    }
}
