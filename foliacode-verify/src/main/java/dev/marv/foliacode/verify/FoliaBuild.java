package dev.marv.foliacode.verify;

import java.util.Objects;

/**
 * A downloadable Folia server build.
 *
 * @param buildId          the PaperMC build number
 * @param channel          the release channel, for example {@code ALPHA}
 * @param minecraftVersion the Minecraft version this build targets
 * @param fileName         the jar file name
 * @param downloadUrl      the absolute download URL
 * @param sha256           the lowercase hex SHA-256 of the jar
 */
public record FoliaBuild(
        int buildId,
        String channel,
        String minecraftVersion,
        String fileName,
        String downloadUrl,
        String sha256
) {

    public FoliaBuild {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(downloadUrl, "downloadUrl");
        Objects.requireNonNull(sha256, "sha256");
        channel = channel == null ? "UNKNOWN" : channel;
    }

    /** The cache file name, which pins the build so different builds never collide. */
    public String cacheFileName() {
        return "folia-" + minecraftVersion + "-" + buildId + ".jar";
    }
}
