package dev.marv.foliacode.agent;

import dev.marv.foliacode.model.UnsafeApi;
import dev.marv.foliacode.rules.TypeHierarchy;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Weaves a counter into every call site the rule set flags.
 *
 * <p>The injected sequence is two instructions placed immediately before the call:
 * push the site id, then {@code invokestatic RuntimeRecorder.observe(int)}. It is
 * stack-neutral, introduces no locals and no branches, so the method's existing
 * stack map frames stay valid and only {@code maxStack} has to grow.</p>
 *
 * <p>That last point is why the writer uses {@link ClassWriter#COMPUTE_MAXS} and not
 * {@code COMPUTE_FRAMES}. Computing frames makes ASM resolve common supertypes,
 * which loads classes — inside a {@link ClassFileTransformer}, during class loading,
 * that is a well-known route to deadlock or to a {@code ClassCircularityError}. The
 * transformation is deliberately shaped so the expensive option is never needed.</p>
 *
 * <p>Method references are <em>not</em> instrumented. The call in
 * {@code list.forEach(Block::setType)} executes inside a lambda class the JVM
 * generates at runtime, not at the site where the handle is written. Rather than
 * pretend otherwise, those sites are counted separately and reported as visible to
 * static analysis only.</p>
 */
final class InstrumentingTransformer implements ClassFileTransformer {

    private static final String RECORDER = "dev/marv/foliacode/agent/RuntimeRecorder";

    /**
     * Packages that are never instrumented.
     *
     * <p>The JDK and the server are out of scope: this agent reports on a plugin.
     * Its own classes are excluded because instrumenting the recorder from inside
     * the recorder is an infinite regress.</p>
     */
    private static final List<String> NEVER_INSTRUMENT = List.of(
            "java/", "javax/", "jdk/", "sun/", "com/sun/",
            "dev/marv/foliacode/", "org/objectweb/asm/",
            "org/bukkit/", "io/papermc/", "net/minecraft/", "com/mojang/",
            "org/spigotmc/", "co/aikar/", "net/kyori/");

    private final UnsafeApiRegistry registry;

    /**
     * Not thread-safe, and the JVM transforms classes on many threads at once, so
     * every access is guarded by this object's monitor.
     */
    private final TypeHierarchy hierarchy;

    private final List<String> includedPackages;

    private final AtomicInteger instrumentedClasses = new AtomicInteger();

    private final AtomicInteger methodReferenceSites = new AtomicInteger();

    /**
     * Builds a transformer.
     *
     * @param registry         the rule registry
     * @param includedPackages internal-name prefixes to instrument; empty means
     *                         everything not covered by {@link #NEVER_INSTRUMENT}
     */
    InstrumentingTransformer(UnsafeApiRegistry registry, List<String> includedPackages) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.includedPackages = List.copyOf(Objects.requireNonNull(includedPackages, "includedPackages"));
        this.hierarchy = new TypeHierarchy();
    }

    /** How many classes were rewritten. */
    int instrumentedClassCount() {
        return instrumentedClasses.get();
    }

    /** How many method-reference sites were seen but could not be instrumented. */
    int methodReferenceSiteCount() {
        return methodReferenceSites.get();
    }

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) {
        try {
            if (!shouldInstrument(loader, className)) {
                return null;
            }
            return instrument(classfileBuffer);
        } catch (Throwable t) {
            // Returning null leaves the class exactly as it was. A plugin that loads
            // without its counters is a lesser failure than a plugin that will not load.
            return null;
        }
    }

    /**
     * Decides whether a class is in scope.
     *
     * @param loader    the defining class loader; {@code null} means bootstrap
     * @param className internal name of the class
     * @return true if the class should be instrumented
     */
    private boolean shouldInstrument(ClassLoader loader, String className) {
        if (className == null || loader == null) {
            // A null loader is the bootstrap loader, which only holds core JDK classes.
            return false;
        }
        for (String excluded : NEVER_INSTRUMENT) {
            if (className.startsWith(excluded)) {
                return false;
            }
        }
        if (includedPackages.isEmpty()) {
            return true;
        }
        for (String included : includedPackages) {
            if (className.startsWith(included)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rewrites a class, if it contains anything worth counting.
     *
     * @param classfileBuffer the original class bytes
     * @return the rewritten bytes, or {@code null} to leave the class untouched
     */
    private byte[] instrument(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        synchronized (hierarchy) {
            hierarchy.learn(reader.getClassName(), reader.getSuperName(), reader.getInterfaces());
        }

        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (node.methods == null || node.methods.isEmpty()) {
            return null;
        }

        String displayName = node.name.replace('/', '.');
        int woven = 0;
        for (MethodNode method : node.methods) {
            woven += instrumentMethod(displayName, method);
        }
        if (woven == 0) {
            return null;
        }

        instrumentedClasses.incrementAndGet();
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Weaves counters into one method.
     *
     * @param className the declaring class, for the report
     * @param method    the method to rewrite
     * @return how many call sites were instrumented
     */
    private int instrumentMethod(String className, MethodNode method) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return 0;
        }

        InsnList instructions = method.instructions;
        int currentLine = -1;
        int woven = 0;

        for (AbstractInsnNode insn : instructions.toArray()) {
            if (insn instanceof LineNumberNode lineNode) {
                currentLine = lineNode.line;
                continue;
            }
            if (insn instanceof InvokeDynamicInsnNode) {
                // Counted, never woven — see the class javadoc.
                methodReferenceSites.addAndGet(countMethodReferences((InvokeDynamicInsnNode) insn));
                continue;
            }
            if (!(insn instanceof MethodInsnNode call)) {
                continue;
            }
            Optional<UnsafeApi> match = match(call.owner, call.name, call.desc);
            if (match.isEmpty()) {
                continue;
            }

            int siteId = RuntimeRecorder.register(className, method.name, method.desc,
                    currentLine, call.owner, match.get());
            instructions.insertBefore(insn, new LdcInsnNode(siteId));
            instructions.insertBefore(insn,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "observe", "(I)V", false));
            woven++;
        }
        return woven;
    }

    /**
     * Counts the unsafe method references carried in an invokedynamic bootstrap.
     *
     * @param indy the instruction
     * @return how many of its handles matched a rule
     */
    private int countMethodReferences(InvokeDynamicInsnNode indy) {
        if (indy.bsmArgs == null) {
            return 0;
        }
        int found = 0;
        for (Object arg : indy.bsmArgs) {
            if (arg instanceof org.objectweb.asm.Handle handle
                    && match(handle.getOwner(), handle.getName(), handle.getDesc()).isPresent()) {
                found++;
            }
        }
        return found;
    }

    /**
     * Matches a call against the rules.
     *
     * @param owner internal name of the called class
     * @param name  method name
     * @param desc  method descriptor
     * @return the matching rule
     */
    private Optional<UnsafeApi> match(String owner, String name, String desc) {
        synchronized (hierarchy) {
            return registry.match(owner, name, desc, hierarchy);
        }
    }
}
