import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import de.regelsuche.measure.FrozenStringPrimitives;

/** Scoped source-level observation in the real 25.1.3 JVM implementation of str.startswith. */
public final class FrozenGraalPyJsonAdmission {
    private static final String STRING = "com/oracle/graal/python/builtins/objects/str/StringBuiltins";
    private static final String OPS = "com/oracle/truffle/api/strings/TStringOps";
    private static final ClassDesc RECEIVER = ClassDesc.of("FrozenGraalPyJsonAdmission");
    private static final MethodTypeDesc RECORD = MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V");
    private static final ThreadLocal<Receipt> ACTIVE = new ThreadLocal<>();
    private static volatile String transformFailure;
    private static boolean installed;
    private static boolean bulk;
    private static boolean bulkSurvey;

    public static final List<String> NATIVE = List.of("length", "length", "start-negative", "end-past-length",
            "remaining-subtract", "remaining-too-short", "prefix-mode", "region-loop-test", "region-loop-visit",
            "read-code-unit", "read-code-unit", "code-unit-equality");

    public static final class Receipt {
        private final int limit;
        private final List<String> events = new ArrayList<>();
        private int nativePosition;
        private boolean bom;
        private boolean insideStartsWith;
        private final boolean regexEnabled;
        private boolean leadingMatch;
        private boolean insideMatch;
        private String terminal;
        private String refused;

        Receipt(int limit, boolean regexEnabled) {
            this.regexEnabled = regexEnabled;
            if (limit < 0) throw new IllegalArgumentException("negative limit");
            this.limit = limit;
        }

        void stop(String reason, String stage) {
            if (terminal == null) {
                terminal = reason;
                refused = stage;
            }
            throw new IllegalStateException(terminal + " at " + refused);
        }

        void enter(String stage) {
            if (terminal != null) throw new IllegalStateException(terminal);
            if (events.size() == limit) stop("DECLARED_LIMIT", stage);
            events.add(stage);
        }

        void guest(String stage) {
            switch (stage) {
                case "json.loads.before-bom" -> {
                    if (bom || nativePosition != 0) stop("UNMEASURED", "unexpected-bom");
                    bom = true;
                }
                case "json.loads.after-bom" -> {
                    if (!bom || insideStartsWith || nativePosition != NATIVE.size()) stop("UNMEASURED", "incomplete-bom");
                    bom = false;
                }
                case "json.loads.before-decode" -> {
                    if (!regexEnabled) stop("UNMEASURED", "json.decoder.leading-whitespace");
                }
                case "json.decode.leading-match" -> {
                    if (!regexEnabled || leadingMatch) stop("UNMEASURED", "unexpected-leading-match");
                    leadingMatch = true;
                }
                case "json.loads.isinstance", "json.loads.cls.is-none", "json.loads.object_hook.is-none",
                        "json.loads.parse_int.is-none", "json.loads.parse_float.is-none", "json.loads.parse_constant.is-none",
                        "json.loads.object_pairs_hook.is-none", "json.loads.not-kw" -> enter(stage);
                default -> stop("UNMEASURED", stage);
            }
        }

        void jdk(String stage) {
            if (stage.startsWith("gap.")) stop("UNMEASURED", stage);
            enter(stage);
        }

        void nativeEvent(String event) {
            if (leadingMatch && event.startsWith("regex.")) {
                if (event.equals("regex.native-start")) {
                    if (insideMatch) stop("UNMEASURED", "nested-regex");
                    insideMatch = true;
                } else if (insideMatch) {
                    enter(event);
                }
                return;
            }
            if (insideMatch && bulkSurvey && event.equals("gap.regex.method-name-conversion")) return;
            if (insideMatch && event.startsWith("gap.")) stop("UNMEASURED", event);
            if (!bom) return;
            if ("native-start".equals(event)) {
                if (insideStartsWith || nativePosition != 0) stop("UNMEASURED", "nested-startswith");
                insideStartsWith = true;
                return;
            }
            if ("native-return".equals(event)) {
                if (!insideStartsWith || nativePosition != NATIVE.size()) stop("UNMEASURED", "unexpected-native-return");
                insideStartsWith = false;
                return;
            }
            if (!insideStartsWith) return;
            if (nativePosition >= NATIVE.size() || !NATIVE.get(nativePosition).equals(event))
                stop("UNMEASURED", "unrecognized-bom-path:" + event);
            enter("startswith." + event);
            nativePosition++;
        }

        List<String> events() { return List.copyOf(events); }
        String terminal() { return terminal; }
        String refused() { return refused; }
    }

    static Receipt begin(int limit, boolean regex) {
        if (!installed || transformFailure != null || ACTIVE.get() != null)
            throw new IllegalStateException("receiver is unavailable or already active: " + transformFailure);
        Receipt receipt = new Receipt(limit, regex);
        ACTIVE.set(receipt);
        return receipt;
    }

    static void finish(Receipt receipt) {
        if (receipt != ACTIVE.get()) throw new IllegalStateException("receipt owner mismatch");
        ACTIVE.remove();
        if (transformFailure != null || receipt.terminal == null)
            throw new IllegalStateException("incomplete admission receiver: " + transformFailure);
    }

    public static void record(String name) {
        Receipt receipt = ACTIVE.get();
        if (receipt != null) receipt.nativeEvent(name);
    }

