package dev.marv.foliacode.cli;

import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.transform.JarTransformer;
import dev.marv.foliacode.transform.TransformAction;
import dev.marv.foliacode.transform.TransformJsonReport;
import dev.marv.foliacode.transform.TransformReport;
import dev.marv.foliacode.transform.TransformTextReport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Implements the {@code transform} command: rewrite the call sites that can be
 * rewritten, and report the ones that cannot.
 *
 * <p>The exit code distinguishes "everything this tool can fix is fixed" from "work
 * remains", because in a pipeline that is the only question worth branching on.</p>
 */
final class TransformCommand {

    /** Exit code: the output has no findings this tool considers serious. */
    static final int EXIT_OK = 0;

    /** Exit code: serious findings remain that a person has to deal with. */
    static final int EXIT_WORK_REMAINS = 1;

    /** Exit code: the command could not run. */
    static final int EXIT_ERROR = 2;

    /** Suffix added to the input file name when no output path is given. */
    private static final String DEFAULT_SUFFIX = "-folia.jar";

    private TransformCommand() {
    }

    /**
     * Runs the command.
     *
     * @param args the full argument array, including {@code transform}
     * @param out  standard output
     * @param err  standard error
     * @return the exit code
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("Invalid arguments: " + e.getMessage());
            return EXIT_ERROR;
        }

        if (!Files.isRegularFile(options.source())) {
            err.println("Plugin JAR not found: " + options.source());
            return EXIT_ERROR;
        }

        TransformReport report;
        try {
            JarTransformer transformer = new JarTransformer();
            report = options.dryRun()
                    ? transformer.dryRun(options.source())
                    : transformer.transform(options.source(), options.resolvedOutput());
        } catch (IOException e) {
            err.println("Transform failed: " + e.getMessage());
            return EXIT_ERROR;
        }

        out.print(TransformTextReport.render(report));

        if (options.jsonOutput() != null) {
            try {
                writeJson(options.jsonOutput(), report);
                out.println();
                out.println("Wrote JSON to: " + options.jsonOutput());
            } catch (IOException e) {
                err.println("Failed to write JSON: " + e.getMessage());
                return EXIT_ERROR;
            }
        }

        boolean seriousWorkRemains = report.refused().stream()
                .map(TransformAction::severity)
                .anyMatch(severity -> severity.isAtLeast(Severity.HIGH));
        return seriousWorkRemains ? EXIT_WORK_REMAINS : EXIT_OK;
    }

    /**
     * Writes the JSON report.
     *
     * @param path   the output path
     * @param report the transform result
     * @throws IOException if writing fails
     */
    private static void writeJson(Path path, TransformReport report) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, TransformJsonReport.render(report), StandardCharsets.UTF_8);
    }

    /**
     * Options for the {@code transform} command.
     *
     * @param source     the plugin to read
     * @param output     where to write, or {@code null} to derive it from the source
     * @param jsonOutput where to write the JSON report, or {@code null}
     * @param dryRun     whether to report without writing a JAR
     */
    record Options(Path source, Path output, Path jsonOutput, boolean dryRun) {

        /**
         * Parses the arguments.
         *
         * @param args the argument array, including {@code transform}
         * @return the parsed options
         * @throws IllegalArgumentException if the arguments are invalid
         */
        static Options parse(String[] args) {
            if (args.length < 2) {
                throw new IllegalArgumentException("No plugin JAR given");
            }
            Path source = Path.of(args[1]);
            Path output = null;
            Path json = null;
            boolean dryRun = false;

            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--out" -> output = Path.of(requireValue(args, ++i, "--out"));
                    case "--json" -> json = Path.of(requireValue(args, ++i, "--json"));
                    case "--dry-run" -> dryRun = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if (dryRun && output != null) {
                throw new IllegalArgumentException("--dry-run writes nothing, so --out has no meaning");
            }
            return new Options(source, output, json, dryRun);
        }

        /**
         * Where the transformed JAR goes.
         *
         * <p>Beside the original, with a suffix. Never over it — the input has to stay
         * available to fall back to.</p>
         *
         * @return the output path
         */
        Path resolvedOutput() {
            if (output != null) {
                return output;
            }
            String fileName = source.getFileName().toString();
            String base = fileName.endsWith(".jar")
                    ? fileName.substring(0, fileName.length() - 4)
                    : fileName;
            Path parent = source.toAbsolutePath().getParent();
            return parent == null
                    ? Path.of(base + DEFAULT_SUFFIX)
                    : parent.resolve(base + DEFAULT_SUFFIX);
        }

        /**
         * Reads the value that follows an option.
         *
         * @param args   the argument array
         * @param index  position of the value
         * @param option the option name, for the error message
         * @return the value
         * @throws IllegalArgumentException if no value is present
         */
        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
