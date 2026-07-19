package dev.marv.foliacode.transform;

import dev.marv.foliacode.model.AnalysisReport;
import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.Severity;
import dev.marv.foliacode.model.Verdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of transforming one JAR.
 *
 * <p>Holds both analyses — the JAR as it arrived and as it left — because the only
 * honest way to describe a rewrite is to show what it changed. The tool re-reads its
 * own output rather than assuming the rewrite worked.</p>
 */
public final class TransformReport {

    private final String sourceName;
    private final String outputName;
    private final int classesScanned;
    private final int classesRewritten;
    private final int nestedJarsUntouched;
    private final List<TransformAction> actions;
    private final AnalysisReport before;
    private final AnalysisReport after;
    private final boolean foliaSupportedDeclared;

    /**
     * Builds a report.
     *
     * @param sourceName             name of the JAR that was read
     * @param outputName             name of the JAR that was written, or {@code null} for a dry run
     * @param classesScanned         how many classes were examined
     * @param classesRewritten       how many classes were changed
     * @param nestedJarsUntouched    how many shaded JARs were left alone
     * @param actions                what happened at each call site
     * @param before                 analysis of the original JAR
     * @param after                  analysis of the written JAR, or {@code null} for a dry run
     * @param foliaSupportedDeclared whether {@code folia-supported: true} was added
     */
    public TransformReport(
            String sourceName,
            String outputName,
            int classesScanned,
            int classesRewritten,
            int nestedJarsUntouched,
            List<TransformAction> actions,
            AnalysisReport before,
            AnalysisReport after,
            boolean foliaSupportedDeclared
    ) {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.outputName = outputName;
        this.classesScanned = classesScanned;
        this.classesRewritten = classesRewritten;
        this.nestedJarsUntouched = nestedJarsUntouched;
        this.actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        this.before = Objects.requireNonNull(before, "before");
        this.after = after;
        this.foliaSupportedDeclared = foliaSupportedDeclared;
    }

    public String sourceName() {
        return sourceName;
    }

    /** Name of the JAR written, or {@code null} if this was a dry run. */
    public String outputName() {
        return outputName;
    }

    /** Whether anything was written to disk. */
    public boolean isDryRun() {
        return outputName == null;
    }

    public int classesScanned() {
        return classesScanned;
    }

    public int classesRewritten() {
        return classesRewritten;
    }

    public int nestedJarsUntouched() {
        return nestedJarsUntouched;
    }

    public List<TransformAction> actions() {
        return actions;
    }

    public AnalysisReport before() {
        return before;
    }

    /** Analysis of the written JAR, or {@code null} for a dry run. */
    public AnalysisReport after() {
        return after;
    }

    /** Whether {@code folia-supported: true} was written into {@code plugin.yml}. */
    public boolean foliaSupportedDeclared() {
        return foliaSupportedDeclared;
    }

    /** The call sites that were rewritten. */
    public List<TransformAction> rewritten() {
        return actions.stream().filter(TransformAction::isRewritten).toList();
    }

    /** The call sites that were left alone, worst severity first. */
    public List<TransformAction> refused() {
        return actions.stream()
                .filter(action -> !action.isRewritten())
                .sorted(Comparator
                        .comparingInt((TransformAction a) -> a.severity().ordinal())
                        .thenComparing(TransformAction::className)
                        .thenComparingInt(TransformAction::lineNumber))
                .toList();
    }

    /** Whether every rewrite this tool knows how to make left nothing behind. */
    public boolean isFullyTransformed() {
        return refused().stream().noneMatch(a -> a.severity().isAtLeast(Severity.HIGH));
    }

    /**
     * Refusals grouped by the API they concern.
     *
     * <p>One entry per rule, since a plugin that calls {@code Block.setType} from
     * forty places has one problem, not forty.</p>
     *
     * @return a map from API display name to the refusals concerning it
     */
    public Map<String, List<TransformAction>> refusalsByApi() {
        Map<String, List<TransformAction>> grouped = new LinkedHashMap<>();
        for (TransformAction action : refused()) {
            grouped.computeIfAbsent(action.api().displayName(), key -> new ArrayList<>()).add(action);
        }
        return grouped;
    }

    /**
     * Rewrites that change observable behaviour, one entry per rule.
     *
     * @return the distinct caveats, in the order they were first hit
     */
    public Map<String, TransformRule> caveats() {
        Map<String, TransformRule> caveats = new LinkedHashMap<>();
        for (TransformAction action : rewritten()) {
            if (action.hasCaveat()) {
                caveats.putIfAbsent(action.rule().shimName(), action.rule());
            }
        }
        return caveats;
    }

    /**
     * Findings still present in the output, excluding the shim FoliaCode added.
     *
     * <p>The shim reaches the server through reflection, which the analyzer reports
     * as {@code INFO} — correctly, since it cannot see through it. Counting FoliaCode's
     * own scaffolding as unresolved plugin findings would misrepresent the result.</p>
     *
     * @return the remaining findings, or an empty list for a dry run
     */
    public List<Finding> remainingFindings() {
        if (after == null) {
            return List.of();
        }
        String shimPackage = TransformRules.SHIM_PACKAGE.replace('/', '.');
        return after.sortedFindings().stream()
                .filter(finding -> !finding.className().startsWith(shimPackage))
                .toList();
    }

    /**
     * The verdict for the output, ignoring the shim.
     *
     * @return the verdict, or the original one for a dry run
     */
    public Verdict resultingVerdict() {
        if (after == null) {
            return before.verdict();
        }
        List<Finding> remaining = remainingFindings();
        if (remaining.stream().anyMatch(f -> f.severity() == Severity.CRITICAL)) {
            return Verdict.NOT_READY;
        }
        if (remaining.stream().anyMatch(f -> f.severity() == Severity.HIGH
                || f.severity() == Severity.MEDIUM)) {
            return Verdict.NEEDS_REVIEW;
        }
        return Verdict.READY;
    }
}
