package dev.marv.foliacode.agent;

import dev.marv.foliacode.agent.testsupport.TransformingClassLoader;
import dev.marv.foliacode.rules.UnsafeApiRegistry;
import dev.marv.foliacode.testsupport.BukkitStubs;
import dev.marv.foliacode.testsupport.JavaSourceCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the transformer by loading and running what it produces.
 *
 * <p>Instrumented bytecode that the JVM verifier rejects would take a server down
 * on startup, so every test here defines the transformed class for real rather than
 * inspecting the byte array.</p>
 */
class InstrumentingTransformerTest {

    @TempDir
    Path workDir;

    @BeforeEach
    void resetRecorder() {
        RuntimeRecorder.reset();
    }

    @AfterEach
    void clearRecorder() {
        RuntimeRecorder.reset();
    }

    @Test
    @DisplayName("counts an unsafe call when the instrumented code actually runs")
    void countsExecutionOfUnsafeCall() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Plugin {
                    public static void touch(Block block) {
                        block.setType(Material.STONE);
                    }
                }
                """);

        InstrumentingTransformer transformer =
                new InstrumentingTransformer(new UnsafeApiRegistry(), List.of("com/example"));
        TransformingClassLoader loader =
                new TransformingClassLoader(classes, transformer, getClass().getClassLoader());

        Class<?> blockType = loader.loadClass("org.bukkit.block.Block");
        Object block = Proxy.newProxyInstance(loader, new Class<?>[]{blockType}, NO_OP);

        Class<?> plugin = loader.loadClass("com.example.Plugin");
        Method touch = plugin.getMethod("touch", blockType);
        touch.invoke(null, block);
        touch.invoke(null, block);

        List<SiteObservation> executed = RuntimeRecorder.snapshot().stream()
                .filter(SiteObservation::executed)
                .toList();

        assertEquals(1, executed.size(), "one call site should have been observed");
        SiteObservation observation = executed.get(0);
        assertEquals(2, observation.executionCount(), "it ran twice");
        assertEquals("Block.setType", observation.site().api().displayName());
        assertEquals("com.example.Plugin", observation.site().className());
        assertEquals("touch", observation.site().methodName());
        assertTrue(observation.site().hasLineNumber(), "the class was compiled with debug info");
        assertEquals(1, transformer.instrumentedClassCount());
    }

    @Test
    @DisplayName("records the thread a call ran on")
    void recordsThreadOfExecution() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Plugin {
                    public static void touch(Block block) {
                        block.setType(Material.STONE);
                    }
                }
                """);

        InstrumentingTransformer transformer =
                new InstrumentingTransformer(new UnsafeApiRegistry(), List.of("com/example"));
        TransformingClassLoader loader =
                new TransformingClassLoader(classes, transformer, getClass().getClassLoader());

        Class<?> blockType = loader.loadClass("org.bukkit.block.Block");
        Object block = Proxy.newProxyInstance(loader, new Class<?>[]{blockType}, NO_OP);
        Method touch = loader.loadClass("com.example.Plugin").getMethod("touch", blockType);

        Thread worker = new Thread(() -> {
            try {
                touch.invoke(null, block);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }, "plugin-worker");
        worker.start();
        worker.join();

        SiteObservation observation = RuntimeRecorder.snapshot().get(0);
        assertEquals(1, observation.threads().size());
        ThreadObservation thread = observation.threads().get(0);
        assertEquals("plugin-worker", thread.threadName());
        // No Bukkit on this classpath, so the server cannot be asked. It must say so
        // rather than assume an answer.
        assertEquals(ThreadVerdict.UNKNOWN, thread.verdict());
    }

    @Test
    @DisplayName("woven code needs the recorder visible from an isolated plugin loader")
    void wovenCodeNeedsTheRecorderOnAnIsolatedLoader() throws Exception {
        // Regression, found by booting a real Folia server. Paper gives each plugin an
        // isolated loader that does not delegate dev.marv.foliacode upwards, so the woven
        // invokestatic failed to link and killed the plugin with a NoClassDefFoundError —
        // the agent breaking the very thing it was meant to observe. In production the
        // fix is to publish the recorder to the bootstrap loader; this reproduces the
        // condition that made it necessary.
        Path classes = compile("""
                package com.example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Plugin {
                    public static void touch(Block block) {
                        block.setType(Material.STONE);
                    }
                }
                """);

        InstrumentingTransformer transformer =
                new InstrumentingTransformer(new UnsafeApiRegistry(), List.of("com/example"));

        // A loader that can see the stubs but hides the probe, the way Paper's plugin
        // loader hides anything on the system class path from the plugin. In production
        // the probe is published to the bootstrap loader, which no loader can hide;
        // this is what that publication is protecting against.
        ClassLoader isolating = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dev.marv.foliacode.probe.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        TransformingClassLoader loader =
                new TransformingClassLoader(classes, transformer, isolating);

        Class<?> blockType = loader.loadClass("org.bukkit.block.Block");
        Object block = Proxy.newProxyInstance(loader, new Class<?>[]{blockType}, NO_OP);
        Method touch = loader.loadClass("com.example.Plugin").getMethod("touch", blockType);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> touch.invoke(null, block));
        assertInstanceOf(NoClassDefFoundError.class, failure.getCause(),
                "this is exactly the failure a real Paper server produced");
    }

    @Test
    @DisplayName("leaves a class with no unsafe calls untouched")
    void leavesCleanClassUntouched() throws Exception {
        Path classes = compile("""
                package com.example;
                public class Plugin {
                    public static int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        InstrumentingTransformer transformer =
                new InstrumentingTransformer(new UnsafeApiRegistry(), List.of("com/example"));
        byte[] original = java.nio.file.Files.readAllBytes(
                classes.resolve("com/example/Plugin.class"));

        byte[] result = transformer.transform(getClass().getClassLoader(),
                "com/example/Plugin", null, null, original);

        assertNull(result, "returning null tells the JVM to keep the original bytes");
        assertEquals(0, transformer.instrumentedClassCount());
    }

    @Test
    @DisplayName("skips classes outside the include filter")
    void skipsClassesOutsideIncludeFilter() throws Exception {
        Path classes = compile("""
                package com.other;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Plugin {
                    public static void touch(Block block) {
                        block.setType(Material.STONE);
                    }
                }
                """, "com.other.Plugin");

        InstrumentingTransformer transformer =
                new InstrumentingTransformer(new UnsafeApiRegistry(), List.of("com/example"));
        byte[] original = java.nio.file.Files.readAllBytes(
                classes.resolve("com/other/Plugin.class"));

        assertNull(transformer.transform(getClass().getClassLoader(),
                "com/other/Plugin", null, null, original));
        assertEquals(0, RuntimeRecorder.instrumentedSiteCount());
    }

    @Test
    @DisplayName("never instruments the server or the JDK")
    void neverInstrumentsServerOrJdk() throws Exception {
        Path classes = compile("""
                package com.example;
                import org.bukkit.Material;
                import org.bukkit.block.Block;
                public class Plugin {
                    public static void touch(Block block) {
                        block.setType(Material.STONE);
                    }
                }
                """);
        byte[] bytes = java.nio.file.Files.readAllBytes(classes.resolve("com/example/Plugin.class"));

        // An empty include list means "everything", which must still exclude these.
        InstrumentingTransformer transformer =
                new InstrumentingTransformer(new UnsafeApiRegistry(), List.of());

        assertNull(transformer.transform(getClass().getClassLoader(),
                "org/bukkit/Something", null, null, bytes));
        assertNull(transformer.transform(getClass().getClassLoader(),
                "java/util/Something", null, null, bytes));
        assertNull(transformer.transform(getClass().getClassLoader(),
                "dev/marv/foliacode/agent/Something", null, null, bytes));
        assertNull(transformer.transform(null, "com/example/Plugin", null, null, bytes),
                "a null loader is the bootstrap loader, which holds no plugin code");

        assertNotNull(transformer.transform(getClass().getClassLoader(),
                "com/example/Plugin", null, null, bytes),
                "an unrelated package is still instrumented when no filter is set");
    }

    @Test
    @DisplayName("counts method references without pretending to instrument them")
    void countsMethodReferencesSeparately() throws Exception {
        Path classes = compile("""
                package com.example;
                import java.util.List;
                import org.bukkit.entity.Entity;
                public class Plugin {
                    public static void purge(List<Entity> entities) {
                        entities.forEach(Entity::remove);
                    }
                }
                """);

        InstrumentingTransformer transformer =
                new InstrumentingTransformer(new UnsafeApiRegistry(), List.of("com/example"));
        byte[] original = java.nio.file.Files.readAllBytes(
                classes.resolve("com/example/Plugin.class"));

        byte[] result = transformer.transform(getClass().getClassLoader(),
                "com/example/Plugin", null, null, original);

        assertEquals(1, transformer.methodReferenceSiteCount(),
                "the Entity::remove handle should be seen");
        assertNull(result, "nothing is woven, so the class is left alone");
        assertFalse(RuntimeRecorder.instrumentedSiteCount() > 0,
                "a method reference is not an instrumented site");
    }

    /**
     * Compiles a class named {@code com.example.Plugin} against the Bukkit stubs.
     *
     * @param source the source text
     * @return the directory holding the compiled classes
     * @throws IOException if compilation fails
     */
    private Path compile(String source) throws IOException {
        return compile(source, "com.example.Plugin");
    }

    /**
     * Compiles one class against the Bukkit stubs.
     *
     * @param source    the source text
     * @param className the fully-qualified class name
     * @return the directory holding the compiled classes
     * @throws IOException if compilation fails
     */
    private Path compile(String source, String className) throws IOException {
        Map<String, String> sources = BukkitStubs.with(className, source);
        return JavaSourceCompiler.compile(workDir, sources);
    }

    /** Returns a default value for every call, which is all the stubs need. */
    private static final InvocationHandler NO_OP = (proxy, method, args) -> {
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType.isPrimitive() && returnType != void.class) {
            return 0;
        }
        return null;
    };
}