    public static void verifyOptions(StringBuilder target) {
        var receipt = ACTIVE.get();
        if (receipt != null && receipt.insideMatch && !target.toString().equals("Flavor=Python,Encoding=UTF-32,PythonMethod=match"))
            receipt.stop("UNMEASURED", "incorrect-options-content");
    }

    public static StringBuilder append(StringBuilder target, String value) {
        var receipt = ACTIVE.get();
        if (receipt == null || !receipt.insideMatch) return target.append(value);
        FrozenStringPrimitives.begin(receipt::jdk);
        try { return target.append(value); }
        finally { FrozenStringPrimitives.end(); }
    }

    public static StringBuilder append(StringBuilder target, char value) {
        var receipt = ACTIVE.get();
        if (receipt == null || !receipt.insideMatch) return target.append(value);
        FrozenStringPrimitives.begin(receipt::jdk);
        try { return target.append(value); }
        finally { FrozenStringPrimitives.end(); }
    }

    private static String event(String method, Instruction op) {
        String opcode = op.opcode().name();
        return switch (method) {
            case "doStartsWith" -> opcode.equals("IRETURN") ? "native-return" : null;
            case "doString" -> op instanceof InvokeInstruction i && i.owner().asInternalName()
                    .equals("com/oracle/truffle/api/strings/TruffleString$CodePointLengthNode")
                    && i.name().equalsString("execute") ? "length" : null;
            case "adjustStartIndex" -> opcode.equals("IFGE") ? "start-negative" : null;
            case "adjustEndIndex" -> opcode.equals("IF_ICMPLE") ? "end-past-length" : null;
            case "doIt" -> switch (opcode) {
                case "ISUB" -> "remaining-subtract";
                case "IF_ICMPGE" -> "remaining-too-short";
                case "IF_ACMPNE" -> "prefix-mode";
                default -> null;
            };
            case "runRegionEqualsWithStride" -> switch (opcode) {
                case "IF_ICMPGE" -> "region-loop-test";
                case "IF_ICMPEQ" -> "code-unit-equality";
                default -> op instanceof InvokeInstruction i && i.name().equalsString("readValue")
                        && i.owner().asInternalName().equals(OPS) ? "read-code-unit" : null;
            };
            default -> null;
        };
    }

    public static void premain(String args, Instrumentation instrumentation) throws Exception {
        bulkSurvey = "bulk-survey".equals(args);
        bulk = bulkSurvey || "bulk".equals(args);
        if (bulk) FrozenJdkStringTransformer.install(instrumentation, bulkSurvey);
        Set<String> types = Set.of(STRING, STRING + "$PrefixSuffixNode", STRING + "$StartsWithNode", OPS);
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> previous,
                    ProtectionDomain protection, byte[] original) {
                if (name == null || !(types.contains(name) || FrozenGraalPyRegexAdmission.TYPES.contains(name))) return null;
                try {
                    if (module.isNamed() && !module.canRead(FrozenGraalPyJsonAdmission.class.getModule()))
                        instrumentation.redefineModule(module, Set.of(FrozenGraalPyJsonAdmission.class.getModule()),
                                Map.of(), Map.of(), Set.of(), Map.of());
                    ClassFile toolkit = ClassFile.of();
                    return toolkit.transformClass(toolkit.parse(original), (builder, element) -> {
                        if (!(element instanceof MethodModel method)) {
                            builder.with(element);
                            return;
                        }
                        String id = method.methodName().stringValue();
                        builder.transformMethod(method, MethodTransform.transformingCode(new CodeTransform() {
                            private int readSites;
                            @Override public void atStart(CodeBuilder code) {
                                String regex = FrozenGraalPyRegexAdmission.start(name, id);
                                if (regex != null) code.ldc(regex).invokestatic(RECEIVER, "record", RECORD);
                                if (id.equals("doStartsWith")) code.ldc("native-start").invokestatic(RECEIVER, "record", RECORD);
                            }
                            @Override public void accept(CodeBuilder code, CodeElement element) {
                                if (element instanceof Instruction instruction) {
                                    String stage = FrozenGraalPyRegexAdmission.TYPES.contains(name)
                                            ? FrozenGraalPyRegexAdmission.site(name, id, instruction) : event(id, instruction);
                                    if (stage != null) {
                                        if (bulk && stage.equals("gap.regex.options-materialize")) {
                                            code.dup().invokestatic(RECEIVER, "verifyOptions",
                                                    MethodTypeDesc.ofDescriptor("(Ljava/lang/StringBuilder;)V"));
                                        }
                                        if (bulk && stage.equals("gap.regex.options-append") && instruction instanceof InvokeInstruction i) {
                                            String descriptor = i.type().stringValue();
                                            if (descriptor.equals("(Ljava/lang/String;)Ljava/lang/StringBuilder;") ||
                                                    descriptor.equals("(C)Ljava/lang/StringBuilder;")) {
                                                code.invokestatic(RECEIVER, "append", MethodTypeDesc.ofDescriptor(
                                                        "(Ljava/lang/StringBuilder;" + descriptor.substring(1)));
                                                return;
                                            }
                                        }
                                        if (stage.equals("read-code-unit") && readSites++ == 0)
                                            code.ldc("region-loop-visit").invokestatic(RECEIVER, "record", RECORD);
                                        code.ldc(stage).invokestatic(RECEIVER, "record", RECORD);
                                    }
                                }
                                code.with(element);
                            }
                        }));
                    });
                } catch (Throwable error) {
                    transformFailure = name + ": " + error;
                    return null;
                }
            }
        });
        installed = true;
    }
}
