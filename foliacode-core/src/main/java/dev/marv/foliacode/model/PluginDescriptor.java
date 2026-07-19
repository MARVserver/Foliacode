package dev.marv.foliacode.model;

/**
 * Plugin metadata read from {@code plugin.yml}.
 *
 * @param name            plugin name, or {@code null} if unavailable
 * @param version         version, or {@code null} if unavailable
 * @param mainClass       main class, or {@code null} if unavailable
 * @param foliaSupported  value of {@code folia-supported}, or {@code null} if undeclared
 */
public record PluginDescriptor(
        String name,
        String version,
        String mainClass,
        Boolean foliaSupported
) {

    /** Empty descriptor used when no {@code plugin.yml} was found. */
    public static final PluginDescriptor UNKNOWN = new PluginDescriptor(null, null, null, null);

    /** Whether a {@code plugin.yml} was successfully read. */
    public boolean isPresent() {
        return name != null || mainClass != null;
    }

    /** Whether {@code folia-supported: true} is declared. */
    public boolean declaresFoliaSupport() {
        return Boolean.TRUE.equals(foliaSupported);
    }

    /** "Name version" for display. */
    public String displayName() {
        if (name == null) {
            return "(unknown)";
        }
        return version == null ? name : name + " " + version;
    }
}
