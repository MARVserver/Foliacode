package dev.marv.foliacode.analysis;

import dev.marv.foliacode.model.CallKind;
import dev.marv.foliacode.model.Finding;
import dev.marv.foliacode.model.UnsafeApi;
import dev.marv.foliacode.rules.TypeHierarchy;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scans a single class for Folia-incompatible calls.
 *
 * <p>Two things set this apart from pasta:
 * <ul>
 *   <li>{@link InvokeDynamicInsnNode} bootstrap arguments are walked too, so
 *       method references like {@code Block::setType} are not missed</li>
 *   <li>owners are matched through the type hierarchy, so calls made through
 *       a subtype such as {@code Player#teleport} are still detected</li>
 * </ul>
 * </p>
 */
public final class ClassScanner {

    private final UnsafeApiRegistry registry;
    private final TypeHierarchy hierarchy;

    /**
     * Builds a scanner.
     *
     * @param registry  the rule registry
     * @param hierarchy the type hierarchy
     */
    public ClassScanner(UnsafeApiRegistry registry, TypeHierarchy hierarchy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    /**
     * Scans a class node.
     *
     * @param classNode the class to scan
     * @return the findings, or an empty list if there are none
     */
    public List<Finding> scan(ClassNode classNode) {
        Objects.requireNonNull(classNode, "classNode");
        if (classNode.methods == null || classNode.methods.isEmpty()) {
            return List.of();
        }

        String className = toDisplayName(classNode.name);
        List<Finding> findings = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            scanMethod(classNode, className, method, findings);
        }
        return findings;
    }

    /**
     * Scans a single method.
     *
     * @param classNode the declaring class
     * @param className the class name for display
     * @param method    the method to scan
     * @param sink      where findings are collected
     */
    private void scanMethod(ClassNode classNode, String className, MethodNode method, List<Finding> sink) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return;
        }

        int currentLine = -1;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof LineNumberNode lineNode) {
                currentLine = lineNode.line;
                continue;
            }
            // Pin the current line to an effectively final local so the lambda can capture it
            final int line = currentLine;
            if (insn instanceof MethodInsnNode call) {
                match(call.owner, call.name, call.desc).ifPresent(api ->
                        sink.add(new Finding(className, method.name, method.desc,
                                line, CallKind.DIRECT_CALL, call.owner, api)));
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                scanInvokeDynamic(className, method, indy, line, sink);
            }
        }
    }

    /**
     * Scans the method handles carried in an invokedynamic bootstrap argument.
     *
     * <p>A method reference such as {@code list.forEach(Block::setType)} does
     * not appear as a call instruction; it appears as a {@link Handle} in the
     * bootstrap arguments. Skipping this means missing a great deal in any
     * modern plugin that leans on lambdas.</p>
     *
     * @param className   the class name for display
     * @param method      the enclosing method
     * @param indy        the invokedynamic instruction
     * @param currentLine the current line number
     * @param sink        where findings are collected
     */
    private void scanInvokeDynamic(
            String className,
            MethodNode method,
            InvokeDynamicInsnNode indy,
            int currentLine,
            List<Finding> sink
    ) {
        if (indy.bsmArgs == null) {
            return;
        }
        for (Object arg : indy.bsmArgs) {
            if (!(arg instanceof Handle handle)) {
                continue;
            }
            match(handle.getOwner(), handle.getName(), handle.getDesc()).ifPresent(api ->
                    sink.add(new Finding(className, method.name, method.desc,
                            currentLine, CallKind.METHOD_REFERENCE, handle.getOwner(), api)));
        }
    }

    /**
     * Matches a call against the rules.
     *
     * @param owner internal name of the declaring class
     * @param name  method name
     * @param desc  method descriptor
     * @return the matching rule
     */
    private Optional<UnsafeApi> match(String owner, String name, String desc) {
        return registry.match(owner, name, desc, hierarchy);
    }

    /**
     * Converts an internal name to a dot-separated name for display.
     *
     * @param internalName the internal name
     * @return the dot-separated class name
     */
    private static String toDisplayName(String internalName) {
        return internalName == null ? "(unknown)" : internalName.replace('/', '.');
    }
}
