package dev.marv.foliacode.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Settings for one server verification run.
 *
 * <p>{@code enabled} defaults to {@code false} and has no convenience factory that flips it
 * on implicitly. Running a verification downloads and executes a third party server jar, so
 * it must be something the user asked for rather than something they got by accident.</p>
 *
 * @param minecraftVersion     the Minecraft version to boot
 * @param enabled              whether the run is permitted; see {@link ServerVerifier#verify}
 * @param memoryMb             heap size for the server JVM
 * @param bootTimeout          how long to wait for a conclusion
 * @param keepServerDirectory  whether to keep the sandbox for inspection instead of deleting it
 * @param extraPlugins         dependency jars to install alongside the plugin under test
 */
public record ServerVerificationConfig(
        boolean enabled,
        String minecraftVersion,
        int memoryMb,
        Duration bootTimeout,
        boolean keepServerDirectory,
        List<Path> extraPlugins
) {

    /** Default heap size. Folia does not boot in appreciably less. */
    public static final int DEFAULT_MEMORY_MB = 1024;

    /** Default boot timeout. */
    public static final Duration DEFAULT_BOOT_TIMEOUT = Duration.ofSeconds(180);

    /** Minecraft version used when the caller does not choose one. */
    public static final String DEFAULT_MINECRAFT_VERSION = "1.21.4";

    public ServerVerificationConfig {
        minecraftVersion = minecraftVersion == null || minecraftVersion.isBlank()
                ? DEFAULT_MINECRAFT_VERSION
                : minecraftVersion.trim();
        memoryMb = memoryMb <= 0 ? DEFAULT_MEMORY_MB : memoryMb;
        bootTimeout = bootTimeout == null || bootTimeout.isZero() || bootTimeout.isNegative()
                ? DEFAULT_BOOT_TIMEOUT
                : bootTimeout;
        extraPlugins = extraPlugins == null ? List.of() : List.copyOf(extraPlugins);
    }

    /**
     * A disabled configuration with default settings.
     *
     * @return a configuration that {@link ServerVerifier} will refuse to run
     */
    public static ServerVerificationConfig disabled() {
        return new ServerVerificationConfig(
                false, DEFAULT_MINECRAFT_VERSION, DEFAULT_MEMORY_MB, DEFAULT_BOOT_TIMEOUT, false, List.of());
    }

    /**
     * An enabled configuration for a Minecraft version, with defaults elsewhere.
     *
     * @param minecraftVersion the version to boot
     * @return an enabled configuration
     */
    public static ServerVerificationConfig enabledFor(String minecraftVersion) {
        return new ServerVerificationConfig(
                true, minecraftVersion, DEFAULT_MEMORY_MB, DEFAULT_BOOT_TIMEOUT, false, List.of());
    }

    /**
     * Returns a copy with a different heap size.
     *
     * @param newMemoryMb the heap size in megabytes
     * @return the updated configuration
     */
    public ServerVerificationConfig withMemoryMb(int newMemoryMb) {
        return new ServerVerificationConfig(
                enabled, minecraftVersion, newMemoryMb, bootTimeout, keepServerDirectory, extraPlugins);
    }

    /**
     * Returns a copy with a different boot timeout.
     *
     * @param newBootTimeout the timeout
     * @return the updated configuration
     */
    public ServerVerificationConfig withBootTimeout(Duration newBootTimeout) {
        return new ServerVerificationConfig(
                enabled, minecraftVersion, memoryMb, newBootTimeout, keepServerDirectory, extraPlugins);
    }

    /**
     * Returns a copy with additional plugin jars installed into the sandbox.
     *
     * @param jars the dependency jars
     * @return the updated configuration
     */
    public ServerVerificationConfig withExtraPlugins(List<Path> jars) {
        return new ServerVerificationConfig(
                enabled, minecraftVersion, memoryMb, bootTimeout, keepServerDirectory, jars);
    }

    /**
     * Returns a copy that keeps the sandbox directory after the run.
     *
     * @param keep whether to keep it
     * @return the updated configuration
     */
    public ServerVerificationConfig withKeepServerDirectory(boolean keep) {
        return new ServerVerificationConfig(
                enabled, minecraftVersion, memoryMb, bootTimeout, keep, extraPlugins);
    }

    /** Whether the configured heap is large enough for Folia to realistically start. */
    public boolean hasViableHeap() {
        return memoryMb >= BootLogAnalyzer.MINIMUM_VIABLE_HEAP_MB;
    }
}
