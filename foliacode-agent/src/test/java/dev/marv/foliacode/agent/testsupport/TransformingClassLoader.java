package dev.marv.foliacode.agent.testsupport;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads compiled classes through a {@link ClassFileTransformer}, the way the JVM
 * does for a {@code -javaagent}.
 *
 * <p>Asserting on the bytes a transformer produces only proves the transformer
 * emitted what the test expected. Loading the result and running it proves the JVM
 * verifier accepts it and that the woven code actually fires — which is the part
 * that breaks in practice.</p>
 */
public final class TransformingClassLoader extends ClassLoader {

    private final Path classesDir;
    private final ClassFileTransformer transformer;

    /**
     * Creates a loader.
     *
     * @param classesDir  directory holding the compiled classes
     * @param transformer the transformer to apply
     * @param parent      the parent loader, which must be able to see the agent classes
     */
    public TransformingClassLoader(Path classesDir, ClassFileTransformer transformer, ClassLoader parent) {
        super(parent);
        this.classesDir = classesDir;
        this.transformer = transformer;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                // Classes under test are defined here even though the parent could also
                // see some of them, because a class loaded by the parent would never pass
                // through the transformer.
                Path file = classesDir.resolve(name.replace('.', '/') + ".class");
                loaded = Files.isRegularFile(file) ? define(name, file) : super.loadClass(name, false);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Reads, transforms and defines one class.
     *
     * @param name the binary class name
     * @param file the class file
     * @return the defined class
     * @throws ClassNotFoundException if the class cannot be read
     */
    private Class<?> define(String name, Path file) throws ClassNotFoundException {
        try {
            byte[] original = Files.readAllBytes(file);
            byte[] transformed = transformer.transform(
                    this, name.replace('.', '/'), null, null, original);
            byte[] bytes = transformed == null ? original : transformed;
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException | IllegalClassFormatException e) {
            throw new ClassNotFoundException(name, e);
        }
    }
}
