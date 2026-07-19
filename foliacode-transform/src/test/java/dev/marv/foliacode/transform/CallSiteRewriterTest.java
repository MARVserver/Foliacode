package dev.marv.foliacode.transform;

import dev.marv.foliacode.rules.TypeHierarchy;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import dev.marv.foliacode.testsupport.BukkitStubs;
import dev.marv.foliacode.testsupport.JavaSourceCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the rewriter by running what it produces.
 *
 * <p>A rewrite that passes an assertion on instruction opcodes but fails the JVM
 * verifier is worse than no rewrite: it turns a diagnosable problem into a plugin
 * that will not load. Every rewrite here is defined as a class and executed.</p>
 */
class CallSiteRewriterTest {

    @TempDir
    Path workDir;

    @Test
    @DisplayName("rewrites a discarded scheduler call and the result still runs")
    void rewritesDiscardedSchedulerCall() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void schedule(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        scheduler.runTask(plugin, task);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        assertEquals(1, rewrite.actions().size());
        TransformAction action = rewrite.actions().get(0);
        assertTrue(action.isRewritten(), "a discarded runTask is rewritable");
        assertEquals("BukkitScheduler.runTask", action.api().displayName());
        assertEquals("dev/marv/foliacode/shim/FoliaSchedulers", action.rule().shimOwner());

        // Run it. With no Bukkit present the shim cannot reach a Folia scheduler, so it
        // must fall back to the original call rather than silently dropping the task.
        RecordingHandler handler = new RecordingHandler();
        Object scheduler = proxy(rewrite.loader(), "org.bukkit.scheduler.BukkitScheduler", handler);
        Object plugin = proxy(rewrite.loader(), "org.bukkit.plugin.Plugin", handler);
        AtomicBoolean ran = new AtomicBoolean();
        Runnable task = () -> ran.set(true);

        Method schedule = rewrite.type().getMethod("schedule",
                rewrite.loader().loadClass("org.bukkit.scheduler.BukkitScheduler"),
                rewrite.loader().loadClass("org.bukkit.plugin.Plugin"),
                Runnable.class);
        schedule.invoke(null, scheduler, plugin, task);

        assertEquals(List.of("runTask"), handler.calls(),
                "the fallback must reach the original scheduler method");
        assertSame(task, handler.lastArguments()[1],
                "and hand it the very task the plugin passed");
        assertFalse(ran.get(), "the stub scheduler never runs the task, which is fine");
    }

    @Test
    @DisplayName("rewrites the older scheduleSync spellings, which return an int task id")
    void rewritesScheduleSyncCalls() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static void schedule(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        scheduler.scheduleSyncDelayedTask(plugin, task, 20L);
                        scheduler.scheduleSyncDelayedTask(plugin, task);
                        scheduler.scheduleSyncRepeatingTask(plugin, task, 0L, 20L);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        assertEquals(3, rewrite.actions().size());
        assertTrue(rewrite.actions().stream().allMatch(TransformAction::isRewritten),
                "all three are the same translation as runTaskLater and runTaskTimer");

        // The int task id these return is discarded, so the shim's 0 is unobservable —
        // but the rewritten class still has to satisfy the verifier, which needs the
        // replacement to leave an int on the stack exactly where one was before.
        RecordingHandler handler = new RecordingHandler();
        Object scheduler = proxy(rewrite.loader(), "org.bukkit.scheduler.BukkitScheduler", handler);
        Object plugin = proxy(rewrite.loader(), "org.bukkit.plugin.Plugin", handler);

        Method schedule = rewrite.type().getMethod("schedule",
                rewrite.loader().loadClass("org.bukkit.scheduler.BukkitScheduler"),
                rewrite.loader().loadClass("org.bukkit.plugin.Plugin"),
                Runnable.class);
        schedule.invoke(null, scheduler, plugin, (Runnable) () -> { });

        assertEquals(
                List.of("scheduleSyncDelayedTask", "scheduleSyncDelayedTask", "scheduleSyncRepeatingTask"),
                handler.calls(),
                "each falls back to the method it replaced when no Folia scheduler exists");
    }

