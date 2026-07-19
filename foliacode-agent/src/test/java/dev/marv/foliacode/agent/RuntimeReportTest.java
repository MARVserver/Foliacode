package dev.marv.foliacode.agent;

import dev.marv.foliacode.model.Category;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.UnsafeApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeReportTest {

    private static final UnsafeApi TELEPORT = new UnsafeApi(
            "org/bukkit/entity/Entity", "teleport", null, Category.ENTITY, Severity.HIGH,
            "reason", "remedy");

    private static final UnsafeApi SET_TYPE = new UnsafeApi(
            "org/bukkit/block/Block", "setType", null, Category.BLOCK, Severity.CRITICAL,
            "reason", "remedy");

    @Test
    @DisplayName("separates what ran from what never ran")
    void separatesExecutedFromUnexecuted() {
        RuntimeReport report = new RuntimeReport(2, 0, List.of(
                observation(1, TELEPORT, 5, ThreadVerdict.TICK_THREAD),
                observation(2, SET_TYPE, 0)));

        assertEquals(2, report.instrumentedSites());
        assertEquals(1, report.executed().size());
        assertEquals(1, report.neverExecuted().size());
        assertEquals(1, report.executedCount(Severity.HIGH));
        assertEquals(0, report.executedCount(Severity.CRITICAL),
                "a site that never ran must not be counted as executed");
    }

    @Test
    @DisplayName("shortlists sites the server said ran off a tick thread")
    void shortlistsOffTickThreadExecutions() {
        SiteObservation offTick = observation(1, TELEPORT, 3, ThreadVerdict.OFF_TICK_THREAD);
        SiteObservation onTick = observation(2, SET_TYPE, 9, ThreadVerdict.TICK_THREAD);
        RuntimeReport report = new RuntimeReport(1, 0, List.of(offTick, onTick));

        assertTrue(offTick.ranOffTickThread());
        assertFalse(onTick.ranOffTickThread());
        assertEquals(1, report.offTickThreadExecutions().size());
        assertEquals("Entity.teleport",
                report.offTickThreadExecutions().get(0).site().api().displayName());
    }

    @Test
    @DisplayName("an unknown thread verdict is not treated as off-tick")
    void unknownVerdictIsNotOffTick() {
        SiteObservation unknown = observation(1, TELEPORT, 4, ThreadVerdict.UNKNOWN);

        assertFalse(unknown.ranOffTickThread(),
                "not knowing is different from knowing it was wrong");
    }

    @Test
    @DisplayName("renders JSON carrying the execution counts and thread evidence")
    void rendersJson() {
        RuntimeReport report = new RuntimeReport(1, 2, List.of(
                observation(1, TELEPORT, 3, ThreadVerdict.OFF_TICK_THREAD)));

        String json = RuntimeJsonReport.render(report);

        assertTrue(json.contains("\"instrumentedSites\": 1"), json);
        assertTrue(json.contains("\"methodReferenceSites\": 2"), json);
        assertTrue(json.contains("\"executionCount\": 3"), json);
        assertTrue(json.contains("\"ranOffTickThread\": true"), json);
        assertTrue(json.contains("\"verdict\": \"OFF_TICK_THREAD\""), json);
        assertTrue(json.contains("\"api\": \"Entity.teleport\""), json);
    }

    @Test
    @DisplayName("the text report says an unexecuted site is untested, not safe")
    void textReportDoesNotClaimSafety() {
        RuntimeReport report = new RuntimeReport(1, 0, List.of(
                observation(1, TELEPORT, 0)));

        String text = RuntimeTextReport.render(report);

        assertTrue(text.contains("Watched but never executed: 1"), text);
        assertTrue(text.contains("untested, not proven"), text);
    }

    /**
     * Builds an observation that never ran.
     *
     * @param id  the site id
     * @param api the rule
     * @param count execution count, expected to be zero
     * @return the observation
     */
    private static SiteObservation observation(int id, UnsafeApi api, long count) {
        return new SiteObservation(site(id, api), count, List.of());
    }

    /**
     * Builds an observation that ran on one thread.
     *
     * @param id      the site id
     * @param api     the rule
     * @param count   execution count
     * @param verdict what the server said about the thread
     * @return the observation
     */
    private static SiteObservation observation(int id, UnsafeApi api, long count, ThreadVerdict verdict) {
        return new SiteObservation(site(id, api), count,
                List.of(new ThreadObservation("worker-" + id, verdict, count)));
    }

    private static CallSite site(int id, UnsafeApi api) {
        return new CallSite(id, "com.example.Plugin", "onEnable", "()V", 42, api.owner(), api);
    }
}
