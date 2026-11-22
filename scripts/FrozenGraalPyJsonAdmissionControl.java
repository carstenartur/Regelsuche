import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/** Compare each interrupted prefix to the original embedded GraalPy admission on one reused context. */
public final class FrozenGraalPyJsonAdmissionControl {
    static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path bridge = root.resolve("bridge.py");
        verifyBridge(bridge);
        String input = Files.readString(Path.of(args[2])).strip();
        if (!isStructuredAsciiProtocolInput(input))
            throw new IllegalArgumentException("expected structured ASCII protocol input");

        try (Context context = Context.newBuilder("python").allowIO(org.graalvm.polyglot.io.IOAccess.ALL)
                .allowNativeAccess(true).allowCreateThread(true).allowHostAccess(HostAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .environment("SYMPY_GROUND_TYPES", "python").build()) {
            context.eval("python", "lambda path: __import__('sys').path.insert(0,path)")
                .execute(root.resolve("packages").toString());
            var adapter = context.eval(Source.newBuilder("python", bridge.toFile()).build());
            var factor = adapter.getMember("factor_payload");
            verifyRuntime(adapter.getMember("runtime_info").execute().asString());

            String mode = args[3];
            boolean regex = !mode.equals("bom");
            if (mode.equals("warm")) factor.execute(input);
            String refused = refusedStage(mode);
            var receipt = new FrozenGraalPyJsonAdmission.Receipt[1];
            installRecorder(context, Path.of(args[1]), regex, receipt);
            checkAllPrefixes(factor, input, mode, regex, refused, receipt);
        }
    }

    private static void verifyBridge(Path bridge) throws Exception {
        if (!sha(bridge).equals("d67e266e11c92fdd3e5e2c85c30a89a7b5292f56e291bc86a9d036ac7aab23c4"))
            throw new IllegalArgumentException("different factor bridge");
    }

    private static void verifyRuntime(String runtime) {
        if (!runtime.contains("graalpy") || !runtime.contains("1.14.0"))
            throw new IllegalStateException(runtime);
    }

    private static String refusedStage(String mode) {
        return switch (mode) {
            case "bulk" -> "gap.jdk-call.java/lang/String.length()I";
            case "bulk-survey" -> "gap.regex.options-materialize";
            case "cold" -> "gap.regex.options-append";
            case "warm" -> "gap.regex.executor";
            case "bom" -> "json.decoder.leading-whitespace";
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void installRecorder(Context context, Path instrumentation, boolean regex,
            FrozenGraalPyJsonAdmission.Receipt[] receipt) throws Exception {
        ProxyExecutable record = arguments -> {
            receipt[0].guest(arguments[0].asString());
            return null;
        };
        context.eval("python", Files.readString(instrumentation) + (regex ? "\ninstall_regex" : "\ninstall"))
            .execute(record);
    }

    private static void checkAllPrefixes(org.graalvm.polyglot.Value factor, String input,
            String mode, boolean regex, String refused, FrozenGraalPyJsonAdmission.Receipt[] holder) {
        List<String> full = null;
        for (int i = -1; i <= (full == null ? 0 : full.size()); i++) {
            int limit = i < 0 ? Integer.MAX_VALUE : i;
            var observation = runBudget(factor, input, limit, regex, holder);
            var events = observation.events();
            if (i < 0) {
                verifyUnbounded(mode, refused, observation);
                full = events;
                System.out.println(String.join(",", events));
            } else {
                verifyPrefix(observation, full, i, refused);
            }
        }
        System.out.println("checked-prefixes=" + (full.size() + 1));
        System.out.println("first-refused=" + refused);
    }

    private static FrozenGraalPyJsonAdmission.Receipt runBudget(org.graalvm.polyglot.Value factor,
            String input, int limit, boolean regex, FrozenGraalPyJsonAdmission.Receipt[] holder) {
        holder[0] = FrozenGraalPyJsonAdmission.begin(limit, regex);
        try {
            factor.execute(input);
            throw new IllegalStateException("uncovered decoder unexpectedly returned");
        } catch (PolyglotException raised) {
            if (holder[0].terminal() == null) throw raised;
        } finally {
            FrozenGraalPyJsonAdmission.finish(holder[0]);
        }
        return holder[0];
    }

    private static void verifyUnbounded(String mode, String refused,
            FrozenGraalPyJsonAdmission.Receipt receipt) {
        var events = receipt.events();
        if (!"UNMEASURED".equals(receipt.terminal()) || !refused.equals(receipt.refused()))
            throw new IllegalStateException("unsupported earlier event " + receipt.refused() + " after " + events);
        if (mode.equals("bulk")) {
            verifyBulk(events);
        } else if (mode.equals("bulk-survey")) {
            verifyBulkSurvey(events);
        }
    }

    private static void verifyBulk(List<String> events) {
        if (events.size() != 30
                || !events.getLast().equals("jdk.AbstractStringBuilder.append.GETFIELD.value")
                || events.stream().anyMatch(event -> event.startsWith("jdk.arraycopy.")
                        || event.equals("regex.read-method-name")))
            throw new IllegalStateException("expected 30 declared events and no bytes moved before String.length");
    }

    private static void verifyBulkSurvey(List<String> events) {
        // The survey passes the opaque helpers and observes both growth copies.
        long bytes = 13 + 16 + 15 + 34 + 18;
        if (events.stream().filter("jdk.arraycopy.source-byte"::equals).count() != bytes
                || events.stream().filter("jdk.arraycopy.target-byte"::equals).count() != bytes)
            throw new IllegalStateException("wrong number of actual byte transfers");
        if (events.size() != 373 || events.stream().filter("regex.read-method-name"::equals).count() != 1)
            throw new IllegalStateException("expected the 373-event survey with one original method-name field read");
    }

    private static void verifyPrefix(FrozenGraalPyJsonAdmission.Receipt receipt,
            List<String> full, int limit, String refused) {
        if (!receipt.events().equals(full.subList(0, limit))
                || !receipt.terminal().equals(limit == full.size() ? "UNMEASURED" : "DECLARED_LIMIT")
                || !receipt.refused().equals(limit == full.size() ? refused : full.get(limit)))
            throw new IllegalStateException("budget " + limit + ": " + receipt.terminal() + " at " + receipt.refused());
    }

    private static boolean isStructuredAsciiProtocolInput(String input) {
        return input.startsWith("{\"protocol\":") && input.chars().allMatch(ch -> ch < 128);
    }
}