    @Test
    @DisplayName("refuses a scheduleSync call whose task id is kept")
    void refusesScheduleSyncWhoseIdIsKept() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                public class Demo {
                    public static int schedule(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        return scheduler.scheduleSyncDelayedTask(plugin, task, 20L);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        assertEquals(RefusalReason.RESULT_IS_USED, rewrite.actions().get(0).refusal(),
                "a kept task id is used to cancel later, and Folia issues no such id");
    }

    @Test
    @DisplayName("refuses when the scheduler's result is used")
    void refusesWhenResultIsUsed() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitScheduler;
                import org.bukkit.scheduler.BukkitTask;
                public class Demo {
                    public static void schedule(BukkitScheduler scheduler, Plugin plugin, Runnable task) {
                        BukkitTask handle = scheduler.runTask(plugin, task);
                        handle.cancel();
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        assertEquals(1, rewrite.actions().size());
        TransformAction action = rewrite.actions().get(0);
        assertFalse(action.isRewritten());
        assertEquals(RefusalReason.RESULT_IS_USED, action.refusal(),
                "the Folia schedulers hand out no BukkitTask, so this cannot be translated");
    }

    @Test
    @DisplayName("rewrites a teleport reached through a subtype")
    void rewritesTeleportThroughSubtype() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.Location;
                import org.bukkit.entity.Player;
                public class Demo {
                    public static void move(Player player, Location location) {
                        player.teleport(location);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        TransformAction action = rewrite.actions().get(0);
        assertTrue(action.isRewritten(), "Player.teleport must reach the Entity.teleport rule");
        assertEquals("org/bukkit/entity/Player", action.calleeOwner());
        assertEquals("dev/marv/foliacode/shim/FoliaEntities", action.rule().shimOwner());
        assertTrue(action.hasCaveat(), "an async teleport completes later, and that must be said");
    }

    @Test
    @DisplayName("refuses a teleport whose result is tested")
    void refusesTeleportWhoseResultIsTested() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.Location;
                import org.bukkit.entity.Player;
                public class Demo {
                    public static boolean move(Player player, Location location) {
                        return player.teleport(location);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        assertEquals(RefusalReason.RESULT_IS_USED, rewrite.actions().get(0).refusal());
    }

    @Test
    @DisplayName("refuses APIs with no proven-safe rewrite")
    void refusesApisWithoutRewrite() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Demo {
                    public static void place(Block block) {
                        block.setType(Material.STONE);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        TransformAction action = rewrite.actions().get(0);
        assertFalse(action.isRewritten());
        assertEquals(RefusalReason.NO_PROVEN_REWRITE, action.refusal());
        assertTrue(action.refusal().explanation().contains("human decision"));
    }

    @Test
    @DisplayName("refuses BukkitRunnable rather than break cancel()")
    void refusesBukkitRunnable() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.plugin.Plugin;
                import org.bukkit.scheduler.BukkitRunnable;
                public class Demo {
                    public static void start(Plugin plugin) {
                        new BukkitRunnable() {
                            public void run() { }
                        }.runTaskTimer(plugin, 0L, 20L);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        List<TransformAction> actions = rewrite.actions();
        assertFalse(actions.isEmpty(), "the BukkitRunnable call must still be reported");
        assertTrue(actions.stream().noneMatch(TransformAction::isRewritten),
                "a scheduled BukkitRunnable keeps state that Folia's schedulers cannot provide");
        assertEquals(RefusalReason.NO_PROVEN_REWRITE, actions.get(0).refusal());
    }

    @Test
    @DisplayName("refuses method references, which have no call site to rewrite")
    void refusesMethodReferences() throws Exception {
        Path classes = compile("""
                package com.example;
                import java.util.List;
                import org.bukkit.entity.Entity;
                public class Demo {
                    public static void purge(List<Entity> entities) {
                        entities.forEach(Entity::remove);
                    }
                }
                """);

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        assertEquals(RefusalReason.METHOD_REFERENCE, rewrite.actions().get(0).refusal());
    }

    @Test
    @DisplayName("leaves a clean class byte-for-byte identical")
    void leavesCleanClassAlone() throws Exception {
        Path classes = compile("""
                package com.example;
                public class Demo {
                    public static int add(int a, int b) {
                        return a + b;
                    }
                }
                """);
        byte[] original = Files.readAllBytes(classes.resolve("com/example/Demo.class"));

        Rewrite rewrite = rewrite(classes, "com.example.Demo");

        assertTrue(rewrite.actions().isEmpty());
        assertArrayEqualsIgnoringNothing(original, rewrite.bytes());
    }

    /**
     * Rewrites one compiled class and defines the result.
     *
     * @param classes   the compiled classes directory
     * @param className the class to rewrite
     * @return the rewrite, ready to inspect and run
     * @throws IOException if the class cannot be read
     */
    private Rewrite rewrite(Path classes, String className) throws IOException, ClassNotFoundException {
        TypeHierarchy hierarchy = new TypeHierarchy();
        List<Path> classFiles = new ArrayList<>();
        try (var walk = Files.walk(classes)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".class"))
                    .forEach(classFiles::add);
        }
        for (Path file : classFiles) {
            ClassReader reader = new ClassReader(Files.readAllBytes(file));
            hierarchy.learn(reader.getClassName(), reader.getSuperName(), reader.getInterfaces());
        }

        Path file = classes.resolve(className.replace('.', '/') + ".class");
        byte[] original = Files.readAllBytes(file);
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);

        List<TransformAction> actions = new CallSiteRewriter(
                new UnsafeApiRegistry(), new TransformRules(), hierarchy).rewrite(node);

        byte[] bytes = original;
        if (actions.stream().anyMatch(TransformAction::isRewritten)) {
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            bytes = writer.toByteArray();
            Files.write(file, bytes);
        }

        ClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
        return new Rewrite(actions, bytes, loader, loader.loadClass(className));
    }

    /**
     * Compiles one class against the Bukkit stubs.
     *
     * @param source the source text
     * @return the compiled classes directory
     * @throws IOException if compilation fails
     */
    private Path compile(String source) throws IOException {
        Map<String, String> sources = BukkitStubs.with("com.example.Demo", source);
        return JavaSourceCompiler.compile(workDir, sources);
    }

    /**
     * Builds a proxy for a stub interface.
     *
     * @param loader        the loader that defined the interface
     * @param interfaceName the interface name
     * @param handler       the invocation handler
     * @return the proxy
     * @throws ClassNotFoundException if the interface is missing
     */
    private static Object proxy(ClassLoader loader, String interfaceName, InvocationHandler handler)
            throws ClassNotFoundException {
        return Proxy.newProxyInstance(loader,
                new Class<?>[]{loader.loadClass(interfaceName)}, handler);
    }

    private static void assertArrayEqualsIgnoringNothing(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual,
                "a class with nothing to rewrite must not be re-emitted at all");
    }

    /**
     * The outcome of rewriting one class.
     *
     * @param actions what the rewriter did
     * @param bytes   the resulting class bytes
     * @param loader  a loader that can define the result
     * @param type    the defined class
     */
    private record Rewrite(List<TransformAction> actions, byte[] bytes,
                           ClassLoader loader, Class<?> type) {
    }

    /** Records the calls made to a stub, so the fallback path can be observed. */
    private static final class RecordingHandler implements InvocationHandler {

        private final List<String> calls = new ArrayList<>();
        private Object[] lastArguments = new Object[0];

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            calls.add(method.getName());
            lastArguments = args == null ? new Object[0] : args;
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) {
                return true;
            }
            if (returnType.isPrimitive() && returnType != void.class) {
                return 0;
            }
            return null;
        }

        List<String> calls() {
            return calls;
        }

        Object[] lastArguments() {
            return lastArguments;
        }
    }
}
