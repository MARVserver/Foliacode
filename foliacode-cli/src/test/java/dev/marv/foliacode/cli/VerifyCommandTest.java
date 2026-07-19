package dev.marv.foliacode.cli;

import dev.marv.foliacode.testsupport.BukkitStubs;
import dev.marv.foliacode.testsupport.JarBuilder;
import dev.marv.foliacode.testsupport.JavaSourceCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the consent gate and argument handling of {@code verify}.
 *
 * <p>None of these boot a server. They cover the decisions taken before anything
 * is downloaded, which is exactly where a mistake would be most costly: running a
 * server nobody agreed to.</p>
 */
class VerifyCommandTest {

    @Test
    @DisplayName("refuses to run without consent and explains how to give it")
    void refusesWithoutConsent(@TempDir Path tempDir) throws IOException {
        Path jar = buildPluginJar(tempDir);

        Result result = run(tempDir, "verify", jar.toString());

        assertEquals(VerifyCommand.EXIT_ERROR, result.exitCode(),
                "no consent must not be treated as a plugin problem");
        assertTrue(result.err().contains("disabled"));
        assertTrue(result.err().contains("--yes"), "the error should say how to opt in");
        assertTrue(result.err().contains("AI assistant"),
                "an assistant reading this must be told to ask its user first");
    }

    @Test
    @DisplayName("a missing plugin JAR is an execution error, not a plugin problem")
    void missingJarIsExecutionError(@TempDir Path tempDir) {
        Result result = run(tempDir, "verify", tempDir.resolve("nope.jar").toString(), "--yes");

        assertEquals(VerifyCommand.EXIT_ERROR, result.exitCode());
        assertTrue(result.err().contains("not found"));
    }

    @Test
    @DisplayName("rejects invalid option values before doing any work")
    void rejectsInvalidOptions(@TempDir Path tempDir) throws IOException {
        Path jar = buildPluginJar(tempDir);

        assertEquals(VerifyCommand.EXIT_ERROR,
                run(tempDir, "verify", jar.toString(), "--memory", "plenty").exitCode());
        assertEquals(VerifyCommand.EXIT_ERROR,
                run(tempDir, "verify", jar.toString(), "--memory", "-1").exitCode());
        assertEquals(VerifyCommand.EXIT_ERROR,
                run(tempDir, "verify", jar.toString(), "--memory").exitCode());
        assertEquals(VerifyCommand.EXIT_ERROR,
                run(tempDir, "verify", jar.toString(), "--nonsense").exitCode());
        assertEquals(VerifyCommand.EXIT_ERROR,
                run(tempDir, "verify").exitCode());
    }

    @Test
    @DisplayName("command-line options layer over the config file")
    void optionsOverrideConfigFile() {
        VerifyCommand.VerifyOptions options = VerifyCommand.VerifyOptions.parse(new String[]{
                "verify", "MyPlugin.jar", "--memory", "4096", "--mc-version", "1.21.8"});

        var effective = options.applyTo(
                dev.marv.foliacode.verify.ServerVerificationConfig.enabledFor("1.21.4"));

        assertEquals(4096, effective.memoryMb());
        assertEquals("1.21.8", effective.minecraftVersion());
    }

    @Test
    @DisplayName("config-file consent alone is enough; --yes is not required")
    void configFileGrantsConsent() {
        VerifyCommand.VerifyOptions options = VerifyCommand.VerifyOptions.parse(new String[]{
                "verify", "MyPlugin.jar"});

        assertTrue(options.applyTo(
                        dev.marv.foliacode.verify.ServerVerificationConfig.enabledFor("1.21.4"))
                .enabled());
        assertFalse(options.applyTo(
                        dev.marv.foliacode.verify.ServerVerificationConfig.disabled())
                .enabled(),
                "and without either signal it stays off");
    }

    @Test
    @DisplayName("--instrument writes its reports outside the sandbox that gets deleted")
    void agentReportPathsAreAbsolute() {
        // Regression: these were relative, and the agent resolves them inside the server
        // process, whose working directory is the throwaway sandbox. The reports were
        // written and then deleted along with it, and the run reported that the agent
        // had produced nothing.
        VerifyCommand.AgentAttachment attachment = VerifyCommand.attachAgent(
                Path.of("/opt/foliacode.jar"), Path.of("."), "MyPlugin", "com.example");

        assertTrue(attachment.textReport().isAbsolute(), attachment.textReport().toString());
        assertTrue(attachment.jsonReport().isAbsolute(), attachment.jsonReport().toString());
        assertTrue(attachment.jvmOption().contains("text=" + attachment.textReport()),
                attachment.jvmOption());
        assertTrue(attachment.jvmOption().contains("report=" + attachment.jsonReport()),
                attachment.jvmOption());
        assertTrue(attachment.jvmOption().startsWith("-javaagent:/opt/foliacode.jar="),
                attachment.jvmOption());
        assertTrue(attachment.jvmOption().contains(",include=com.example"),
                attachment.jvmOption());
    }

    @Test
    @DisplayName("--instrument watches everything when the plugin declares no main package")
    void agentInstrumentsEverythingWithoutAPackage() {
        VerifyCommand.AgentAttachment attachment = VerifyCommand.attachAgent(
                Path.of("/opt/foliacode.jar"), Path.of("."), "MyPlugin", null);

        assertFalse(attachment.jvmOption().contains("include="),
                "an absent filter must be omitted, not sent as an empty one");
    }

    @Test
    @DisplayName("--with collects every dependency jar given")
    void collectsExtraPlugins() {
        VerifyCommand.VerifyOptions options = VerifyCommand.VerifyOptions.parse(new String[]{
                "verify", "MyPlugin.jar", "--with", "Vault.jar", "--with", "ProtocolLib.jar"});

        assertEquals(2, options.extraPlugins().size());
    }

    /**
     * Builds a minimal plugin JAR to point the command at.
     *
     * @param tempDir the working directory
     * @return the JAR path
     * @throws IOException if the JAR cannot be built
     */
    private static Path buildPluginJar(Path tempDir) throws IOException {
        Path buildDir = Files.createDirectories(tempDir.resolve("build"));
        Path classesDir = JavaSourceCompiler.compile(buildDir, BukkitStubs.with("example.Tiny", """
                package example;
                public class Tiny { }
                """));
        return new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("plugin.yml", "name: Tiny\nversion: 1.0\nmain: example.Tiny\n")
                .writeTo(tempDir.resolve("Tiny.jar"));
    }

    /**
     * Runs the command with a controlled working directory and captured streams.
     *
     * @param baseDir directory to look for {@code foliacode.yml} in
     * @param args    the arguments
     * @return the outcome
     */
    private static Result run(Path baseDir, String... args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);

        int exitCode = VerifyCommand.run(args, baseDir, out, err);
        out.flush();
        err.flush();

        return new Result(exitCode,
                outBuffer.toString(StandardCharsets.UTF_8),
                errBuffer.toString(StandardCharsets.UTF_8));
    }

    /**
     * A captured command outcome.
     *
     * @param exitCode the exit code
     * @param out      standard output
     * @param err      standard error
     */
    private record Result(int exitCode, String out, String err) {
    }
}
