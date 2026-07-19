package dev.marv.foliacode.verify;

import java.util.List;
import java.util.Objects;

/**
 * A single problem detected in the server boot log.
 *
 * <p>Every issue carries the log lines that produced it. A verification tool that reports
 * a conclusion without the evidence for it cannot be checked by the user, so the evidence
 * is part of the model rather than something the reporter reconstructs.</p>
 *
 * @param type     the kind of problem, expressed with the outcome vocabulary
 * @param severity how the problem affects the verification verdict
 * @param message  an actionable description of the problem
 * @param evidence the log lines the detection was based on
 */
public record VerificationIssue(
        VerificationOutcome type,
        IssueSeverity severity,
        String message,
        List<String> evidence
) {

    /** Maximum number of evidence lines retained per issue, to keep reports readable. */
    public static final int MAX_EVIDENCE_LINES = 5;

    public VerificationIssue {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        evidence = evidence == null ? List.of() : truncate(evidence);
    }

    private static List<String> truncate(List<String> lines) {
        List<String> copy = List.copyOf(lines);
        return copy.size() <= MAX_EVIDENCE_LINES ? copy : List.copyOf(copy.subList(0, MAX_EVIDENCE_LINES));
    }

    /** Whether any evidence was captured. */
    public boolean hasEvidence() {
        return !evidence.isEmpty();
    }
}
