package dev.marv.foliacode.cli;

import dev.marv.foliacode.verify.ServerVerificationConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Project configuration read from {@code foliacode.yml}.
 *
 * <p>Server verification downloads a Folia jar and executes it. That is a meaningful
 * escalation from reading bytecode, so it is off unless someone deliberately turns it
 * on — either here or with an explicit flag. Silence is never taken as consent.</p>
 *
 * <p>Example:</p>
 * <pre>
 * serverVerification:
 *   enabled: true
 *   minecraftVersion: "1.21.4"
 *   memoryMb: 2048
 *   bootTimeoutSeconds: 180
 *   keepServerDirectory: false
 * </pre>
 */
public record FoliacodeConfig(ServerVerificationConfig serverVerification) {

    /** Conventional file name, looked up in the working directory. */
    public static final String FILE_NAME = "foliacode.yml";

    /** Configuration used when no file is present: verification disabled. */
    public static FoliacodeConfig defaults() {
        return new FoliacodeConfig(ServerVerificationConfig.disabled());
    }

    /**
     * Loads configuration from a directory, falling back to defaults.
     *
     * @param directory the directory to look in
     * @return the configuration
     * @throws IOException if the file exists but cannot be read
     */
    public static FoliacodeConfig loadFrom(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return defaults();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /**
     * Parses configuration from YAML text.
     *
     * @param yaml the YAML document
     * @return the configuration; defaults for anything absent or unreadable
     */
    public static FoliacodeConfig parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return defaults();
        }
        Map<?, ?> root = loadMap(yaml);
        if (root == null) {
            return defaults();
        }
        Object section = root.get("serverVerification");
        if (!(section instanceof Map<?, ?> verification)) {
            return defaults();
        }

        boolean enabled = booleanValue(verification, "enabled", false);
        String version = stringValue(verification, "minecraftVersion",
                ServerVerificationConfig.DEFAULT_MINECRAFT_VERSION);
        int memoryMb = intValue(verification, "memoryMb",
                ServerVerificationConfig.DEFAULT_MEMORY_MB);
        int timeoutSeconds = intValue(verification, "bootTimeoutSeconds",
                (int) ServerVerificationConfig.DEFAULT_BOOT_TIMEOUT.toSeconds());
        boolean keep = booleanValue(verification, "keepServerDirectory", false);

        return new FoliacodeConfig(new ServerVerificationConfig(
                enabled, version, memoryMb, Duration.ofSeconds(timeoutSeconds), keep, List.of()));
    }

    /**
     * Parses the document into a map.
     *
     * <p>Uses {@link SafeConstructor} because a configuration file can arrive from
     * anywhere; the default constructor would let a document name arbitrary classes
     * to instantiate.</p>
     *
     * @param yaml the YAML document
     * @return the top-level map, or {@code null} if it is not a map or does not parse
     */
    private static Map<?, ?> loadMap(String yaml) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
            return loaded instanceof Map<?, ?> map ? map : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads a string value.
     *
     * @param map          the map
     * @param key          the key
     * @param defaultValue value to use when absent
     * @return the value
     */
    private static String stringValue(Map<?, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /**
     * Reads a boolean value, tolerating a quoted {@code "true"}.
     *
     * @param map          the map
     * @param key          the key
     * @param defaultValue value to use when absent
     * @return the value
     */
    private static boolean booleanValue(Map<?, ?> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    /**
     * Reads an integer value.
     *
     * @param map          the map
     * @param key          the key
     * @param defaultValue value to use when absent or unparseable
     * @return the value
     */
    private static int intValue(Map<?, ?> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
