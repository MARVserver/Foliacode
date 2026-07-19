package dev.marv.foliacode.testsupport;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compiles Java source on the fly so tests run against real {@code .class}
 * files.
 *
 * <p>Bytecode assembled by hand with ASM does not necessarily match what
 * javac emits. invokedynamic in particular is awkward to hand-build, which
 * makes it easy to end up with tests that pass while the analyzer misses the
 * same construct in a real plugin. Letting javac generate it removes that
 * gap.</p>
 */
public final class JavaSourceCompiler {

    private JavaSourceCompiler() {
    }

    /**
     * Compiles a set of sources.
     *
     * @param workDir the working directory
     * @param sources fully-qualified class name to source text
     * @return the directory holding the generated {@code .class} files
     * @throws IOException if compilation or file access fails
     */
    public static Path compile(Path workDir, Map<String, String> sources) throws IOException {
        Path sourceDir = workDir.resolve("src");
        Path outputDir = workDir.resolve("classes");
        Files.createDirectories(outputDir);

        List<File> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = sourceDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            files.add(file.toFile());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler available; run the tests on a JDK");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files);
            // -g: emit debug info including line numbers, which the line-number assertions need
            List<String> options = List.of("-d", outputDir.toString(), "-g", "-nowarn");

            boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            if (!success) {
                throw new IllegalStateException("Compilation failed:\n" + formatDiagnostics(diagnostics));
            }
        }
        return outputDir;
    }

    /**
     * Reads the bytes of a compiled class.
     *
     * @param classesDir the compiler output directory
     * @param className  fully-qualified class name
     * @return the class bytes
     * @throws IOException if reading fails
     */
    public static byte[] readClass(Path classesDir, String className) throws IOException {
        Path classFile = classesDir.resolve(className.replace('.', '/') + ".class");
        if (!Files.exists(classFile)) {
            throw new IOException("Class file not found: " + classFile);
        }
        return Files.readAllBytes(classFile);
    }

    /**
     * Formats compiler diagnostics for readability.
     *
     * @param diagnostics the diagnostics
     * @return the formatted text
     */
    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> "  " + d.getSource() + ":" + d.getLineNumber() + " " + d.getMessage(null))
                .collect(Collectors.joining("\n"));
    }
}
