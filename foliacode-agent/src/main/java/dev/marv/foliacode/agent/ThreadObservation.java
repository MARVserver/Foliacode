package dev.marv.foliacode.agent;

import java.util.Objects;

/**
 * How often one call site ran on one particular thread.
 *
 * <p>The raw thread name is kept alongside the server's verdict on purpose. The
 * verdict is what the tool concluded; the name is the evidence a human can check
 * it against.</p>
 *
 * @param threadName name of the thread as reported by the JVM
 * @param verdict    what the server said about that thread
 * @param count      how many times the site ran there
 */
public record ThreadObservation(String threadName, ThreadVerdict verdict, long count) {

    public ThreadObservation {
        Objects.requireNonNull(threadName, "threadName");
        Objects.requireNonNull(verdict, "verdict");
    }
}
