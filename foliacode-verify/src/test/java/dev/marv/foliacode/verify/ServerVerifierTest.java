package dev.marv.foliacode.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the opt-in gate and the configuration defaults of {@link ServerVerifier}.
 *
 * <p>These tests never touch the network and never start a process. The gate is checked
 * before anything is downloaded, which is exactly why it can be tested offline.</p>
 */
class ServerVerifierTest {

    @Test
    @DisplayName("Refuses to run when verification is not enabled")
    void refusesWhenDisabled(@TempDir Path temp) throws IOException {
        Path plugin = Files.writeString(temp.resolve("MyPlugin.jar"), "jar", StandardCharsets.UTF_8);
        ServerVerifier verifier = new ServerVerifier(ServerVerificationConfig.disabled());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> verifier.verify(plugin, "MyPlugin", "com.example.myplugin.MyPlugin"));

        assertTrue(error.getMessage().contains("disabled"));
        assertTrue(error.getMessage().contains("enabled=true"),
                "The message should tell the caller how to turn verification on");
    }

    @Test
    @DisplayName("Explains why verification is opt-in")
    void explainsWhyItIsOptIn(@TempDir Path temp) throws IOException {
        Path plugin = Files.writeString(temp.resolve("MyPlugin.jar"), "jar", StandardCharsets.UTF_8);
        ServerVerifier verifier = new ServerVerifier(
                new ServerVerificationConfig(false, "1.21.4", 1024, Duration.ofSeconds(30), false, List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> verifier.verify(plugin, "MyPlugin", null));

        assertTrue(error.getMessage().contains("executes"),
                "The user should be told that running this executes a third party jar");
    }

    @Test
    @DisplayName("Checks the opt-in gate before touching the plugin path")
    void gateComesFirst(@TempDir Path temp) {
        ServerVerifier verifier = new ServerVerifier(ServerVerificationConfig.disabled());
        Path missing = temp.resolve("does-not-exist.jar");

        // The jar does not exist, so an IOException here would mean we did work before
        // checking whether we were allowed to.
        assertThrows(IllegalStateException.class, () -> verifier.verify(missing, "MyPlugin", null));
    }

    @Test
    @DisplayName("Rejects a null plugin jar")
    void rejectsNullPluginJar() {
        ServerVerifier verifier = new ServerVerifier(ServerVerificationConfig.enabledFor("1.21.4"));
        assertThrows(NullPointerException.class, () -> verifier.verify(null, "MyPlugin", null));
    }

    @Test
    @DisplayName("Rejects a null configuration")
    void rejectsNullConfig() {
        assertThrows(NullPointerException.class, () -> new ServerVerifier(null));
    }

    @Test
    @DisplayName("Applies the documented defaults")
    void appliesDefaults() {
        ServerVerificationConfig config = ServerVerificationConfig.disabled();

        assertFalse(config.enabled(), "Verification must be off unless the user asks for it");
        assertEquals(1024, config.memoryMb());
        assertEquals(Duration.ofSeconds(180), config.bootTimeout());
        assertFalse(config.keepServerDirectory());
        assertTrue(config.extraPlugins().isEmpty());
    }

    @Test
    @DisplayName("Replaces unusable settings with the defaults")
    void normalisesInvalidSettings() {
        ServerVerificationConfig config =
                new ServerVerificationConfig(true, "  ", 0, Duration.ZERO, false, null);

        assertEquals(ServerVerificationConfig.DEFAULT_MINECRAFT_VERSION, config.minecraftVersion());
        assertEquals(ServerVerificationConfig.DEFAULT_MEMORY_MB, config.memoryMb());
        assertEquals(ServerVerificationConfig.DEFAULT_BOOT_TIMEOUT, config.bootTimeout());
        assertTrue(config.extraPlugins().isEmpty());
    }

    @Test
    @DisplayName("Reports whether the configured heap can actually boot Folia")
    void reportsHeapViability() {
        assertFalse(ServerVerificationConfig.enabledFor("1.21.4").withMemoryMb(128).hasViableHeap(),
                "128 MB cannot boot a Folia server and the config should say so up front");
        assertFalse(ServerVerificationConfig.enabledFor("1.21.4").withMemoryMb(512).hasViableHeap());
        assertTrue(ServerVerificationConfig.enabledFor("1.21.4").hasViableHeap());
        assertTrue(ServerVerificationConfig.enabledFor("1.21.4").withMemoryMb(4096).hasViableHeap());
    }

    @Test
    @DisplayName("Copy methods preserve the settings they do not change")
    void copyMethodsPreserveOtherSettings(@TempDir Path temp) {
        ServerVerificationConfig config = ServerVerificationConfig.enabledFor("1.21.4")
                .withMemoryMb(2048)
                .withBootTimeout(Duration.ofSeconds(90))
                .withKeepServerDirectory(true)
                .withExtraPlugins(List.of(temp.resolve("Vault.jar")));

        assertTrue(config.enabled());
        assertEquals("1.21.4", config.minecraftVersion());
        assertEquals(2048, config.memoryMb());
        assertEquals(Duration.ofSeconds(90), config.bootTimeout());
        assertTrue(config.keepServerDirectory());
        assertEquals(1, config.extraPlugins().size());
    }

    @Test
    @DisplayName("The launcher pins the heap with matching -Xms and -Xmx")
    void buildsLaunchCommand(@TempDir Path temp) {
        List<String> command = ServerLauncher.buildCommand("java", temp.resolve("folia.jar"), 1024);

        assertEquals("java", command.get(0));
        assertTrue(command.contains("-Xms1024M"));
        assertTrue(command.contains("-Xmx1024M"));
        assertTrue(command.contains("-jar"));
        assertTrue(command.contains("--nogui"), "The server must not try to open a GUI");
        assertTrue(command.get(command.indexOf("-jar") + 1).endsWith("folia.jar"));
    }
}
