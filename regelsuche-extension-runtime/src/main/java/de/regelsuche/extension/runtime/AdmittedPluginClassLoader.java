package de.regelsuche.extension.runtime;

import de.regelsuche.api.StableApi;
import de.regelsuche.extension.RegelsuchePlugin;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

/** Enforces the admitted dependency boundary, including lazily resolved classes. Not a sandbox. */
final class AdmittedPluginClassLoader extends URLClassLoader {
    private final Set<Path> admittedPaths;
    private final Set<Class<?>> sharedHostTypes;

    AdmittedPluginClassLoader(URL[] urls, ClassLoader parent, Set<Class<?>> sharedHostTypes)
            throws Exception {
        super(urls, parent);
        var paths = new HashSet<Path>();
        for (URL url : urls) {
            paths.add(Path.of(url.toURI()).toRealPath());
        }
        this.admittedPaths = Set.copyOf(paths);
        this.sharedHostTypes = Set.copyOf(sharedHostTypes);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // Loading does not initialize the returned class. Reject an unadmitted parent
            // resolution before returning it to the VM, even when an admitted JAR has a copy.
            Class<?> type = super.loadClass(name, false);
            if (!isHostType(type) && !isAdmittedType(type)) {
                throw new SecurityException("external dependency is neither an exported host type"
                    + " nor defined by an admitted artifact: " + name);
            }
            if (resolve) {
                resolveClass(type);
            }
            return type;
        }
    }

    private boolean isHostType(Class<?> type) {
        ClassLoader owner = type.getClassLoader();
        if (owner == null || owner == ClassLoader.getPlatformClassLoader()) {
            return true;
        }
        if (sharedHostTypes.contains(type)) {
            return true;
        }
        // Exact package and loader identity, not all de.regelsuche.* implementation classes.
        return Modifier.isPublic(type.getModifiers())
            && ((owner == RegelsuchePlugin.class.getClassLoader()
                    && type.getPackageName().equals(RegelsuchePlugin.class.getPackageName()))
                || (owner == StableApi.class.getClassLoader()
                    && type.getPackageName().equals(StableApi.class.getPackageName())));
    }

    private boolean isAdmittedType(Class<?> type) {
        if (type.getClassLoader() != this) {
            return false;
        }
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null) {
            return false;
        }
        try {
            return admittedPaths.contains(Path.of(source.getLocation().toURI()).toRealPath());
        } catch (Exception failure) {
            throw new SecurityException("cannot verify external dependency source: "
                + type.getName(), failure);
        }
    }

    @Override
    public URL getResource(String name) {
        return findResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // Service descriptors and dependency resources must not leak in from the host.
        return findResources(name);
    }
}
