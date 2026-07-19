package dev.marv.foliacode.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link BootLogAnalyzer} classifies real server output correctly.
 *
 * <p>The log excerpts are shaped like genuine Paper and Folia output, including the
 * timestamp and level prefixes, because the analyzer has to work on the real thing.</p>
 */
class BootLogAnalyzerTest {

    private static final String PLUGIN = "MyPlugin";
    private static final String MAIN_CLASS = "com.example.myplugin.MyPlugin";

    private static BootLogAnalyzer analyzer() {
        return new BootLogAnalyzer(PLUGIN, MAIN_CLASS);
    }

    private static ServerVerificationResult analyze(List<String> lines) {
        return analyzer().analyze(lines, Duration.ofSeconds(12), false);
    }

    private static ServerVerificationResult analyzeTimedOut(List<String> lines) {
        return analyzer().analyze(lines, Duration.ofSeconds(180), true);
    }

    @Test
    @DisplayName("A completed boot with the plugin enabled is a success")
    void classifiesSuccess() {
        ServerVerificationResult result = analyze(List.of(
                "[12:00:01 INFO]: Starting minecraft server version 1.21.4",
                "[12:00:02 INFO]: [MyPlugin] Loading server plugin MyPlugin v1.0.0",
                "[12:00:09 INFO]: [MyPlugin] Enabling MyPlugin v1.0.0",
                "[12:00:12 INFO]: Done (11.482s)! For help, type \"help\""));

        assertEquals(VerificationOutcome.SUCCESS, result.outcome());
        assertTrue(result.isSuccess());
        assertTrue(result.missingDependencies().isEmpty());
        assertEquals(Duration.ofSeconds(12), result.bootDuration());
    }

    @Test
    @DisplayName("A completed boot without the plugin enabled reports PLUGIN_NOT_LOADED")
    void classifiesPluginNotLoaded() {
        ServerVerificationResult result = analyze(List.of(
                "[12:00:01 INFO]: Starting minecraft server version 1.21.4",
                "[12:00:12 INFO]: Done (11.482s)! For help, type \"help\""));

        assertEquals(VerificationOutcome.PLUGIN_NOT_LOADED, result.outcome());
        assertTrue(result.outcome().isAttributableToPlugin());
        assertTrue(hasIssue(result, VerificationOutcome.PLUGIN_NOT_LOADED));
    }

    @Nested
    @DisplayName("Missing dependencies")
    class MissingDependencies {

