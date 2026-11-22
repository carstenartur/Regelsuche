import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.Set;

/** Original source operation sites before the first cold preparation or warm NFA execution. */
final class FrozenGraalPyRegexAdmission {
    private static final String RE = "com/oracle/graal/python/builtins/modules/re/";
    private static final String PYTHON_METHOD = RE + "PythonMethod";
    private static final String TRUFFLE_STRING = "com/oracle/truffle/api/strings/TruffleString";
    static final Set<String> TYPES = Set.of(RE + "PatternBuiltins$MatchNode", RE + "TRegexCache",
            RE + "PatternNodes$TRegexCompileNode", RE + "PatternNodes$SearchNode", RE + "PatternNodes$RECheckInputTypeNode",
            PYTHON_METHOD);

    static String start(String owner, String method) {
        if (owner.equals(RE + "PatternBuiltins$MatchNode") && method.equals("match")) return "regex.native-start";
        if (owner.equals(RE + "PatternNodes$RECheckInputTypeNode") && method.equals("check")) return "regex.input-type";
        return null;
    }

    static String site(String owner, String method, Instruction op) {
        String opcode = op.opcode().name();
        if (owner.equals(RE + "TRegexCache")) return cache(method, op, opcode);
        if (owner.equals(PYTHON_METHOD) && method.equals("getTRegexOption")) return methodOption(op);
        if (owner.equals(RE + "PatternNodes$TRegexCompileNode") && method.equals("getCompiledRegexLocaleNonSensitive")
                && opcode.equals("IFNULL")) return "regex.cached-or-compile";
        if (owner.equals(RE + "PatternNodes$SearchNode") && method.equals("getSliceCached")) {
            if (op instanceof InvokeInstruction i) {
                String call = i.owner().asInternalName();
                if (call.endsWith("/PyObjectSizeNode")) return "regex.input-length";
                if (call.endsWith("$TRegexCallExec")) return "gap.regex.executor";
            }
            return switch (opcode) {
                case "IF_ICMPLT" -> "regex.position-before-end";
                case "IFGE" -> "regex.position-nonnegative";
                case "IF_ICMPLE" -> "regex.position-within-length";
                default -> null;
            };
        }
        return null;
    }

    private static String methodOption(Instruction instruction) {
        if (instruction instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD
                && field.owner().asInternalName().equals(PYTHON_METHOD) && field.name().equalsString("name")
                && field.type().equalsString("L" + TRUFFLE_STRING + ";")) return "regex.read-method-name";
        if (instruction instanceof InvokeInstruction call && call.opcode() == Opcode.INVOKEVIRTUAL
                && call.owner().asInternalName().equals(TRUFFLE_STRING) && call.name().equalsString("toJavaStringUncached")
                && call.type().equalsString("()Ljava/lang/String;")) return "gap.regex.method-name-conversion";
        return null;
    }

    private static String cache(String method, Instruction instruction, String op) {
        return switch (method) {
            case "getRegexp" -> switch (op) {
                case "TABLESWITCH" -> "regex.select-method";
                case "IFEQ" -> "regex.must-advance";
                case "GETFIELD" -> "regex.read-" + ((FieldInstruction) instruction).name().stringValue();
                default -> null;
            };
            case "compile" -> op.equals("IFEQ") ? "regex.select-encoding" : null;
            case "isBinary" -> op.equals("GETFIELD") ? "regex.binary" : null;
            case "getTRegexOptions" -> instruction instanceof InvokeInstruction i
                    && i.owner().asInternalName().equals("java/lang/StringBuilder")
                    ? switch (i.name().stringValue()) {
                        case "append" -> "gap.regex.options-append";
                        case "toString" -> "gap.regex.options-materialize";
                        default -> null;
                    } : null;
            default -> null;
        };
    }
}
