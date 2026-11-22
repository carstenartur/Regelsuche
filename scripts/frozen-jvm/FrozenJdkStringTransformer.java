import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import de.regelsuche.measure.FrozenStringPrimitives;

/** Source-bound scalar operations and concrete bulk copies in JDK25 compact string appends. */
final class FrozenJdkStringTransformer implements ClassFileTransformer {
    private static final ClassDesc HOOK = ClassDesc.of("de.regelsuche.measure.FrozenStringPrimitives");
    private static final MethodTypeDesc EVENT = MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V");
    private static final Set<String> CLASSES = Set.of("java/lang/AbstractStringBuilder", "java/lang/String",
            "java/util/Arrays","jdk/internal/util/ArraysSupport","java/lang/StringLatin1");
    private static final Set<String> ARITHMETIC = Set.of("IADD", "ISUB", "IAND", "IOR", "ISHL", "ISHR",
            "IUSHR", "LSHL", "IINC", "BASTORE", "BALOAD", "ARRAYLENGTH");
    private static final Map<String,String> SURVEY_HELPERS = Map.of("java/lang/String.length", "input-length",
            "java/lang/String.coder", "input-coder", "java/lang/Math.max", "maximum",
            "java/lang/Math.min", "minimum");
    private static final Set<String> ASB = Set.of("needsNewBuffer", "ensureCapacityNewCoder", "newCapacity", "isLatin1");
    private static volatile String failure;
    private final boolean survey;

    private FrozenJdkStringTransformer(boolean survey) {
        this.survey = survey;
    }

    static void install(Instrumentation inst, boolean survey) throws Exception {
        if (FrozenStringPrimitives.class.getClassLoader() != null)
            throw new IllegalStateException("JDK primitive receiver must be boot loaded");
        inst.redefineModule(String.class.getModule(), Set.of(FrozenStringPrimitives.class.getModule()),
                Map.of(), Map.of(), Set.of(), Map.of());
        inst.addTransformer(new FrozenJdkStringTransformer(survey), true);
        var originals = new java.util.ArrayList<Class<?>>();
        for (String type : CLASSES) originals.add(Class.forName(type.replace('/', '.'), false, null));
        inst.retransformClasses(originals.toArray(Class<?>[]::new));
        if (failure != null) throw new IllegalStateException(failure);
    }

    private static boolean admitted(String type, String name, String desc) {
        return switch (type) {
            case "java/lang/AbstractStringBuilder" -> ASB.contains(name) || name.equals("append")
                    && (desc.equals("(Ljava/lang/String;)Ljava/lang/AbstractStringBuilder;")
                    || desc.equals("(C)Ljava/lang/AbstractStringBuilder;"));
            case "java/lang/String" -> name.equals("getBytes") && desc.equals("([BIB)V");
            case "java/util/Arrays" -> name.equals("copyOf") && desc.equals("([BI)[B");
            case "java/lang/StringLatin1" -> name.equals("coderFromChar");
            case "jdk/internal/util/ArraysSupport" -> name.equals("newLength");
            default -> false;
        };
    }

    @Override public byte[] transform(Module module, ClassLoader loader, String owner, Class<?> originalClass,
                                      ProtectionDomain domain, byte[] bytes) {
        if (owner == null || !CLASSES.contains(owner)) return null;
        try {
            ClassFile cf = ClassFile.of();
            return cf.transformClass(cf.parse(bytes), (builder, element) -> {
                if (!(element instanceof MethodModel method) || !admitted(owner, method.methodName().stringValue(),
                        method.methodType().stringValue())) {
                    builder.with(element);
                    return;
                }
                String prefix = "jdk." + owner.substring(owner.lastIndexOf('/') + 1) + "." + method.methodName() + ".";
                builder.transformMethod(method, MethodTransform.transformingCode(new CodeTransform() {
                    private boolean assertionGuard;
                    @Override public void accept(CodeBuilder code, CodeElement element) {
                        if (element instanceof InvokeInstruction i) {
                            String target = i.owner().asInternalName(), name = i.name().stringValue();
                            if (target.equals("java/lang/System") && name.equals("arraycopy")) {
                                code.invokestatic(HOOK, "arraycopy", MethodTypeDesc.ofDescriptor(i.type().stringValue()));
                                return;
                            }
                            String helper = SURVEY_HELPERS.get(target + "." + name);
                            // A survey label observes a call site. It does not cover the invoked helper.
                            if (survey && helper != null) emit(code, prefix + helper);
                            else if (!admitted(target, name, i.type().stringValue()))
                                emit(code, "gap.jdk-call." + target + "." + name + i.type().stringValue());
                        } else if (element instanceof FieldInstruction field) {
                            String name = field.name().stringValue();
                            if (name.equals("$assertionsDisabled")) assertionGuard = true;
                            else emit(code, prefix + field.opcode() + "." + name);
                        } else if (element instanceof Instruction op) {
                            String tag = op.opcode().name();
                            if (assertionGuard && tag.startsWith("IF")) assertionGuard = false;
                            else if (ARITHMETIC.contains(tag) || tag.startsWith("IF")) emit(code, prefix + tag);
                        }
                        code.with(element);
                    }
                }));
            });
        } catch (Throwable problem) {
            failure = owner + ": " + problem;
            return null;
        }
    }

    private static void emit(CodeBuilder builder, String label) {
        builder.ldc(label).invokestatic(HOOK, "event", EVENT);
    }
}
