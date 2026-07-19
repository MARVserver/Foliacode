package dev.marv.foliacode.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Options passed on the {@code -javaagent} command line.
 *
 * <p>The JVM hands an agent one string, so the format is
 * {@code key=value,key=value}. Several packages are separated with {@code ;},
 * since {@code ,} is already spoken for.</p>
 *
 * <p>Unknown keys are ignored rather than rejected. A server that refuses to boot
 * because a diagnostic tool disliked an argument is a bad trade.</p>
 *
 * @param reportPath      where the JSON report is written
 * @param textPath        where a human-readable report is written, or {@code null} for none
 * @param includePackages internal-name prefixes to instrument; empty means everything
 *                        outside the JDK and the server
 * @param quiet           whether to suppress the agent's own console output
 */
public record AgentOptions(
        Path reportPath, Path textPath, List<String> includePackages, boolean quiet) {

    /** Report path used when none is given. */
    public static final String DEFAULT_REPORT = "foliacode-runtime.json";

    public AgentOptions {
        Objects.requireNonNull(reportPath, "reportPath");
        includePackages = List.copyOf(Objects.requireNonNull(includePackages, "includePackages"));
    }

    /**
     * Parses the agent argument string.
     *
     * @param args the raw argument string; may be {@code null} or empty
     * @return the parsed options
     */
    public static AgentOptions parse(String args) {
        Path reportPath = Path.of(DEFAULT_REPORT);
        Path textPath = null;
        List<String> include = new ArrayList<>();
        boolean quiet = false;

        if (args != null && !args.isBlank()) {
            for (String pair : args.split(",")) {
                String trimmed = pair.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int equals = trimmed.indexOf('=');
                String key = equals < 0 ? trimmed : trimmed.substring(0, equals).trim();
                String value = equals < 0 ? "" : trimmed.substring(equals + 1).trim();

                switch (key) {
                    case "report" -> {
                        if (!value.isEmpty()) {
                            reportPath = Path.of(value);
                        }
                    }
                    case "text" -> {
                        if (!value.isEmpty()) {
                            textPath = Path.of(value);
                        }
                    }
                    case "include" -> include.addAll(parsePackages(value));
                    case "quiet" -> quiet = value.isEmpty() || Boolean.parseBoolean(value);
                    default -> {
                        // Ignored on purpose; see the class javadoc.
                    }
                }
            }
        }
        return new AgentOptions(reportPath, textPath, include, quiet);
    }

    /**
     * Splits and normalises a package list.
     *
     * <p>Users think in {@code com.example} but bytecode is written
     * {@code com/example}, so both are accepted and stored in the internal form.</p>
     *
     * @param value the raw value
     * @return internal-name prefixes
     */
    private static List<String> parsePackages(String value) {
        List<String> packages = new ArrayList<>();
        for (String entry : value.split(";")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                packages.add(trimmed.replace('.', '/'));
            }
        }
        return packages;
    }
}
