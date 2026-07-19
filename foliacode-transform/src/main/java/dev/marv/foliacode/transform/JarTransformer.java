package dev.marv.foliacode.transform;

import dev.marv.foliacode.analysis.JarAnalyzer;
import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.Verdict;
import dev.marv.foliacode.rules.TypeHierarchy;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites a plugin JAR, then checks its own work by re-analysing the result.
 *
 * <p>The original file is never modified. A transform produces a new JAR beside it,
 * so the input remains available to compare against and to fall back to.</p>
 *
 * <p>Classes shaded into the plugin as nested JARs are left untouched. Rewriting a
 * dependency's bytecode inside somebody's artifact is a decision for its author, not
 * for a tool that was asked to look at the plugin.</p>
 */
public final class JarTransformer {

    /** Cap on the size of any single entry, matching the analyzer's. */
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    private final UnsafeApiRegistry registry;
    private final TransformRules transformRules;

    /** Builds a transformer with the default rule sets. */
    public JarTransformer() {
        this(new UnsafeApiRegistry(), new TransformRules());
    }

    /**
     * Builds a transformer with custom rule sets.
     *
     * @param registry       the analyzer's rule set
     * @param transformRules the rewrites to perform
     */
    public JarTransformer(UnsafeApiRegistry registry, TransformRules transformRules) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transformRules = Objects.requireNonNull(transformRules, "transformRules");
    }

    /**
     * Transforms a JAR and writes the result.
     *
     * @param source the JAR to read
     * @param target where to write the result; must not be the source
     * @return what was done
     * @throws IOException if the JAR cannot be read or written
     */
    public TransformReport transform(Path source, Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (Files.exists(target) && Files.isSameFile(source, target)) {
            throw new IOException("Refusing to overwrite the plugin being transformed: " + source);
        }
        return run(source, target, false);
    }

    /**
     * Works out what a transform would do without keeping the result.
     *
     * <p>The output is still built and analysed — in a temporary file that is deleted
     * afterwards — so a dry run reports on the JAR that would actually be produced
     * rather than on a prediction of it.</p>
     *
     * @param source the JAR to read
     * @return what would be done
     * @throws IOException if the JAR cannot be read
     */
    public TransformReport dryRun(Path source) throws IOException {
        Path temporary = Files.createTempFile("foliacode-dryrun", ".jar");
        try {
            return run(source, temporary, true);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Reads, rewrites, writes and re-analyses.
     *
     * @param source the JAR to read
     * @param target where to write
     * @param dryRun whether the output is temporary
     * @return the report
     * @throws IOException if the JAR cannot be read or written
     */
    private TransformReport run(Path source, Path target, boolean dryRun) throws IOException {
        Objects.requireNonNull(source, "source");
        if (!Files.isRegularFile(source)) {
            throw new IOException("JAR file not found: " + source);
        }

        AnalysisReport before = new JarAnalyzer(registry).analyze(source);

        JarContents contents = read(source);
        if (contents.signed) {
            throw new IOException(
                    "This JAR is signed. Rewriting its classes would invalidate the signature, "
                            + "and stripping the signature would silently discard a guarantee "
                            + "somebody meant to make. Transform the unsigned build instead.");
        }

        TypeHierarchy hierarchy = new TypeHierarchy();
        for (Map.Entry<String, byte[]> entry : contents.classes().entrySet()) {
            learnHierarchy(entry.getValue(), hierarchy);
        }

        CallSiteRewriter rewriter = new CallSiteRewriter(registry, transformRules, hierarchy);
        List<TransformAction> actions = new ArrayList<>();
        int classesRewritten = 0;
        int classesScanned = 0;

        for (Map.Entry<String, byte[]> entry : contents.classes().entrySet()) {
            try {
                ClassNode node = new ClassNode();
                ClassReader reader = new ClassReader(entry.getValue());
                reader.accept(node, 0);
                classesScanned++;

                List<TransformAction> classActions = rewriter.rewrite(node);
                actions.addAll(classActions);

                if (classActions.stream().anyMatch(TransformAction::isRewritten)) {
                    // Stack effects are unchanged by the substitution, so frames survive;
                    // COMPUTE_MAXS is belt and braces and never loads a class to do it.
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    node.accept(writer);
                    entry.setValue(writer.toByteArray());
                    classesRewritten++;
                }
            } catch (RuntimeException e) {
                // One malformed class must not cost the user the whole transform. It is
                // copied through unchanged, exactly as it arrived.
                classesScanned++;
            }
        }

        boolean anythingRewritten = classesRewritten > 0;
        if (anythingRewritten) {
            addShimClasses(contents);
        }

        boolean declaredFoliaSupport = false;
        write(target, contents);
        AnalysisReport after = new JarAnalyzer(registry).analyze(target);

        // folia-supported is a promise to the server, so it is only made once the
        // rewritten JAR has been read back and found clean.
        TransformReport provisional = new TransformReport(
                name(source), dryRun ? null : name(target), classesScanned, classesRewritten,
                contents.nestedJars(), actions, before, after, false);

        if (provisional.resultingVerdict() == Verdict.READY && contents.pluginYmlEntry() != null) {
            declaredFoliaSupport = declareFoliaSupport(contents);
            if (declaredFoliaSupport) {
                write(target, contents);
                after = new JarAnalyzer(registry).analyze(target);
            }
        }

        return new TransformReport(
                name(source), dryRun ? null : name(target), classesScanned, classesRewritten,
                contents.nestedJars(), actions, before, after, declaredFoliaSupport);
    }

    /**
     * Adds {@code folia-supported: true} to the plugin descriptor.
     *
     * @param contents the JAR contents, modified in place
     * @return true if the descriptor was changed
     */
    private boolean declareFoliaSupport(JarContents contents) {
        String entryName = contents.pluginYmlEntry();
        byte[] bytes = contents.entries().get(entryName);
        if (bytes == null) {
            return false;
        }
        String yaml = new String(bytes, StandardCharsets.UTF_8);
        // Only a top-level key is touched. Anything indented belongs to a nested
        // structure and is none of this tool's business.
        String updated = yaml.lines()
                .filter(line -> !line.startsWith("folia-supported:"))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"))
                + "folia-supported: true\n";
        contents.entries().put(entryName, updated.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    /**
     * Copies the shim classes into the JAR.
     *
     * <p>They are read from FoliaCode's own classpath, so what ships inside a
     * transformed plugin is exactly the code in this repository.</p>
     *
     * @param contents the JAR contents, modified in place
     * @throws IOException if a shim class cannot be read
     */
    private void addShimClasses(JarContents contents) throws IOException {
        for (String internalName : TransformRules.SHIM_CLASSES) {
            String resource = "/" + internalName + ".class";
            try (InputStream in = JarTransformer.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("Shim class missing from the FoliaCode build: " + resource);
                }
                contents.entries().put(internalName + ".class", in.readAllBytes());
            }
        }
    }

    /**
     * Reads a class header into the type hierarchy.
     *
     * @param classBytes the class bytes
     * @param hierarchy  the hierarchy to populate
     */
    private void learnHierarchy(byte[] classBytes, TypeHierarchy hierarchy) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            hierarchy.learn(reader.getClassName(), reader.getSuperName(), reader.getInterfaces());
        } catch (RuntimeException e) {
            // A hierarchy gap costs subtype resolution for one class, not the transform.
        }
    }

    /**
     * Reads every entry of a JAR into memory.
     *
     * @param source the JAR
     * @return the contents
     * @throws IOException if reading fails
     */
    private JarContents read(Path source) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, byte[]> classes = new LinkedHashMap<>();
        String pluginYml = null;
        int nestedJars = 0;
        boolean signed = false;

        try (InputStream in = Files.newInputStream(source);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] bytes = zip.readNBytes(MAX_ENTRY_BYTES);
                entries.put(name, bytes);

                if (name.endsWith(".class")) {
                    classes.put(name, bytes);
                } else if (name.endsWith(".jar")) {
                    nestedJars++;
                } else if (isSignatureFile(name)) {
                    signed = true;
                } else if (pluginYml == null && isPluginYml(name)) {
                    pluginYml = name;
                }
            }
        }
        return new JarContents(entries, classes, pluginYml, nestedJars, signed);
    }

    /**
     * Writes the JAR out.
     *
     * @param target   the output path
     * @param contents the entries to write
     * @throws IOException if writing fails
     */
    private void write(Path target, JarContents contents) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : contents.entries().entrySet()) {
                byte[] bytes = contents.classes().getOrDefault(entry.getKey(), entry.getValue());
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
    }

    /**
     * Tests whether an entry is a JAR signature.
     *
     * @param name the entry name
     * @return true if it is part of a signature
     */
    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".DSA") || upper.endsWith(".RSA"));
    }

    /**
     * Tests whether an entry is a plugin descriptor.
     *
     * @param name the entry name
     * @return true if it is one
     */
    private static boolean isPluginYml(String name) {
        return "plugin.yml".equals(name) || "paper-plugin.yml".equals(name);
    }

    /**
     * File name of a path, for reports.
     *
     * @param path the path
     * @return the file name
     */
    private static String name(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    /**
     * Everything read out of a JAR.
     *
     * @param entries        every entry, in the order they appeared
     * @param classes        the class entries, whose values are replaced as they are rewritten
     * @param pluginYmlEntry name of the plugin descriptor entry, or {@code null}
     * @param nestedJars     how many shaded JARs were present
     * @param signed         whether the JAR carries a signature
     */
    private record JarContents(
            Map<String, byte[]> entries,
            Map<String, byte[]> classes,
            String pluginYmlEntry,
            int nestedJars,
            boolean signed
    ) {
    }
}
