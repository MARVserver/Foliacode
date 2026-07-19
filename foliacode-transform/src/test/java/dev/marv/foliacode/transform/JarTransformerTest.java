package dev.marv.foliacode.transform;

import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.Verdict;
import dev.marv.foliacode.testsupport.BukkitStubs;
import dev.marv.foliacode.testsupport.JarBuilder;
import dev.marv.foliacode.testsupport.JavaSourceCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarTransformerTest {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("rewrites a JAR, ships the shim, and declares Folia support once clean")
    void transformsAndDeclaresSupport() throws Exception {
        Path jar = buildJar("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void start(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        scheduler.runTask(plugin, task);
                        scheduler.runTaskLater(plugin, task, 0L);
                    }
                }
                """, """
                name: Demo
                version: 1.0.0
                main: com.example.Demo
                """);

        Path output = workDir.resolve("out/Demo-folia.jar");
        TransformReport report = new JarTransformer().transform(jar, output);

        assertEquals(2, report.rewritten().size());
        assertEquals(1, report.classesRewritten());
        assertEquals(Verdict.NOT_READY, report.before().verdict());
        assertEquals(Verdict.READY, report.resultingVerdict(),
                "both CRITICAL scheduler calls were translated");

        Map<String, byte[]> entries = readJar(output);
        assertTrue(entries.containsKey("dev/marv/foliacode/shim/FoliaSchedulers.class"),
                "the rewritten calls target the shim, so it has to travel with the plugin");
        assertTrue(entries.containsKey("dev/marv/foliacode/shim/FoliaBridge.class"));

        assertTrue(report.foliaSupportedDeclared());
        String pluginYml = new String(entries.get("plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(pluginYml.contains("folia-supported: true"), pluginYml);
        assertTrue(pluginYml.contains("main: com.example.Demo"),
                "the rest of the descriptor must survive untouched");

        assertTrue(Files.exists(jar), "the original is never consumed");
    }

    @Test
    @DisplayName("does not declare Folia support while anything is still unresolved")
    void withholdsDeclarationWhenFindingsRemain() throws Exception {
        Path jar = buildJar("""
                package com.example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void start(BukkitScheduler scheduler, Plugin plugin,
                                             Runnable task, Block block) {
                        scheduler.runTask(plugin, task);
                        block.setType(Material.STONE);
                    }
                }
                """, """
                name: Demo
                version: 1.0.0
                main: com.example.Demo
                """);

        Path output = workDir.resolve("Demo-folia.jar");
        TransformReport report = new JarTransformer().transform(jar, output);

        assertEquals(1, report.rewritten().size());
        assertEquals(1, report.refused().size());
        assertFalse(report.foliaSupportedDeclared(),
                "a HIGH finding remains, so the promise is not made");
        assertEquals(Verdict.NEEDS_REVIEW, report.resultingVerdict());

        String pluginYml = new String(readJar(output).get("plugin.yml"), StandardCharsets.UTF_8);
        assertFalse(pluginYml.contains("folia-supported"), pluginYml);
    }

    @Test
    @DisplayName("counts the shim's own reflection separately from the plugin's findings")
    void excludesShimFromRemainingFindings() throws Exception {
        Path jar = buildJar("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void start(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        scheduler.runTask(plugin, task);
                    }
                }
                """, "name: Demo\nmain: com.example.Demo\n");

        TransformReport report =
                new JarTransformer().transform(jar, workDir.resolve("Demo-folia.jar"));

        assertTrue(report.after().count(Severity.INFO) > 0,
                "the shim reaches the server reflectively, which the analyzer notices");
        assertTrue(report.remainingFindings().isEmpty(),
                "but FoliaCode's own scaffolding is not the plugin's remaining work");
    }

    @Test
    @DisplayName("a dry run reports on the real output without leaving one behind")
    void dryRunWritesNothing() throws Exception {
        Path jar = buildJar("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void start(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        scheduler.runTask(plugin, task);
                    }
                }
                """, "name: Demo\nmain: com.example.Demo\n");

        TransformReport report = new JarTransformer().dryRun(jar);

        assertTrue(report.isDryRun());
        assertEquals(1, report.rewritten().size());
        assertEquals(Verdict.READY, report.resultingVerdict(),
                "the dry run analyses the JAR it would have produced, not a guess");
        try (var files = Files.list(workDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith("-folia.jar")));
        }
    }

    @Test
    @DisplayName("refuses to overwrite the plugin it is reading")
    void refusesToOverwriteSource() throws Exception {
        Path jar = buildJar("""
                package com.example;
                public class Demo { }
                """, "name: Demo\n");

        IOException error = assertThrows(IOException.class,
                () -> new JarTransformer().transform(jar, jar));

        assertTrue(error.getMessage().contains("Refusing to overwrite"), error.getMessage());
    }

    @Test
    @DisplayName("refuses a signed JAR rather than silently void its signature")
    void refusesSignedJar() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void start(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        scheduler.runTask(plugin, task);
                    }
                }
                """);
        Path jar = new JarBuilder()
                .addClassesFrom(classes)
                .addText("plugin.yml", "name: Demo\n")
                .addText("META-INF/SIGNER.SF", "Signature-Version: 1.0\n")
                .writeTo(workDir.resolve("Signed.jar"));

        IOException error = assertThrows(IOException.class,
                () -> new JarTransformer().transform(jar, workDir.resolve("out.jar")));

        assertTrue(error.getMessage().contains("signed"), error.getMessage());
    }

    @Test
    @DisplayName("preserves entries it has no business touching")
    void preservesUnrelatedEntries() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void start(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        scheduler.runTask(plugin, task);
                    }
                }
                """);
        Path jar = new JarBuilder()
                .addClassesFrom(classes)
                .addText("plugin.yml", "name: Demo\n")
                .addText("config.yml", "greeting: hello\n")
                .addText("lang/en.yml", "key: value\n")
                .writeTo(workDir.resolve("Demo.jar"));

        Path output = workDir.resolve("Demo-folia.jar");
        new JarTransformer().transform(jar, output);

        Map<String, byte[]> entries = readJar(output);
        assertEquals("greeting: hello\n",
                new String(entries.get("config.yml"), StandardCharsets.UTF_8));
        assertEquals("key: value\n",
                new String(entries.get("lang/en.yml"), StandardCharsets.UTF_8));
    }

    /**
     * Builds a plugin JAR containing one class.
     *
     * @param source    source of {@code com.example.Demo}
     * @param pluginYml the descriptor
     * @return the JAR path
     * @throws IOException if compilation or writing fails
     */
    private Path buildJar(String source, String pluginYml) throws IOException {
        return new JarBuilder()
                .addClassesFrom(compile(source))
                .addText("plugin.yml", pluginYml)
                .writeTo(workDir.resolve("Demo.jar"));
    }

    /**
     * Compiles one class against the Bukkit stubs.
     *
     * @param source the source text
     * @return the compiled classes directory
     * @throws IOException if compilation fails
     */
    private Path compile(String source) throws IOException {
        return JavaSourceCompiler.compile(workDir, BukkitStubs.with("com.example.Demo", source));
    }

    /**
     * Reads every entry of a JAR.
     *
     * @param jar the JAR
     * @return entry name to content
     * @throws IOException if reading fails
     */
    private static Map<String, byte[]> readJar(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }
}
