package dev.marv.foliacode.cli;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;

/**
 * Finds the JAR this code is running from.
 *
 * <p>The runtime agent is attached with {@code -javaagent:<jar>}, and the JAR it needs
 * is the one already running: the CLI's fat JAR carries {@code Premain-Class} in its
 * manifest and bundles the agent classes.</p>
 *
 * <p>When FoliaCode is run from loose classes — during development, or from an IDE —
 * there is no such JAR. That case returns {@code null} so the caller can say so
 * plainly instead of building a {@code -javaagent} argument that would stop the
 * server from starting.</p>
 */
final class SelfLocator {

    private SelfLocator() {
    }

    /**
     * Locates the running JAR.
     *
     * @return the JAR path, or {@code null} if FoliaCode is not running from one
     */
    static Path runningJar() {
        try {
            CodeSource source = SelfLocator.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            boolean isJar = Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".jar");
            return isJar ? path.toAbsolutePath() : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
