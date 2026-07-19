package dev.marv.foliacode.transform;

import dev.marv.foliacode.model.UnsafeApi;
import dev.marv.foliacode.rules.TypeHierarchy;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
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
 * Rewrites the call sites in one class, and records why it left the rest alone.
 *
 * <p>The substitution is one instruction: the original {@code invoke} is replaced by
 * an {@code invokestatic} to a shim whose first parameter is the receiver. Nothing
 * is inserted, removed or reordered, so the stack effect is identical and the
 * method's existing stack map frames remain valid.</p>
 *
 * <p>Only sites whose result is discarded are rewritten. The asynchronous
 * replacements cannot produce the value the synchronous call did, and the proof that
 * nobody notices is the {@code pop} sitting immediately after the call. Where that
 * proof is missing the site is refused, not guessed at.</p>
 */
public final class CallSiteRewriter {

    private final UnsafeApiRegistry registry;
    private final TransformRules transformRules;
    private final TypeHierarchy hierarchy;

    /**
     * Builds a rewriter.
     *
     * @param registry       the analyzer's rule set, which decides what counts as a problem
     * @param transformRules the rewrites this tool is prepared to perform
     * @param hierarchy      the type hierarchy, for resolving calls made through subtypes
     */
    public CallSiteRewriter(
            UnsafeApiRegistry registry, TransformRules transformRules, TypeHierarchy hierarchy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transformRules = Objects.requireNonNull(transformRules, "transformRules");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    /**
     * Rewrites a class in place.
     *
     * @param classNode the class to rewrite; modified when a rewrite applies
     * @return what was done and what was refused
     */
    public List<TransformAction> rewrite(ClassNode classNode) {
        Objects.requireNonNull(classNode, "classNode");
        if (classNode.methods == null || classNode.methods.isEmpty()) {
            return List.of();
        }

        String className = classNode.name.replace('/', '.');
        List<TransformAction> actions = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            rewriteMethod(className, method, actions);
        }
        return actions;
    }

    /**
     * Rewrites one method.
     *
     * @param className the declaring class, for the report
     * @param method    the method
     * @param sink      where actions are collected
     */
    private void rewriteMethod(String className, MethodNode method, List<TransformAction> sink) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return;
        }

        int currentLine = -1;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof LineNumberNode lineNode) {
                currentLine = lineNode.line;
                continue;
            }
            if (insn instanceof MethodInsnNode call) {
                handleCall(className, method, call, currentLine, sink);
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                handleMethodReferences(className, method, indy, currentLine, sink);
            }
        }
    }

    /**
     * Decides what to do with one call instruction.
     *
     * @param className   the declaring class
     * @param method      the declaring method
     * @param call        the call instruction
     * @param line        the current line number
     * @param sink        where actions are collected
     */
    private void handleCall(
            String className, MethodNode method, MethodInsnNode call, int line,
            List<TransformAction> sink) {

        Optional<UnsafeApi> match = registry.match(call.owner, call.name, call.desc, hierarchy);
        if (match.isEmpty()) {
            return;
        }
        UnsafeApi api = match.get();

        Optional<TransformRule> rule = findRule(call);
        if (rule.isEmpty()) {
            sink.add(TransformAction.refused(className, method.name, method.desc, line,
                    call.owner, api, RefusalReason.NO_PROVEN_REWRITE));
            return;
        }
        if (!resultIsDiscarded(call)) {
            sink.add(TransformAction.refused(className, method.name, method.desc, line,
                    call.owner, api, RefusalReason.RESULT_IS_USED));
            return;
        }

        TransformRule applied = rule.get();
        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                applied.shimOwner(), applied.shimName(), applied.shimDescriptor(), false));
        sink.add(TransformAction.rewritten(className, method.name, method.desc, line,
                call.owner, api, applied));
    }

    /**
     * Records the unsafe method references, which cannot be rewritten.
     *
     * @param className the declaring class
     * @param method    the declaring method
     * @param indy      the invokedynamic instruction
     * @param line      the current line number
     * @param sink      where actions are collected
     */
    private void handleMethodReferences(
            String className, MethodNode method, InvokeDynamicInsnNode indy, int line,
            List<TransformAction> sink) {
        if (indy.bsmArgs == null) {
            return;
        }
        for (Object arg : indy.bsmArgs) {
            if (!(arg instanceof Handle handle)) {
                continue;
            }
            registry.match(handle.getOwner(), handle.getName(), handle.getDesc(), hierarchy)
                    .ifPresent(api -> sink.add(TransformAction.refused(
                            className, method.name, method.desc, line,
                            handle.getOwner(), api, RefusalReason.METHOD_REFERENCE)));
        }
    }

    /**
     * Finds the rewrite for a call, resolving the owner through the type hierarchy.
     *
     * <p>A call to {@code Player.teleport} has to reach the {@code Entity.teleport}
     * rule, exactly as it does in the analyzer.</p>
     *
     * @param call the call instruction
     * @return the rule, if one applies
     */
    private Optional<TransformRule> findRule(MethodInsnNode call) {
        return transformRules.candidatesFor(call.name, call.desc).stream()
                .filter(rule -> hierarchy.isSubtypeOf(call.owner, rule.owner()))
                .findFirst();
    }

    /**
     * Tests whether the call's result is thrown away.
     *
     * <p>Looks for the {@code pop} that follows. Labels, line numbers and stack map
     * frames carry no instructions and are skipped; anything else means the value is
     * being used.</p>
     *
     * @param call the call instruction
     * @return true if the result is discarded immediately
     */
    private static boolean resultIsDiscarded(MethodInsnNode call) {
        AbstractInsnNode next = call.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next != null && next.getOpcode() == Opcodes.POP;
    }
}
