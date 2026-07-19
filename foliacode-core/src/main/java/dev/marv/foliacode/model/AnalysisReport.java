package dev.marv.foliacode.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of analysing one JAR.
 *
 * <p>Aggregation happens here, once. The output layers (text and JSON) only
 * read from this result, so adding a new output format never duplicates the
 * counting logic.</p>
 */
public final class AnalysisReport {

    private final String jarName;
    private final PluginDescriptor plugin;
    private final int classesScanned;
    private final int classesFailed;
    private final int nestedJarsScanned;
    private final List<Finding> findings;
    private final Map<Severity, Integer> severityCounts;
    private final Map<Category, Integer> categoryCounts;

    public AnalysisReport(
            String jarName,
            PluginDescriptor plugin,
            int classesScanned,
            int classesFailed,
            int nestedJarsScanned,
            List<Finding> findings
    ) {
        this.jarName = Objects.requireNonNull(jarName, "jarName");
        this.plugin = plugin == null ? PluginDescriptor.UNKNOWN : plugin;
        this.classesScanned = classesScanned;
        this.classesFailed = classesFailed;
        this.nestedJarsScanned = nestedJarsScanned;
        this.findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        this.severityCounts = countBySeverity(this.findings);
        this.categoryCounts = countByCategory(this.findings);
    }

    private static Map<Severity, Integer> countBySeverity(List<Finding> findings) {
        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            counts.put(severity, 0);
        }
        for (Finding finding : findings) {
            counts.merge(finding.severity(), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<Category, Integer> countByCategory(List<Finding> findings) {
        Map<Category, Integer> counts = new EnumMap<>(Category.class);
        for (Finding finding : findings) {
            counts.merge(finding.category(), 1, Integer::sum);
        }
        return counts;
    }

    public String jarName() {
        return jarName;
    }

    public PluginDescriptor plugin() {
        return plugin;
    }

    public int classesScanned() {
        return classesScanned;
    }

    public int classesFailed() {
        return classesFailed;
    }

    public int nestedJarsScanned() {
        return nestedJarsScanned;
    }

    public List<Finding> findings() {
        return findings;
    }

    /**
     * Number of findings at a given severity.
     *
     * @param severity the severity to count
     * @return the count
     */
    public int count(Severity severity) {
        return severityCounts.getOrDefault(severity, 0);
    }

    /** Counts per severity; every severity is present as a key. */
    public Map<Severity, Integer> severityCounts() {
        return severityCounts;
    }

    /** Counts per category; only categories with findings are present. */
    public Map<Category, Integer> categoryCounts() {
        return categoryCounts;
    }

    /**
     * Whether any finding is at least as serious as a threshold.
     *
     * @param threshold the threshold
     * @return true if at least one finding meets it
     */
    public boolean hasFindingsAtLeast(Severity threshold) {
        return findings.stream().anyMatch(f -> f.severity().isAtLeast(threshold));
    }

    /** The overall verdict. */
    public Verdict verdict() {
        if (count(Severity.CRITICAL) > 0) {
            return Verdict.NOT_READY;
        }
        if (count(Severity.HIGH) > 0 || count(Severity.MEDIUM) > 0) {
            return Verdict.NEEDS_REVIEW;
        }
        return Verdict.READY;
    }

    /**
     * Findings ordered by severity, then by call site.
     *
     * @return the sorted list
     */
    public List<Finding> sortedFindings() {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator
                .comparingInt((Finding f) -> f.severity().ordinal())
                .thenComparing(Finding::className)
                .thenComparing(Finding::methodName)
                .thenComparingInt(Finding::lineNumber));
        return sorted;
    }

    /**
     * Findings grouped by severity.
     *
     * <p>Severities with no findings are omitted.</p>
     *
     * @return a {@code Map} in severity order
     */
    public Map<Severity, List<Finding>> groupedBySeverity() {
        Map<Severity, List<Finding>> grouped = new LinkedHashMap<>();
        for (Finding finding : sortedFindings()) {
            grouped.computeIfAbsent(finding.severity(), key -> new ArrayList<>()).add(finding);
        }
        return grouped;
    }
}
