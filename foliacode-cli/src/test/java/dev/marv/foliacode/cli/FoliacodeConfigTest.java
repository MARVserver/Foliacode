package dev.marv.foliacode.cli;

import dev.marv.foliacode.verify.ServerVerificationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies configuration loading.
 *
 * <p>The property that matters most here is that verification stays off unless a
 * file explicitly turns it on. Every malformed, empty or absent input must fail
 * closed, because failing open would mean downloading and executing a server that
 * nobody asked for.</p>
 */
class FoliacodeConfigTest {

    @Test
    @DisplayName("server verification is disabled by default")
    void disabledByDefault() {
        assertFalse(FoliacodeConfig.defaults().serverVerification().enabled());
    }

    @Test
    @DisplayName("a missing config file yields the defaults")
    void missingFileYieldsDefaults(@TempDir Path tempDir) throws IOException {
        FoliacodeConfig config = FoliacodeConfig.loadFrom(tempDir);

        assertFalse(config.serverVerification().enabled());
    }

    @Test
    @DisplayName("reads every server verification setting")
    void readsAllSettings(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(FoliacodeConfig.FILE_NAME), """
                serverVerification:
                  enabled: true
                  minecraftVersion: "1.21.8"
                  memoryMb: 4096
                  bootTimeoutSeconds: 300
                  keepServerDirectory: true
                """);

        ServerVerificationConfig verification = FoliacodeConfig.loadFrom(tempDir).serverVerification();

        assertTrue(verification.enabled());
        assertEquals("1.21.8", verification.minecraftVersion());
        assertEquals(4096, verification.memoryMb());
        assertEquals(300, verification.bootTimeout().toSeconds());
        assertTrue(verification.keepServerDirectory());
    }

    @Test
    @DisplayName("absent keys fall back to defaults instead of failing")
    void partialConfigUsesDefaults() {
        ServerVerificationConfig verification = FoliacodeConfig.parse("""
                serverVerification:
                  enabled: true
                """).serverVerification();

        assertTrue(verification.enabled());
        assertEquals(ServerVerificationConfig.DEFAULT_MINECRAFT_VERSION,
                verification.minecraftVersion());
        assertEquals(ServerVerificationConfig.DEFAULT_MEMORY_MB, verification.memoryMb());
    }

    @Test
    @DisplayName("a quoted boolean still enables verification")
    void acceptsQuotedBoolean() {
        assertTrue(FoliacodeConfig.parse("""
                serverVerification:
                  enabled: "true"
                """).serverVerification().enabled());
    }

    @Test
    @DisplayName("malformed or irrelevant YAML fails closed rather than open")
    void malformedInputFailsClosed() {
        assertFalse(FoliacodeConfig.parse("").serverVerification().enabled());
        assertFalse(FoliacodeConfig.parse(null).serverVerification().enabled());
        assertFalse(FoliacodeConfig.parse("name: [unclosed\n  bad: : yaml")
                .serverVerification().enabled());
        assertFalse(FoliacodeConfig.parse("somethingElse: true")
                .serverVerification().enabled());
        assertFalse(FoliacodeConfig.parse("serverVerification: not-a-map")
                .serverVerification().enabled());
    }

    @Test
    @DisplayName("a non-numeric memory value falls back rather than crashing")
    void nonNumericMemoryFallsBack() {
        ServerVerificationConfig verification = FoliacodeConfig.parse("""
                serverVerification:
                  enabled: true
                  memoryMb: "lots"
                """).serverVerification();

        assertEquals(ServerVerificationConfig.DEFAULT_MEMORY_MB, verification.memoryMb());
    }
}
