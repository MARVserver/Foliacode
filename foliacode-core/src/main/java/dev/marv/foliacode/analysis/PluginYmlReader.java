package dev.marv.foliacode.analysis;

import dev.marv.foliacode.model.PluginDescriptor;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Reads {@code plugin.yml}.
 *
 * <p>The input is a third-party JAR, so the YAML is always parsed with
 * {@link SafeConstructor}. A default {@code Yaml} instantiates whatever class
 * the document asks for, which is not something a tool that analyses unknown
 * JARs can afford.</p>
 */
public final class PluginYmlReader {

    private PluginYmlReader() {
    }

    /**
     * Parses the contents of a {@code plugin.yml}.
     *
     * @param yamlBytes the YAML bytes; may be {@code null}
     * @return the parsed metadata, or {@link PluginDescriptor#UNKNOWN} if it cannot be read
     */
    public static PluginDescriptor read(byte[] yamlBytes) {
        if (yamlBytes == null || yamlBytes.length == 0) {
            return PluginDescriptor.UNKNOWN;
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object loaded = yaml.load(new ByteArrayInputStream(yamlBytes));
            if (!(loaded instanceof Map<?, ?> map)) {
                return PluginDescriptor.UNKNOWN;
            }
            return new PluginDescriptor(
                    stringValue(map, "name"),
                    stringValue(map, "version"),
                    stringValue(map, "main"),
                    booleanValue(map, "folia-supported")
            );
        } catch (RuntimeException e) {
            // A broken plugin.yml must not abort the whole analysis
            return PluginDescriptor.UNKNOWN;
        }
    }

    /**
     * Extracts a string value.
     *
     * @param map the map
     * @param key the key
     * @return the value, or {@code null} if absent
     */
    private static String stringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Extracts a boolean value.
     *
     * <p>A quoted form such as {@code folia-supported: "true"} is accepted
     * too.</p>
     *
     * @param map the map
     * @param key the key
     * @return the value, or {@code null} if undeclared
     */
    private static Boolean booleanValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Decodes bytes as UTF-8 text (for diagnostics). */
    static String asText(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
