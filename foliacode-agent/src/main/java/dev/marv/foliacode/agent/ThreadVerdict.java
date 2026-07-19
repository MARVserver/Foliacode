package dev.marv.foliacode.agent;

/**
 * What the server said about the thread a call ran on.
 *
 * <p>Folia's thread model is not something this agent should guess at from
 * thread names. The only authority is the server itself, reached through
 * {@code Bukkit.isPrimaryThread()}. When that cannot be consulted — because
 * Bukkit is not loaded yet, or the call came from a plain JVM thread before the
 * server started — the answer is {@link #UNKNOWN} rather than an assumption.</p>
 */
public enum ThreadVerdict {

    /** {@code Bukkit.isPrimaryThread()} returned true: a server tick thread. */
    TICK_THREAD("tick thread"),

    /** {@code Bukkit.isPrimaryThread()} returned false: not a server tick thread. */
    OFF_TICK_THREAD("off-tick thread"),

    /** The server could not be consulted. Reported as such, never guessed. */
    UNKNOWN("thread context unknown");

    private final String label;

    ThreadVerdict(String label) {
        this.label = label;
    }

    /** Short description for reports. */
    public String label() {
        return label;
    }
}
