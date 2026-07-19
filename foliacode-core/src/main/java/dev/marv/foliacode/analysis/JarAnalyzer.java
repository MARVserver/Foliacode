package dev.marv.foliacode.analysis;

import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.PluginDescriptor;
import dev.marv.foliacode.rules.TypeHierarchy;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Analyses a single JAR.
 *
 * <p>Analysis runs in two passes. The first reads only class headers to build
 * the type hierarchy; the second walks the method bodies. Completing the
 * hierarchy first is what allows calls made through types defined inside the
 * JAR to resolve.</p>
 *
 * <p>Shaded plugins ship their dependencies as nested JARs, so nested JARs are
 * analysed one level deep. Ignoring them would risk reporting "no
 * incompatibilities" on a plugin that clearly has some.</p>
 */
public final class JarAnalyzer {

    /** Cap on total uncompressed size, as a defence against zip bombs. */
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024;

    /** Cap on the size of any single entry. */
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    /** How deep to follow nested JARs. */
    private static final int MAX_NESTED_DEPTH = 1;

    private final UnsafeApiRegistry registry;

    /** Builds an analyzer with the default rule set. */
    public JarAnalyzer() {
        this(new UnsafeApiRegistry());
    }

    /**
     * Builds an analyzer with a custom rule set.
     *
     * @param registry the rule registry
     */
    public JarAnalyzer(UnsafeApiRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Analyses a JAR.
     *
     * @param jarPath path to the JAR to analyse
     * @return the analysis result
     * @throws IOException if the JAR cannot be read
     */
    public AnalysisReport analyze(Path jarPath) throws IOException {
        Objects.requireNonNull(jarPath, "jarPath");
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException("JAR file not found: " + jarPath);
        }

        JarContents contents = new JarContents();
        try (InputStream in = Files.newInputStream(jarPath)) {
            collect(in, contents, 0);
        }

        TypeHierarchy hierarchy = new TypeHierarchy();
        for (byte[] classBytes : contents.classes.values()) {
            learnHierarchy(classBytes, hierarchy);
        }

        ClassScanner scanner = new ClassScanner(registry, hierarchy);
        List<Finding> findings = new ArrayList<>();
        int scanned = 0;
        int failed = 0;
        for (byte[] classBytes : contents.classes.values()) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
                findings.addAll(scanner.scan(node));
                scanned++;
            } catch (RuntimeException e) {
                // One malformed class must not abort the whole analysis
                failed++;
            }
        }

        PluginDescriptor descriptor = PluginYmlReader.read(contents.pluginYml);
        String jarName = jarPath.getFileName() == null
                ? jarPath.toString()
                : jarPath.getFileName().toString();

        return new AnalysisReport(jarName, descriptor, scanned, failed,
                contents.nestedJarCount, findings);
    }

    /**
     * Reads only a class header and registers it in the type hierarchy.
     *
     * <p>Very cheap, since the method bodies are never parsed.</p>
     *
     * @param classBytes the class bytes
     * @param hierarchy  the hierarchy to populate
     */
    private void learnHierarchy(byte[] classBytes, TypeHierarchy hierarchy) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            hierarchy.learn(reader.getClassName(), reader.getSuperName(), reader.getInterfaces());
        } catch (RuntimeException e) {
            // A failed hierarchy lookup must not stop the scan itself
        }
    }

    /**
     * Collects classes and {@code plugin.yml} from a ZIP stream.
     *
     * @param in       the input stream
     * @param contents where the entries are collected
     * @param depth    current nesting depth
     * @throws IOException if reading fails
     */
    private void collect(InputStream in, JarContents contents, int depth) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();

                if (name.endsWith(".class")) {
                    byte[] bytes = readEntry(zip, contents);
                    if (bytes != null) {
                        contents.classes.put(depth + ":" + name, bytes);
                    }
                } else if (depth == 0 && isPluginYml(name)) {
                    byte[] bytes = readEntry(zip, contents);
                    if (bytes != null && contents.pluginYml == null) {
                        contents.pluginYml = bytes;
                    }
                } else if (depth < MAX_NESTED_DEPTH && name.endsWith(".jar")) {
                    byte[] bytes = readEntry(zip, contents);
                    if (bytes != null) {
                        contents.nestedJarCount++;
                        collect(new ByteArrayInputStream(bytes), contents, depth + 1);
                    }
                }
            }
        }
    }

    /**
     * Tests whether an entry is a plugin descriptor.
     *
     * <p>Paper's {@code paper-plugin.yml} counts as well.</p>
     *
     * @param name the entry name
     * @return true if it is a plugin descriptor
     */
    private static boolean isPluginYml(String name) {
        return "plugin.yml".equals(name) || "paper-plugin.yml".equals(name);
    }

    /**
     * Reads the contents of a ZIP entry.
     *
     * <p>Returns {@code null} when a size cap is exceeded, which skips the
     * entry.</p>
     *
     * @param zip      the stream to read from
     * @param contents where the running total is tracked
     * @return the bytes read, or {@code null} if a cap was exceeded
     * @throws IOException if reading fails
     */
    private static byte[] readEntry(ZipInputStream zip, JarContents contents) throws IOException {
        if (contents.totalBytes >= MAX_TOTAL_UNCOMPRESSED_BYTES) {
            return null;
        }
        byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES);
        contents.totalBytes += bytes.length;
        return bytes;
    }

    /** The JAR contents gathered so far. */
    private static final class JarContents {
        /** Keyed by "depth:entryName", to keep same-named classes in multi-release JARs apart. */
        private final Map<String, byte[]> classes = new LinkedHashMap<>();
        private byte[] pluginYml;
        private int nestedJarCount;
        private long totalBytes;
    }
}
