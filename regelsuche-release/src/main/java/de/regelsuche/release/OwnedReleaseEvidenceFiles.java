package de.regelsuche.release;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Owns directory descriptors and immutable first-read bytes for one verification. */
final class OwnedReleaseEvidenceFiles implements AutoCloseable {
    private static final int DIRECTORY_FLAGS = 0200000 | 0400000 | 02000000;
    private static final int MEMBER_FLAGS = 0400000 | 02000000 | 04000;
    private final Thread owner = Thread.currentThread();
    private final Map<Path, byte[]> snapshots = new HashMap<>();
    private int rootDescriptor = -1;

    OwnedReleaseEvidenceFiles(Path root) throws IOException {
        requireSupportedPlatform();
        rejectParents(root);
        requireNativePath(root);
        Path absolute = root.toAbsolutePath().normalize();
        int descriptor = Calls.open(-100, absolute.getRoot().toString(), DIRECTORY_FLAGS);
        try {
            for (Path part : absolute) {
                int next = Calls.open(descriptor, part.toString(), DIRECTORY_FLAGS);
                int previous = descriptor;
                descriptor = next;
                Calls.close(previous);
            }
            rootDescriptor = descriptor;
        } catch (Throwable failure) {
            try { Calls.close(descriptor); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    static void requireSupportedPlatform() {
        if (!System.getProperty("os.name").equals("Linux")
                || !java.util.Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))
                || ValueLayout.ADDRESS.byteSize() != 8 || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("UNSUPPORTED_PLATFORM: release evidence reader requires Linux AMD64 openat/statx ABI");
        }
        if (!OwnedReleaseEvidenceFiles.class.getModule().isNativeAccessEnabled()) {
            throw new IllegalArgumentException("UNSUPPORTED_NATIVE_ACCESS: use --enable-native-access=ALL-UNNAMED for release verification");
        }
    }

    private void requireOpen() {
        if (Thread.currentThread() != owner || rootDescriptor < 0) {
            throw new IllegalStateException("evidence root is closed or belongs to another thread");
        }
    }

    byte[] readBytes(Path supplied) throws IOException {
        requireOpen();
        Path relative = relative(supplied);
        byte[] snapshot = snapshots.get(relative);
        if (snapshot == null) {
            try (var parent = parent(relative)) {
                int descriptor = Calls.open(parent.descriptor, relative.getFileName().toString(), MEMBER_FLAGS);
                try {
                    int mode = Calls.mode(descriptor, "", 0x1000 | 0x100);
                    if ((mode & 0170000) != 0100000) throw new IOException("evidence member is not a regular file: " + relative);
                    snapshot = Calls.read(descriptor);
                    snapshots.put(relative, snapshot);
                } finally { Calls.close(descriptor); }
            }
        }
        return snapshot.clone();
    }

    boolean present(Path supplied) throws IOException {
        requireOpen();
        Path relative = relative(supplied);
        try (var parent = parent(relative)) {
            return Calls.mode(parent.descriptor, relative.getFileName().toString(), 0x100) != -1;
        }
    }

    private Parent parent(Path relative) throws IOException {
        int descriptor = rootDescriptor;
        boolean owned = false;
        try {
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                int next = Calls.open(descriptor, relative.getName(i).toString(), DIRECTORY_FLAGS);
                int previous = descriptor;
                descriptor = next;
                if (owned) Calls.close(previous);
                owned = true;
            }
            return new Parent(descriptor, owned);
        } catch (Throwable failure) {
            if (owned) try { Calls.close(descriptor); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private record Parent(int descriptor, boolean owned) implements AutoCloseable {
        @Override public void close() throws IOException { if (owned) Calls.close(descriptor); }
    }

    private static Path relative(Path value) {
        rejectParents(value);
        requireNativePath(value);
        Path normalized = value.normalize();
        if (value.isAbsolute() || normalized.toString().isEmpty() || normalized.toString().equals(".")) {
            throw new IllegalArgumentException("evidence member must be a relative child path");
        }
        return normalized;
    }

    /** The FFM bridge encodes path strings as UTF-8 in the native filesystem namespace. */
    private static void requireNativePath(Path path) {
        if (path.getFileSystem() != FileSystems.getDefault()) {
            throw new IllegalArgumentException("UNSUPPORTED_FILESYSTEM: native release reads require the default filesystem");
        }
        Path absolute = path.toAbsolutePath().normalize();
        // The URI retains original filename octets; its decoded path uses UTF-8.
        // Do not let replacement characters or another native filename encoding select a sibling.
        if (!Path.of(absolute.toUri().getPath()).normalize().equals(absolute)) {
            throw new IllegalArgumentException("UNSUPPORTED_PATH_ENCODING: native release paths must have a lossless UTF-8 representation");
        }
    }

    private static void rejectParents(Path path) {
        for (Path part : path) if (part.toString().equals("..")) throw new IllegalArgumentException("parent traversal is forbidden");
    }

    @Override public void close() throws IOException {
        if (rootDescriptor >= 0) {
            requireOpen();
            int descriptor = rootDescriptor;
            rootDescriptor = -1;
            Calls.close(descriptor);
        }
    }

    /** Public JDK FFM only; Linux UAPI statx has a stable 256-byte layout and mode at offset 28. */
    private static final class Calls {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final Linker.Option ERRNO = Linker.Option.captureCallState("errno");
        private static final MemoryLayout STATE = Linker.Option.captureStateLayout();
        private static final long ERROR_OFFSET = STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
        private static final MethodHandle OPEN = link("openat", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        private static final MethodHandle STAT = link("statx", FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        private static final MethodHandle READ = link("read", FunctionDescriptor.of(ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        private static final MethodHandle CLOSE = link("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        private static MethodHandle link(String name, FunctionDescriptor descriptor) {
            var symbol = LINKER.defaultLookup().find(name).orElseThrow(() ->
                new IllegalArgumentException("UNSUPPORTED_PLATFORM: missing Linux libc operation " + name));
            // openat is variadic even though read-only opens pass no optional mode argument.
            return name.equals("openat")
                ? LINKER.downcallHandle(symbol, descriptor, ERRNO, Linker.Option.firstVariadicArg(3))
                : LINKER.downcallHandle(symbol, descriptor, ERRNO);
        }

        private static int open(int parent, String name, int flags) throws IOException {
            try (var arena = Arena.ofConfined()) {
                var state = arena.allocate(STATE);
                var path = arena.allocateFrom(name);
                while (true) {
                    int result = (int) OPEN.invokeExact(state, parent, path, flags);
                    if (result >= 0) return result;
                    int error = state.get(ValueLayout.JAVA_INT, ERROR_OFFSET);
                    if (error != 4) throw new IOException("no-follow open failed for " + name + " (errno=" + error + ")");
                }
            } catch (IOException failure) { throw failure; }
            catch (Throwable failure) { throw new IOException("Linux openat invocation failed", failure); }
        }

        private static int mode(int descriptor, String name, int flags) throws IOException {
            try (var arena = Arena.ofConfined()) {
                var state = arena.allocate(STATE);
                var path = arena.allocateFrom(name);
                var stat = arena.allocate(256, 8);
                while (true) {
                    int result = (int) STAT.invokeExact(state, descriptor, path, flags, 1, stat);
                    if (result == 0) {
                        if ((stat.get(ValueLayout.JAVA_INT, 0) & 1) == 0) throw new IOException("statx did not attest the file type");
                        return Short.toUnsignedInt(stat.get(ValueLayout.JAVA_SHORT, 28));
                    }
                    int error = state.get(ValueLayout.JAVA_INT, ERROR_OFFSET);
                    if (error == 2 && !name.isEmpty()) return -1;
                    if (error != 4) throw new IOException("statx failed (errno=" + error + ")");
                }
            } catch (IOException failure) { throw failure; }
            catch (Throwable failure) { throw new IOException("Linux statx invocation failed", failure); }
        }

        private static byte[] read(int descriptor) throws IOException {
            try (var arena = Arena.ofConfined()) {
                var state = arena.allocate(STATE);
                var buffer = arena.allocate(8192);
                var result = new ByteArrayOutputStream();
                while (true) {
                    long count = (long) READ.invokeExact(state, descriptor, buffer, 8192L);
                    if (count == 0) return result.toByteArray();
                    if (count > 0) result.writeBytes(buffer.asSlice(0, count).toArray(ValueLayout.JAVA_BYTE));
                    else {
                        int error = state.get(ValueLayout.JAVA_INT, ERROR_OFFSET);
                        if (error != 4) throw new IOException("descriptor read failed (errno=" + error + ")");
                    }
                }
            } catch (IOException failure) { throw failure; }
            catch (Throwable failure) { throw new IOException("Linux read invocation failed", failure); }
        }

        private static void close(int descriptor) throws IOException {
            try (var arena = Arena.ofConfined()) {
                var state = arena.allocate(STATE);
                int result = (int) CLOSE.invokeExact(state, descriptor);
                if (result != 0) throw new IOException("descriptor close failed (errno=" + state.get(ValueLayout.JAVA_INT, ERROR_OFFSET) + ")");
            } catch (IOException failure) { throw failure; }
            catch (Throwable failure) { throw new IOException("Linux close invocation failed", failure); }
        }
    }
}
