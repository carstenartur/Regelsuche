import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Independent tracing of the original historical Java generator for public controls. */
public final class FrozenRandomBytecodeControl {
    private static final String CLASS = "com.oracle.graal.python.builtins.objects.random.PRandom";
    private static final List<String> EVENTS = new ArrayList<>();

    public static void hit(String operation) {
        EVENTS.add(operation);
    }

    private static void observe(CodeBuilder code, String event) {
        code.ldc(event).invokestatic(ClassDesc.of("FrozenRandomBytecodeControl"), "hit",
            MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V"));
    }

    private static String operation(Instruction instruction) {
        if (instruction instanceof FieldInstruction field) {
            return field.opcode().name() + "." + field.name().stringValue();
        }
        if (instruction instanceof InvokeInstruction call) {
            if (call.opcode() != Opcode.INVOKEVIRTUAL || !call.name().equalsString("nextInt")
                    || !call.type().equalsString("()I") || !call.owner().asInternalName().equals(CLASS.replace('.', '/'))) {
                throw new IllegalStateException("unexpected call in original generator " + call);
            }
            return "";
        }
        return switch (instruction.opcode()) {
            case IADD, IINC, IAND, IOR, IXOR, ISHL, IUSHR, I2D, DADD, DMUL, IALOAD, IASTORE,
                 IF_ICMPLT, IF_ICMPGE -> instruction.opcode().name();
            case ALOAD_0, ALOAD_1, ASTORE_1, BIPUSH, SIPUSH, ICONST_0, ICONST_1, ICONST_5,
                 ILOAD_1, ILOAD_2, ILOAD_3, ISTORE_1, ISTORE_2, ISTORE_3, DUP, DUP_X1,
                 LDC, LDC2_W, GOTO, IRETURN, DRETURN -> "";
            default -> throw new IllegalStateException("unclassified original opcode " + instruction.opcode());
        };
    }

    private static void instrument(Path compiled) throws Exception {
        ClassFile files = ClassFile.of();
        var original = files.parse(Files.readAllBytes(compiled));
        var transformed = files.transformClass(original, ClassTransform.transformingMethodBodies(
            m -> Set.of("nextInt", "nextDouble").contains(m.methodName().stringValue()),
            (code, element) -> {
                if (element instanceof Instruction instruction) {
                    String event = operation(instruction);
                    if (!event.isEmpty()) observe(code, event);
                    code.with(instruction);
                    // In this pinned source the two IF_ICMPGE instructions skip
                    // the loop bodies. Fallthrough is one actual body visit.
                    if (instruction.opcode() == Opcode.IF_ICMPGE) observe(code, "LOOP_VISIT");
                } else {
                    code.with(element);
                }
            }));
        Files.write(compiled, transformed);
    }

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        instrument(classes.resolve(CLASS.replace('.', '/') + ".class"));
        Class<?> generator = Class.forName(CLASS);
        Object rng = generator.getConstructors()[0].newInstance(null, null);
        var restore = generator.getDeclaredMethod("restore", int[].class, int.class);
        var draw = generator.getDeclaredMethod("nextDouble");
        var state = generator.getDeclaredMethod("getState");
        var index = generator.getDeclaredMethod("getIndex");
        for (var method : List.of(restore, draw, state, index)) method.setAccessible(true);
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            long[] words = Arrays.stream(line.split(",")).mapToLong(Long::parseLong).toArray();
            int[] seed = new int[624];
            for (int i = 0; i < seed.length; i++) seed[i] = (int) words[i];
            restore.invoke(rng, seed, (int) words[624]);
            EVENTS.clear();
            double observed = (double) draw.invoke(rng);
            System.out.println(Double.toHexString(observed));
            var fields = Arrays.stream((int[]) state.invoke(rng)).mapToObj(Integer::toUnsignedString).toList();
            System.out.println(String.join(",", fields) + "," + index.invoke(rng));
            System.out.println(String.join(",", EVENTS));
        }
    }
}
