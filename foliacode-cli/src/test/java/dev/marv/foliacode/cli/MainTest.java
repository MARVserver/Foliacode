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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the CLI's exit codes and output.
 *
 * <p>The exit code is what makes a CI build pass or fail, so the contract
 * "below the threshold is 0, at or above it is 1, an error is 2" is pinned
 * down explicitly.</p>
 */
class MainTest {

    @Test
    @DisplayName("no arguments prints usage and exits 2")
    void noArgumentsReturnsError() {
        Result result = run();

        assertEquals(2, result.exitCode());
        assertTrue(result.out().contains("Usage"));
    }

    @Test
    @DisplayName("--help exits 0")
    void helpReturnsOk() {
        Result result = run("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("foliacode analyze"));
    }

    @Test
    @DisplayName("an unknown command exits 2")
    void unknownCommandReturnsError() {
        Result result = run("frobnicate");

        assertEquals(2, result.exitCode());
        assertTrue(result.err().contains("Unknown command"));
    }

    @Test
    @DisplayName("rules lists the registered rules")
    void rulesListsRegisteredRules() {
        Result result = run("rules");

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("Registered rules"));
        assertTrue(result.out().contains("BukkitScheduler.runTask"));
    }

    @Test
    @DisplayName("a clean JAR exits 0")
    void cleanJarExitsZero(@TempDir Path tempDir) throws IOException {
        Path jar = buildJar(tempDir, "Clean.jar", "example.Clean", """
                package example;
                public class Clean {
                    public int twice(int value) { return value * 2; }
                }
                """);

        Result result = run("analyze", jar.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.out().contains("READY"));
    }

    @Test
    @DisplayName("a JAR with a CRITICAL finding exits 1")
    void criticalFindingExitsOne(@TempDir Path tempDir) throws IOException {
        Path jar = buildJar(tempDir, "Bad.jar", "example.Bad", """
                package example;
                import org.bukkit.Bukkit;
                import org.bukkit.plugin.Plugin;
                public class Bad {
                    public void onEnable(Plugin plugin) {
                        Bukkit.getScheduler().runTask(plugin, () -> { });
                    }
                }
                """);

        Result result = run("analyze", jar.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.out().contains("NOT READY"));
    }

    @Test
    @DisplayName("--fail-on HIGH exits 1 on a HIGH finding")
    void failOnThresholdIsRespected(@TempDir Path tempDir) throws IOException {
        Path jar = buildJar(tempDir, "HighOnly.jar", "example.HighOnly", """
                package example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class HighOnly {
                    public void clear(Block block) { block.setType(Material.AIR); }
                }
                """);

        assertEquals(0, run("analyze", jar.toString()).exitCode(),
                "the default threshold is CRITICAL, so a HIGH-only JAR exits 0");
        assertEquals(1, run("analyze", jar.toString(), "--fail-on", "HIGH").exitCode(),
                "--fail-on HIGH should make the same JAR exit 1");
    }

    @Test
    @DisplayName("--json writes the analysis result to a file")
    void writesJsonOutput(@TempDir Path tempDir) throws IOException {
        Path jar = buildJar(tempDir, "Json.jar", "example.Json", """
                package example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Json {
                    public void clear(Block block) { block.setType(Material.AIR); }
                }
                """);
        Path jsonFile = tempDir.resolve("out/report.json");

        Result result = run("analyze", jar.toString(), "--json", jsonFile.toString());

        assertEquals(0, result.exitCode());
        assertTrue(Files.exists(jsonFile), "the JSON file should have been created");
        String json = Files.readString(jsonFile);
        assertTrue(json.contains("\"verdict\""));
        assertTrue(json.contains("Block.setType"));
    }

    @Test
    @DisplayName("a target that does not exist exits 2")
    void missingTargetReturnsError(@TempDir Path tempDir) {
        Result result = run("analyze", tempDir.resolve("nope.jar").toString());

        assertEquals(2, result.exitCode());
    }

    @Test
    @DisplayName("an invalid option exits 2")
    void invalidOptionReturnsError(@TempDir Path tempDir) throws IOException {
        Path jar = buildJar(tempDir, "Any.jar", "example.Any", """
                package example;
                public class Any { }
                """);

        assertEquals(2, run("analyze", jar.toString(), "--nonsense").exitCode());
        assertEquals(2, run("analyze", jar.toString(), "--fail-on", "BOGUS").exitCode());
        assertEquals(2, run("analyze", jar.toString(), "--json").exitCode());
    }

    @Test
    @DisplayName("pointing at a directory analyses every JAR inside it")
    void analyzesDirectory(@TempDir Path tempDir) throws IOException {
        Path pluginsDir = Files.createDirectories(tempDir.resolve("plugins"));
        Path build = Files.createDirectories(tempDir.resolve("build"));

        Path classesDir = JavaSourceCompiler.compile(build, BukkitStubs.with("example.Two", """
                package example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Two {
                    public void clear(Block block) { block.setType(Material.AIR); }
                }
                """));
        new JarBuilder().addClassesFrom(classesDir).writeTo(pluginsDir.resolve("a.jar"));
        new JarBuilder().addClassesFrom(classesDir).writeTo(pluginsDir.resolve("b.jar"));

        Result result = run("analyze", pluginsDir.toString());

        assertEquals(0, result.exitCode(), "HIGH only, so the default threshold gives 0");
        assertTrue(result.out().contains("a.jar"));
        assertTrue(result.out().contains("b.jar"));
    }

    /**
     * Builds a JAR containing a single class.
     *
     * @param tempDir   the working directory
     * @param jarName   the JAR name
     * @param className fully-qualified class name
     * @param source    the source
     * @return path to the generated JAR
     * @throws IOException if the JAR cannot be built
     */
    private static Path buildJar(Path tempDir, String jarName, String className, String source)
            throws IOException {
        Path buildDir = Files.createDirectories(tempDir.resolve("build-" + jarName));
        Path classesDir = JavaSourceCompiler.compile(buildDir, BukkitStubs.with(className, source));
        return new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("plugin.yml", "name: " + jarName + "\nversion: 1.0\nmain: " + className + "\n")
                .writeTo(tempDir.resolve(jarName));
    }

    /**
     * Runs the CLI and captures its output and exit code.
     *
     * @param args the command-line arguments
     * @return the result of the run
     */
    private static Result run(String... args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);

        int exitCode = Main.run(args, out, err);
        out.flush();
        err.flush();

        return new Result(exitCode,
                outBuffer.toString(StandardCharsets.UTF_8),
                errBuffer.toString(StandardCharsets.UTF_8));
    }

    /**
     * The result of a CLI run.
     *
     * @param exitCode the exit code
     * @param out      standard output
     * @param err      standard error
     */
    private record Result(int exitCode, String out, String err) {
    }
}
