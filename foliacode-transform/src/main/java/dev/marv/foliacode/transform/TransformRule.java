package dev.marv.foliacode.transform;

import java.util.Objects;

/**
 * One rewrite this tool is prepared to perform.
 *
 * <p>The descriptor is matched exactly, unlike in the analyzer's rule set. Analysis
 * can afford to say "some overload of {@code teleport} is a problem"; a rewrite
 * cannot. Replacing a call means knowing precisely what is on the operand stack, and
 * an overload with different parameters is a different rewrite or none at all.</p>
 *
 * <p>The shim's descriptor is chosen so that the substitution is a single instruction
 * swap: the original receiver becomes the first argument, so the stack the call site
 * has already built is exactly what the replacement needs.</p>
 *
 * @param owner           internal name of the type declaring the original method
 * @param methodName      the original method name
 * @param descriptor      the original descriptor, matched exactly
 * @param shimOwner       internal name of the replacement class
 * @param shimName        name of the replacement method
 * @param shimDescriptor  descriptor of the replacement method
 * @param summary         what the rewrite does, for the report
 * @param caveat          a behaviour change the reader must know about, or {@code null}
 */
public record TransformRule(
        String owner,
        String methodName,
        String descriptor,
        String shimOwner,
        String shimName,
        String shimDescriptor,
        String summary,
        String caveat
) {

    public TransformRule {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(shimOwner, "shimOwner");
        Objects.requireNonNull(shimName, "shimName");
        Objects.requireNonNull(shimDescriptor, "shimDescriptor");
        Objects.requireNonNull(summary, "summary");
    }

    /** Whether this rewrite changes observable behaviour in some way worth stating. */
    public boolean hasCaveat() {
        return caveat != null && !caveat.isBlank();
    }
}
