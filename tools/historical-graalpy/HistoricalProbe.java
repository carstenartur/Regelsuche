import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

/** Public development input and receiver for the original historical JVM bridge. */
public final class HistoricalProbe {
    private static final Map<String, Long> calls = new TreeMap<>();
    private static final Map<String, String> classes = new TreeMap<>();
    private static volatile boolean active;
    private static volatile String failure;
    private static boolean installed;

    private static boolean tracked(String name) {
        return name.startsWith("com/oracle/graal/python/builtins/modules/json/") ||
            name.startsWith("com/oracle/graal/python/builtins/objects/random/PRandom") ||
            name.startsWith("com/oracle/truffle/regex/tregex/nodes/") ||
            name.startsWith("com/oracle/graal/python/builtins/objects/str/StringBuiltins$Startswith");
    }

    public static void record(String name) {
        if (active) synchronized (calls) {
            calls.merge(name, 1L, Math::addExact);
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void premain(String options, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> previous,
                    ProtectionDomain domain, byte[] original) {
                if (name == null || !tracked(name)) return null;
                try {
                    Module receiver = HistoricalProbe.class.getModule();
                    if (module != null && module.isNamed() && !module.canRead(receiver)) {
                        instrumentation.redefineModule(module, Set.of(receiver), Map.of(), Map.of(), Set.of(), Map.of());
                    }
                    ClassFile cf = ClassFile.of();
                    var model = cf.parse(original);
                    synchronized (classes) {
                        classes.put(name, sha(original) + "\t" + domain.getCodeSource().getLocation());
                    }
                    return cf.transformClass(model, (builder, element) -> {
                        if (element instanceof MethodModel method && !method.methodName().stringValue().startsWith("<")) {
                            String id = name + "." + method.methodName().stringValue() + method.methodType().stringValue();
                            builder.transformMethod(method, MethodTransform.transformingCode(new CodeTransform() {
                                @Override public void atStart(CodeBuilder code) {
                                    code.ldc(id).invokestatic(ClassDesc.of("HistoricalProbe"), "record",
                                        MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"));
                                }
                                @Override public void accept(CodeBuilder code, CodeElement instruction) {
                                    code.with(instruction);
                                }
                            }));
                        } else {
                            builder.with(element);
                        }
                    });
                } catch (Throwable error) {
                    failure = name + ": " + error;
                    return null;
                }
            }
        });
        installed = true;
    }

    private static String polynomial() {
        return """
            {"protocol":"regelsuche.sympy-factorization/v1","domain":"ZZ","variableCount":1,"terms":[
            {"exponents":[6],"numerator":"1","denominator":"1"},{"exponents":[4],"numerator":"6","denominator":"1"},
            {"exponents":[2],"numerator":"11","denominator":"1"},{"exponents":[0],"numerator":"6","denominator":"1"}]}
            """.replaceAll("\\s+", "");
    }

    public static void main(String[] args) throws Exception {
        if (!installed) throw new IllegalStateException("missing JVM bytecode receiver");
        Path root = Path.of(args[0]).toAbsolutePath();
        Path evidence = Path.of(args[1]).toAbsolutePath();
        Path bridge = root.resolve("bridge.py");
        if (!sha(Files.readAllBytes(bridge)).equals("d67e266e11c92fdd3e5e2c85c30a89a7b5292f56e291bc86a9d036ac7aab23c4")) {
            throw new IllegalArgumentException("historical bridge hash differs");
        }
        try (Context context = Context.newBuilder("python").allowAllAccess(true)
                .environment("SYMPY_GROUND_TYPES", "python").build()) {
            context.eval("python", "lambda p: __import__('sys').path.insert(0, p)").execute(root.resolve("packages").toString());
            context.eval("python", """
                import sys, json
                import sympy, mpmath
                from sympy.core.cache import clear_cache
                from sympy.core.random import seed
                if (sys.implementation.name != 'graalpy' or sys.implementation.version[:3] != (25,1,3)
                    or sys.implementation.version.releaselevel != 'final' or sympy.__version__ != '1.14.0'
                    or mpmath.__version__ != '1.3.0' or sympy.ZZ.dtype is not int):
                    raise RuntimeError('historical runtime or ground domain differs')
                clear_cache()
                seed(0)
                """);
            var adapter = context.eval(Source.newBuilder("python", bridge.toFile()).build());
            var factor = adapter.getMember("factor_payload");
            active = true;
            String output;
            try {
                output = factor.execute(polynomial()).asString();
            } finally {
                active = false;
                var classLog = classes.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).toList();
                var callLog = calls.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).toList();
                Files.write(evidence.resolve("original-classes.tsv"), classLog);
                Files.write(evidence.resolve("entered-methods.tsv"), callLog);
            }
            Files.writeString(evidence.resolve("public-input.json"), polynomial());
            Files.writeString(evidence.resolve("original-output.json"), output);
            Files.writeString(evidence.resolve("bridge-runtime.json"), adapter.getMember("runtime_info").execute().asString());
            if (failure != null) throw new IllegalStateException("incomplete JVM receiver: " + failure);
            boolean scanned = calls.keySet().stream().anyMatch(key -> key.contains("JSONScannerBuiltins") && key.contains("scanOnceUnicode"));
            long draws = calls.entrySet().stream().filter(e -> e.getKey().endsWith("PRandom.nextDouble()D"))
                .mapToLong(Map.Entry::getValue).sum();
            if (!scanned || draws != 7) throw new IllegalStateException("JSON scanner or seven genuine PRandom calls missing: " + draws);
        }
    }
}
