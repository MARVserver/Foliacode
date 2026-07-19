package dev.marv.foliacode.analysis;

import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.Verdict;
import dev.marv.foliacode.testsupport.BukkitStubs;
import dev.marv.foliacode.testsupport.JarBuilder;
import dev.marv.foliacode.testsupport.JavaSourceCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link JarAnalyzer} against real JAR structures.
 */
class JarAnalyzerTest {

    @Test
    @DisplayName("reports an incompatible plugin as NOT_READY and reads its plugin.yml")
    void analyzesIncompatiblePlugin(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.with("example.MyPlugin", """
                package example;
                import org.bukkit.Bukkit;
                import org.bukkit.plugin.Plugin;
                public class MyPlugin {
                    public void onEnable(Plugin plugin) {
                        Bukkit.getScheduler().runTask(plugin, () -> { });
                    }
                }
                """));

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("plugin.yml", """
                        name: MyPlugin
                        version: 1.2.3
                        main: example.MyPlugin
                        """)
                .writeTo(tempDir.resolve("MyPlugin.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertEquals(Verdict.NOT_READY, report.verdict());
        assertTrue(report.count(Severity.CRITICAL) > 0, "the synchronous scheduler should show up as CRITICAL");
        assertEquals("MyPlugin", report.plugin().name());
        assertEquals("1.2.3", report.plugin().version());
        assertEquals("example.MyPlugin", report.plugin().mainClass());
        assertNull(report.plugin().foliaSupported(), "an undeclared flag should stay null");
        assertTrue(report.classesScanned() > 0);
        assertEquals(0, report.classesFailed());
    }

    @Test
    @DisplayName("reports a clean plugin as READY")
    void analyzesCleanPlugin(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.with("example.Clean", """
                package example;
                public class Clean {
                    public int compute(int value) {
                        return value * 2;
                    }
                }
                """));

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("plugin.yml", "name: Clean\nversion: 1.0\nmain: example.Clean\n")
                .writeTo(tempDir.resolve("Clean.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertEquals(Verdict.READY, report.verdict(),
                "false positives are unacceptable. Findings: " + report.findings());
        assertTrue(report.findings().isEmpty());
    }

    @Test
    @DisplayName("detects incompatibilities inside nested JARs — the shaded-plugin case")
    void analyzesNestedJars(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.with("shaded.Inner", """
                package shaded;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Inner {
                    public void clear(Block block) {
                        block.setType(Material.AIR);
                    }
                }
                """));

        byte[] innerJar = new JarBuilder()
                .addBytes("shaded/Inner.class",
                        JavaSourceCompiler.readClass(classesDir, "shaded.Inner"))
                .toBytes(tempDir);

        Path outerJar = new JarBuilder()
                .addText("plugin.yml", "name: Shaded\nversion: 1.0\nmain: shaded.Outer\n")
                .addBytes("lib/inner.jar", innerJar)
                .writeTo(tempDir.resolve("Shaded.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(outerJar);

        assertEquals(1, report.nestedJarsScanned(), "one nested JAR should have been unpacked");
        assertTrue(report.findings().stream()
                        .anyMatch(f -> f.api().displayName().equals("Block.setType")),
                "calls inside the nested JAR should be detected. Findings: " + report.findings());
    }

    @Test
    @DisplayName("detects calls on a BukkitRunnable subclass — regression test from a real EssentialsX case")
    void detectsCallOnBukkitRunnableSubclass(@TempDir Path tempDir) throws IOException {
        // NyanCommand in EssentialsX 2.22.0 holds an inner class extending BukkitRunnable, so the
        // call owner in the bytecode is that inner class rather than BukkitRunnable itself:
        //   invokevirtual NyanCommand$TuneRunnable.runTaskTimer:(Lorg/bukkit/plugin/Plugin;JJ)...
        // Matching owners by exact name misses this CRITICAL incompatibility entirely.
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.with("example.Nyan", """
                package example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitRunnable;
                public class Nyan {
                    static class TuneRunnable extends BukkitRunnable {
                        @Override public void run() { }
                    }
                    public void start(Plugin plugin) {
                        new TuneRunnable().runTaskTimer(plugin, 0L, 1L);
                    }
                }
                """));

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("plugin.yml", "name: Nyan\nversion: 1.0\nmain: example.Nyan\n")
                .writeTo(tempDir.resolve("Nyan.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertTrue(report.findings().stream()
                        .anyMatch(f -> f.api().displayName().equals("BukkitRunnable.runTaskTimer")
                                && f.severity() == Severity.CRITICAL
                                && f.isViaSubtype()),
                "runTaskTimer on a subclass should be detected as CRITICAL."
                        + " Findings: " + report.findings());
    }

    @Test
    @DisplayName("reads the folia-supported declaration")
    void readsFoliaSupportedFlag(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.sources());

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("plugin.yml", """
                        name: Declared
                        version: 1.0
                        main: example.Declared
                        folia-supported: true
                        """)
                .writeTo(tempDir.resolve("Declared.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertTrue(report.plugin().declaresFoliaSupport());
    }

    @Test
    @DisplayName("reads paper-plugin.yml as well")
    void readsPaperPluginYml(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.sources());

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("paper-plugin.yml", "name: PaperStyle\nversion: 2.0\nmain: example.PaperStyle\n")
                .writeTo(tempDir.resolve("PaperStyle.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertEquals("PaperStyle", report.plugin().name());
    }

    @Test
    @DisplayName("continues the analysis when plugin.yml is absent")
    void survivesMissingPluginYml(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.with("example.NoYml", """
                package example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class NoYml {
                    public void clear(Block block) { block.setType(Material.AIR); }
                }
                """));

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .writeTo(tempDir.resolve("NoYml.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertFalse(report.plugin().isPresent());
        assertFalse(report.findings().isEmpty(), "classes should still be scanned without a plugin.yml");
    }

    @Test
    @DisplayName("does not abort on a malformed plugin.yml")
    void survivesMalformedPluginYml(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.sources());

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("plugin.yml", "name: [unclosed\n  bad: : yaml")
                .writeTo(tempDir.resolve("Broken.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertFalse(report.plugin().isPresent(), "unreadable YAML should be treated as UNKNOWN");
        assertTrue(report.classesScanned() > 0, "the class scan should carry on");
    }

    @Test
    @DisplayName("copes with entries that are not classes")
    void ignoresNonClassEntries(@TempDir Path tempDir) throws IOException {
        Path classesDir = JavaSourceCompiler.compile(tempDir, BukkitStubs.sources());

        Path jar = new JarBuilder()
                .addClassesFrom(classesDir)
                .addText("config.yml", "some: value\n")
                .addText("README.txt", "hello")
                .addBytes("assets/icon.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})
                .writeTo(tempDir.resolve("Mixed.jar"));

        AnalysisReport report = new JarAnalyzer().analyze(jar);

        assertTrue(report.classesScanned() > 0);
        assertEquals(0, report.classesFailed());
    }

    @Test
    @DisplayName("throws IOException when the file does not exist")
    void throwsOnMissingFile(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.jar");
        assertThrows(IOException.class, () -> new JarAnalyzer().analyze(missing));
    }

    @Test
    @DisplayName("does not blow up on a file that is not a JAR at all")
    void survivesNonJarFile(@TempDir Path tempDir) throws IOException {
        Path notAJar = tempDir.resolve("plain.jar");
        Files.writeString(notAJar, "this is not a JAR");

        AnalysisReport report = new JarAnalyzer().analyze(notAJar);

        assertEquals(0, report.classesScanned());
        assertEquals(Verdict.READY, report.verdict());
    }
}
