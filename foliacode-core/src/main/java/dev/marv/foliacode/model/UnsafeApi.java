package dev.marv.foliacode.model;

import java.util.Objects;

/**
 * One API that causes trouble on Folia.
 *
 * <p>Keeping the knowledge in a data type, rather than scattering string
 * literals through the matching logic, makes each rule unit-testable and
 * reduces adding or removing one to a single line in the registry.</p>
 *
 * @param owner       internal name of the declaring class, e.g. {@code org/bukkit/block/Block}
 * @param methodName  method name, or {@code "*"} to match every method
 * @param descriptor  method descriptor, or {@code null} to match any descriptor
 * @param category    classification
 * @param severity    how serious the finding is
 * @param reason      why this breaks on Folia
 * @param remedy      what to do instead
 */
public record UnsafeApi(
        String owner,
        String methodName,
        String descriptor,
        Category category,
        Severity severity,
        String reason,
        String remedy
) {

    /** Wildcard that matches every method on the owner. */
    public static final String ANY_METHOD = "*";

    public UnsafeApi {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(remedy, "remedy");
    }

    /**
     * Tests whether a method name matches this rule.
     *
     * @param name name of the called method
     * @return true on a match
     */
    public boolean matchesName(String name) {
        return ANY_METHOD.equals(methodName) || methodName.equals(name);
    }

    /**
     * Tests whether a method descriptor matches this rule.
     *
     * @param desc descriptor of the called method
     * @return true if this rule ignores descriptors, or the descriptor matches
     */
    public boolean matchesDescriptor(String desc) {
        return descriptor == null || descriptor.equals(desc);
    }

    /** Short name for display, e.g. {@code Block.setType}. */
    public String displayName() {
        return simpleOwnerName() + "." + methodName;
    }

    /** Simple name of the declaring class. */
    public String simpleOwnerName() {
        int index = owner.lastIndexOf('/');
        return index < 0 ? owner : owner.substring(index + 1);
    }
}
