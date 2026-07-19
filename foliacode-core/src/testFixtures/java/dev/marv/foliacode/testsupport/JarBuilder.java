package dev.marv.foliacode.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles JARs for tests.
 *
 * <p>Builds the same structure a real plugin JAR has ({@code plugin.yml},
 * classes, nested JARs) so that
 * {@link dev.marv.foliacode.analysis.JarAnalyzer} can be exercised against
 * its actual input format.</p>
 */
public final class JarBuilder {

    private final Map<String, byte[]> entries = new LinkedHashMap<>();

    /**
     * Adds the contents of a compiled classes directory.
     *
     * @param classesDir the class output directory
     * @return this builder
     * @throws IOException if reading fails
     */
    public JarBuilder addClassesFrom(Path classesDir) throws IOException {
        try (Stream<Path> files = Files.walk(classesDir)) {
            List<Path> classFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .toList();
            for (Path file : classFiles) {
                String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                entries.put(entryName, Files.readAllBytes(file));
            }
        }
        return this;
    }

    /**
     * Adds a text entry.
     *
     * @param name    the entry name
     * @param content the content
     * @return this builder
     */
    public JarBuilder addText(String name, String content) {
        entries.put(name, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Adds a binary entry. Used to embed nested JARs.
     *
     * @param name  the entry name
     * @param bytes the content
     * @return this builder
     */
    public JarBuilder addBytes(String name, byte[] bytes) {
        entries.put(name, bytes);
        return this;
    }

    /**
     * Writes the JAR out.
     *
     * @param target the output path
     * @return the output path
     * @throws IOException if writing fails
     */
    public Path writeTo(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return target;
    }

    /**
     * Builds the JAR as a byte array. Used to create nested JARs.
     *
     * @param workDir directory for the temporary file
     * @return the JAR bytes
     * @throws IOException if writing fails
     */
    public byte[] toBytes(Path workDir) throws IOException {
        Path temp = Files.createTempFile(workDir, "nested", ".jar");
        writeTo(temp);
        byte[] bytes = Files.readAllBytes(temp);
        Files.deleteIfExists(temp);
        return bytes;
    }

    /** Names of the entries added so far (for diagnostics). */
    public List<String> entryNames() {
        return new ArrayList<>(entries.keySet());
    }
}
