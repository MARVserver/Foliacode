package dev.marv.foliacode.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOptionsTest {

    @Test
    @DisplayName("falls back to defaults when no arguments are given")
    void defaultsWithoutArguments() {
        for (String args : new String[]{null, "", "   "}) {
            AgentOptions options = AgentOptions.parse(args);
            assertEquals(Path.of(AgentOptions.DEFAULT_REPORT), options.reportPath());
            assertEquals(List.of(), options.includePackages());
            assertFalse(options.quiet());
        }
    }

    @Test
    @DisplayName("parses the report path and package filter")
    void parsesReportAndIncludes() {
        AgentOptions options = AgentOptions.parse("report=/tmp/run.json,include=com.example;org.demo");

        assertEquals(Path.of("/tmp/run.json"), options.reportPath());
        assertEquals(List.of("com/example", "org/demo"), options.includePackages(),
                "packages are stored in the internal form the bytecode uses");
    }

    @Test
    @DisplayName("accepts quiet with or without a value")
    void parsesQuiet() {
        assertTrue(AgentOptions.parse("quiet").quiet());
        assertTrue(AgentOptions.parse("quiet=true").quiet());
        assertFalse(AgentOptions.parse("quiet=false").quiet());
    }

    @Test
    @DisplayName("ignores unknown keys rather than refusing to start")
    void ignoresUnknownKeys() {
        AgentOptions options = AgentOptions.parse("nonsense=1,report=out.json,alsoNonsense");

        assertEquals(Path.of("out.json"), options.reportPath());
    }
}
