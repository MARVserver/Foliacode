package dev.marv.foliacode.report;

import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.CallKind;
import dev.marv.foliacode.model.Category;
import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.PluginDescriptor;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.UnsafeApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the output of {@link TextReport} and {@link JsonReport}.
 */
class ReportTest {

    private static final UnsafeApi SCHEDULER_RULE = new UnsafeApi(
            "org/bukkit/scheduler/BukkitScheduler", "runTask", null,
            Category.SCHEDULER, Severity.CRITICAL,
            "Folia does not support synchronous scheduling",
            "Switch to RegionScheduler");

    private static final UnsafeApi TELEPORT_RULE = new UnsafeApi(
            "org/bukkit/entity/Entity", "teleport", null,
            Category.ENTITY, Severity.HIGH,
            "A synchronous teleport cannot cross regions",
            "Use teleportAsync");

    @Test
    @DisplayName("text output includes the verdict and the findings")
    void textReportContainsVerdictAndFindings() {
        String text = TextReport.render(sampleReport(), false);

        assertTrue(text.contains("NOT READY"), "the verdict should be present");
        assertTrue(text.contains("BukkitScheduler.runTask"));
        assertTrue(text.contains("Entity.teleport"));
        assertTrue(text.contains("Switch to RegionScheduler"), "the remedy should be present");
        assertTrue(text.contains("example.MyPlugin"), "the call site should be present");
    }

    @Test
    @DisplayName("warns when folia-supported: true is declared but incompatibilities exist")
    void textReportWarnsOnFalseFoliaDeclaration() {
        AnalysisReport report = new AnalysisReport(
                "Declared.jar",
                new PluginDescriptor("Declared", "1.0", "example.Declared", Boolean.TRUE),
                10, 0, 0,
                List.of(finding(SCHEDULER_RULE, "example.Declared", "onEnable", 10)));

        String text = TextReport.render(report, false);

        assertTrue(text.contains("WARNING"), "a false Folia support declaration must be called out");
    }

    @Test
    @DisplayName("states the limits of static analysis when nothing is found")
    void textReportNotesLimitsWhenClean() {
        AnalysisReport report = new AnalysisReport(
                "Clean.jar", PluginDescriptor.UNKNOWN, 5, 0, 0, List.of());

        String text = TextReport.render(report, false);

        assertTrue(text.contains("No Folia-incompatible calls"));
        assertTrue(text.contains("reflection"),
                "what cannot be followed must be spelled out, so a clean report is not read as proof of safety");
    }

    @Test
    @DisplayName("truncates call sites by default and lists them all with verbose")
    void textReportTruncatesLocationsUnlessVerbose() {
        List<Finding> many = List.of(
                finding(SCHEDULER_RULE, "example.A", "m", 1),
                finding(SCHEDULER_RULE, "example.B", "m", 2),
                finding(SCHEDULER_RULE, "example.C", "m", 3),
                finding(SCHEDULER_RULE, "example.D", "m", 4),
                finding(SCHEDULER_RULE, "example.E", "m", 5));
        AnalysisReport report = new AnalysisReport(
                "Many.jar", PluginDescriptor.UNKNOWN, 5, 0, 0, many);

        String concise = TextReport.render(report, false);
        String verbose = TextReport.render(report, true);

        assertTrue(concise.contains("and 2 more"), "output should be truncated by default");
        assertFalse(concise.contains("example.E"));
        assertTrue(verbose.contains("example.E"), "verbose should list every call site");
    }

    @Test
    @DisplayName("JSON output includes the summary and the findings")
    void jsonReportContainsSummaryAndFindings() {
        String json = JsonReport.render(sampleReport());

        assertTrue(json.contains("\"verdict\": \"NOT_READY\""));
        assertTrue(json.contains("\"CRITICAL\": 1"));
        assertTrue(json.contains("\"HIGH\": 1"));
        assertTrue(json.contains("\"api\": \"BukkitScheduler.runTask\""));
        assertTrue(json.contains("\"callKind\": \"DIRECT_CALL\""));
        assertTrue(json.contains("\"line\": 42"));
    }

    @Test
    @DisplayName("escapes JSON special characters correctly")
    void jsonReportEscapesSpecialCharacters() {
        assertEquals("say \\\"hi\\\"", JsonReport.escape("say \"hi\""));
        assertEquals("a\\\\b", JsonReport.escape("a\\b"));
        assertEquals("line1\\nline2", JsonReport.escape("line1\nline2"));
        assertEquals("a\\tb", JsonReport.escape("a\tb"));
        assertEquals("a\\rb", JsonReport.escape("a\rb"));
        assertEquals("\\u0001", JsonReport.escape(String.valueOf((char) 0x01)),
                "control characters must become \\uXXXX escapes");
        assertEquals("café 日本語", JsonReport.escape("café 日本語"),
                "non-ASCII characters must pass through untouched");
    }

    @Test
    @DisplayName("stays well-formed JSON even with hostile input")
    void jsonReportStaysWellFormedWithHostileInput() {
        UnsafeApi hostile = new UnsafeApi(
                "org/evil/\"Owner\"", "me\"thod", null,
                Category.SERVER, Severity.HIGH,
                "a reason with a newline\nand \"quotes\"",
                "a remedy with a backslash \\ inside it");
        AnalysisReport report = new AnalysisReport(
                "hostile\".jar", PluginDescriptor.UNKNOWN, 1, 0, 0,
                List.of(finding(hostile, "evil\"Class", "me\"thod", 1)));

        String json = JsonReport.render(report);

        assertEquals(0, countUnescapedQuotes(json) % 2,
                "unescaped quotes must come in pairs, otherwise the structure is broken");
        assertFalse(json.contains("\n\"quotes\""), "no raw quote should leak through");
    }

    /**
     * Counts the quotes that were not escaped.
     *
     * @param json the text to inspect
     * @return the count
     */
    private static int countUnescapedQuotes(String json) {
        int count = 0;
        for (int i = 0; i < json.length(); i++) {
            if (json.charAt(i) == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                count++;
            }
        }
        return count;
    }

    /**
     * Builds an analysis result for tests.
     *
     * @return the analysis result
     */
    private static AnalysisReport sampleReport() {
        return new AnalysisReport(
                "MyPlugin.jar",
                new PluginDescriptor("MyPlugin", "1.0.0", "example.MyPlugin", null),
                120, 0, 0,
                List.of(
                        finding(SCHEDULER_RULE, "example.MyPlugin", "onEnable", 42),
                        finding(TELEPORT_RULE, "example.Warp", "warp", 88)));
    }

    /**
     * Builds a finding for tests.
     *
     * @param api       the rule
     * @param className the class name
     * @param method    the method name
     * @param line      the line number
     * @return the finding
     */
    private static Finding finding(UnsafeApi api, String className, String method, int line) {
        return new Finding(className, method, "()V", line,
                CallKind.DIRECT_CALL, api.owner(), api);
    }
}