        @Test
        @DisplayName("Extracts every plugin name from the bracketed dependency list")
        void extractsMultipleNames() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:04 ERROR]: Could not load 'plugins/MyPlugin.jar' in folder 'plugins'",
                    "org.bukkit.plugin.UnknownDependencyException: Unknown/missing dependency plugins: "
                            + "[Vault, PlaceholderAPI]. Please download and install these plugins to run "
                            + "'MyPlugin'.",
                    "[12:00:12 INFO]: Done (9.104s)! For help, type \"help\""));

            assertEquals(VerificationOutcome.MISSING_DEPENDENCY, result.outcome());
            assertEquals(List.of("Vault", "PlaceholderAPI"), result.missingDependencies());
            assertTrue(result.hasMissingDependencies());
        }

        @Test
        @DisplayName("Extracts a single dependency name")
        void extractsSingleName() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:04 ERROR]: [Paper] Could not load 'plugins/MyPlugin.jar' in folder "
                            + "'plugins': Unknown/missing dependency plugins: [Vault]"));

            assertEquals(VerificationOutcome.MISSING_DEPENDENCY, result.outcome());
            assertEquals(List.of("Vault"), result.missingDependencies());
        }

        @Test
        @DisplayName("Extracts a quoted dependency name from the newer plugin loader")
        void extractsQuotedName() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:04 ERROR]: [PluginInitializerManager] Could not load plugin 'MyPlugin': "
                            + "missing dependency 'LuckPerms'"));

            assertEquals(VerificationOutcome.MISSING_DEPENDENCY, result.outcome());
            assertEquals(List.of("LuckPerms"), result.missingDependencies());
        }

        @Test
        @DisplayName("Reports a dependency problem even when no name can be parsed")
        void detectsUnnamedDependencyFailure() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:04 ERROR]: Plugin MyPlugin is missing a dependency and will not be loaded"));

            assertEquals(VerificationOutcome.MISSING_DEPENDENCY, result.outcome());
            assertTrue(result.missingDependencies().isEmpty());
            assertTrue(hasIssue(result, VerificationOutcome.MISSING_DEPENDENCY));
        }

        @Test
        @DisplayName("The missing dependency issue quotes the log line it came from")
        void carriesEvidence() {
            String line = "org.bukkit.plugin.UnknownDependencyException: Unknown/missing dependency "
                    + "plugins: [Vault]. Please download and install these plugins to run 'MyPlugin'.";
            ServerVerificationResult result = analyze(List.of(line));

            VerificationIssue issue = issue(result, VerificationOutcome.MISSING_DEPENDENCY);
            assertTrue(issue.hasEvidence());
            assertTrue(issue.evidence().contains(line));
            assertTrue(issue.message().contains("Vault"),
                    "The message should name the missing plugin so the user can act on it");
        }
    }

    @Test
    @DisplayName("A plugin rejected by Folia reports FOLIA_UNSUPPORTED")
    void classifiesFoliaUnsupported() {
        String line = "[12:00:04 ERROR]: Could not load 'plugins/MyPlugin.jar' in folder 'plugins': "
                + "\"MyPlugin\" is not marked as supporting Folia! Please see "
                + "https://docs.papermc.io/folia/reference/overview for more information.";
        ServerVerificationResult result = analyze(List.of(line));

        assertEquals(VerificationOutcome.FOLIA_UNSUPPORTED, result.outcome());
        assertTrue(issue(result, VerificationOutcome.FOLIA_UNSUPPORTED).evidence().contains(line));
    }

    @Nested
    @DisplayName("Class loading failures")
    class ClassErrors {

        @Test
        @DisplayName("Detects NoClassDefFoundError")
        void detectsNoClassDefFound() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:09 ERROR]: Error occurred while enabling MyPlugin v1.0.0",
                    "java.lang.NoClassDefFoundError: org/bukkit/craftbukkit/CraftServer",
                    "\tat com.example.myplugin.MyPlugin.onEnable(MyPlugin.java:31)"));

            assertEquals(VerificationOutcome.CLASS_ERROR, result.outcome());
        }

        @Test
        @DisplayName("Detects ClassNotFoundException")
        void detectsClassNotFound() {
            ServerVerificationResult result = analyze(List.of(
                    "java.lang.ClassNotFoundException: net.milkbowl.vault.economy.Economy"));

            assertEquals(VerificationOutcome.CLASS_ERROR, result.outcome());
        }

        @Test
        @DisplayName("Detects UnsupportedClassVersionError from a newer compiler target")
        void detectsUnsupportedClassVersion() {
            ServerVerificationResult result = analyze(List.of(
                    "java.lang.UnsupportedClassVersionError: com/example/myplugin/MyPlugin has been "
                            + "compiled by a more recent version of the Java Runtime (class file version "
                            + "65.0), this version of the Java Runtime only recognizes class file "
                            + "versions up to 61.0"));

            assertEquals(VerificationOutcome.CLASS_ERROR, result.outcome());
        }

        @Test
        @DisplayName("Detects IncompatibleClassChangeError")
        void detectsIncompatibleClassChange() {
            ServerVerificationResult result = analyze(List.of(
                    "java.lang.IncompatibleClassChangeError: Found interface org.bukkit.entity.Player, "
                            + "but class was expected"));

            assertEquals(VerificationOutcome.CLASS_ERROR, result.outcome());
        }

        @Test
        @DisplayName("A class error in an unrelated plugin does not fail a clean boot")
        void ignoresUnrelatedClassErrorAfterCleanBoot() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:08 INFO]: [SomeOtherPlugin] Enabling SomeOtherPlugin v2.0",
                    "[12:00:08 ERROR]: [SomeOtherPlugin] java.lang.NoClassDefFoundError: "
                            + "org/other/Thing",
                    "\tat org.other.SomeOtherPlugin.onEnable(SomeOtherPlugin.java:12)",
                    "[12:00:09 INFO]: [MyPlugin] Enabling MyPlugin v1.0.0",
                    "[12:00:12 INFO]: Done (11.482s)! For help, type \"help\""));

            assertEquals(VerificationOutcome.SUCCESS, result.outcome(),
                    "Another plugin's failure must not be blamed on the plugin under test");
        }
    }

    @Nested
    @DisplayName("Runtime exceptions")
    class RuntimeExceptions {

        @Test
        @DisplayName("Detects UnsupportedOperationException thrown by Folia")
        void detectsUnsupportedOperation() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:09 ERROR]: Error occurred while enabling MyPlugin v1.0.0",
                    "java.lang.UnsupportedOperationException: Cannot access the Bukkit scheduler "
                            + "from a region thread",
                    "\tat com.example.myplugin.MyPlugin.onEnable(MyPlugin.java:44)"));

            assertEquals(VerificationOutcome.RUNTIME_EXCEPTION, result.outcome());
        }

        @Test
        @DisplayName("Attributes a stack trace in the plugin's own package to the plugin")
        void detectsPluginStackFrame() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:09 ERROR]: Error occurred while enabling MyPlugin v1.0.0",
                    "java.lang.IllegalStateException: not on the main thread",
                    "\tat com.example.myplugin.tasks.Ticker.run(Ticker.java:18)"));

            assertEquals(VerificationOutcome.RUNTIME_EXCEPTION, result.outcome());
            assertTrue(issue(result, VerificationOutcome.RUNTIME_EXCEPTION).hasEvidence());
        }

        @Test
        @DisplayName("Ignores stack frames belonging to other code")
        void ignoresForeignStackFrame() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:09 INFO]: [MyPlugin] Enabling MyPlugin v1.0.0",
                    "\tat org.somethingelse.Other.run(Other.java:9)",
                    "[12:00:12 INFO]: Done (11.482s)! For help, type \"help\""));

            assertEquals(VerificationOutcome.SUCCESS, result.outcome());
        }
    }

    @Nested
    @DisplayName("Memory failures")
    class MemoryFailures {

        @Test
        @DisplayName("A JVM that cannot reserve the heap is OUT_OF_MEMORY, not a plugin failure")
        void classifiesLowHeapVmFailure() {
            // Exactly what the JVM prints when asked to run a Folia server in 128 MB.
            ServerVerificationResult result = new BootLogAnalyzer(PLUGIN, MAIN_CLASS, 128)
                    .analyze(List.of(
                            "Error occurred during initialization of VM",
                            "Could not reserve enough space for 131072KB object heap"),
                            Duration.ofSeconds(1), false);

            assertEquals(VerificationOutcome.OUT_OF_MEMORY, result.outcome());
            assertFalse(result.isAttributableToPlugin(),
                    "A server that could not start says nothing about the plugin");
        }

        @Test
        @DisplayName("The out of memory message names the configured heap and tells the user to raise it")
        void givesActionableAdvice() {
            ServerVerificationResult result = new BootLogAnalyzer(PLUGIN, MAIN_CLASS, 128)
                    .analyze(List.of(
                            "Error occurred during initialization of VM",
                            "Could not reserve enough space for 131072KB object heap"),
                            Duration.ofSeconds(1), false);

            VerificationIssue issue = issue(result, VerificationOutcome.OUT_OF_MEMORY);
            assertTrue(issue.message().contains("128"), "Should state the heap that was configured");
            assertTrue(issue.message().contains(String.valueOf(BootLogAnalyzer.MINIMUM_VIABLE_HEAP_MB)),
                    "Should state the heap the user needs instead");
            assertTrue(issue.message().contains("not a defect in the plugin"),
                    "Must say plainly that the plugin is not at fault");
            assertEquals(IssueSeverity.FATAL, issue.severity());
        }

        @Test
        @DisplayName("Detects an OutOfMemoryError raised during the boot")
        void detectsHeapExhaustion() {
            ServerVerificationResult result = analyze(List.of(
                    "[12:00:30 ERROR]: java.lang.OutOfMemoryError: Java heap space"));

            assertEquals(VerificationOutcome.OUT_OF_MEMORY, result.outcome());
        }

        @Test
        @DisplayName("Detects an insufficient memory failure")
        void detectsInsufficientMemory() {
            ServerVerificationResult result = analyze(List.of(
                    "There is insufficient memory for the Java Runtime Environment to continue.",
                    "Native memory allocation (mmap) failed to map 1073741824 bytes"));

            assertEquals(VerificationOutcome.OUT_OF_MEMORY, result.outcome());
        }

        @Test
        @DisplayName("Memory failures outrank plugin failures so the plugin is never blamed")
        void memoryOutranksEverythingElse() {
            ServerVerificationResult result = new BootLogAnalyzer(PLUGIN, MAIN_CLASS, 128).analyze(List.of(
                    "[12:00:04 ERROR]: Unknown/missing dependency plugins: [Vault]",
                    "java.lang.NoClassDefFoundError: com/example/myplugin/Thing",
                    "\tat com.example.myplugin.MyPlugin.onEnable(MyPlugin.java:31)",
                    "java.lang.OutOfMemoryError: Java heap space"),
                    Duration.ofSeconds(30), false);

            assertEquals(VerificationOutcome.OUT_OF_MEMORY, result.outcome(),
                    "An out of memory failure must win, otherwise we tell users to fix working code");
        }
    }

    @Test
    @DisplayName("A boot that never concludes reports TIMEOUT")
    void classifiesTimeout() {
        ServerVerificationResult result = analyzeTimedOut(List.of(
                "[12:00:01 INFO]: Starting minecraft server version 1.21.4",
                "[12:00:04 INFO]: Preparing level \"world\""));

        assertEquals(VerificationOutcome.TIMEOUT, result.outcome());
        assertFalse(result.isAttributableToPlugin());
        assertTrue(hasIssue(result, VerificationOutcome.TIMEOUT));
    }

    @Test
    @DisplayName("A definite failure outranks the timeout that followed it")
    void definiteFailureOutranksTimeout() {
        ServerVerificationResult result = analyzeTimedOut(List.of(
                "[12:00:04 ERROR]: Unknown/missing dependency plugins: [Vault]"));

        assertEquals(VerificationOutcome.MISSING_DEPENDENCY, result.outcome(),
                "The known cause is more useful than reporting that we stopped waiting");
    }

    @Test
    @DisplayName("A process that dies without a known cause reports SERVER_FAILED")
    void classifiesServerFailed() {
        ServerVerificationResult result = analyze(List.of(
                "[12:00:01 INFO]: Starting minecraft server version 1.21.4",
                "[12:00:02 ERROR]: Failed to bind to port"));

        assertEquals(VerificationOutcome.SERVER_FAILED, result.outcome());
        assertFalse(result.isAttributableToPlugin());
    }

    @Test
    @DisplayName("An empty log reports SERVER_FAILED rather than success")
    void handlesEmptyLog() {
        ServerVerificationResult result = analyze(List.of());
        assertEquals(VerificationOutcome.SERVER_FAILED, result.outcome());
    }

    @Test
    @DisplayName("Handles null and missing inputs without failing")
    void handlesNullSafely() {
        ServerVerificationResult result = new BootLogAnalyzer(null, null).analyze(null, null, false);

        assertEquals(VerificationOutcome.SERVER_FAILED, result.outcome());
        assertEquals(Duration.ZERO, result.bootDuration());
        assertTrue(result.logTail().isEmpty());
    }

    @Test
    @DisplayName("The whole log is retained as the tail so the report can show it")
    void retainsLogTail() {
        List<String> lines = List.of(
                "[12:00:09 INFO]: [MyPlugin] Enabling MyPlugin v1.0.0",
                "[12:00:12 INFO]: Done (11.482s)! For help, type \"help\"");

        assertEquals(lines, analyze(lines).logTail());
    }

    @Nested
    @DisplayName("Early termination")
    class Decisiveness {

        @Test
        @DisplayName("Recognises the lines that settle the outcome")
        void recognisesDecisiveLines() {
            BootLogAnalyzer analyzer = analyzer();

            assertTrue(analyzer.isDecisive("[12:00:12 INFO]: Done (11.482s)! For help, type \"help\""));
            assertTrue(analyzer.isDecisive("Could not reserve enough space for 131072KB object heap"));
            assertTrue(analyzer.isDecisive("Unknown/missing dependency plugins: [Vault]"));
            assertTrue(analyzer.isDecisive("\"MyPlugin\" is not marked as supporting Folia!"));
        }

        @Test
        @DisplayName("Ordinary progress lines are not decisive")
        void ordinaryLinesAreNotDecisive() {
            BootLogAnalyzer analyzer = analyzer();

            assertFalse(analyzer.isDecisive("[12:00:04 INFO]: Preparing level \"world\""));
            assertFalse(analyzer.isDecisive("[12:00:09 INFO]: [MyPlugin] Enabling MyPlugin v1.0.0"));
            assertFalse(analyzer.isDecisive(null));
        }
    }

    private static boolean hasIssue(ServerVerificationResult result, VerificationOutcome type) {
        return result.issues().stream().anyMatch(issue -> issue.type() == type);
    }

    private static VerificationIssue issue(ServerVerificationResult result, VerificationOutcome type) {
        return result.issues().stream()
                .filter(candidate -> candidate.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " issue was reported. Issues: "
                        + result.issues()));
    }
}
