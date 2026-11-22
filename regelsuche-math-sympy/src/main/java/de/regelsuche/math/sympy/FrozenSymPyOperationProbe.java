package de.regelsuche.math.sympy;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.scalar.ExactRational;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Execute one structured diagnostic input in an isolated historical interpreter.
 * The normal engine codec supplies the source. No study authority is created.
 */
public final class FrozenSymPyOperationProbe {
    private static final List<String> METER_FILES = List.of(
        "check-frozen-polynomial-operation-work.py", "frozen_graalpy_random_work.py", "frozen_sympy_coefficient_work.py",
        "frozen_sympy_native_work.py", "frozen_sympy_operation_work.py",
        "frozen_sympy_sequence_work.py", "frozen_sympy_source_patch.py");
    private static final String BOOTSTRAP = """
        import os, runpy, sys
        os.environ['SYMPY_GROUND_TYPES'] = 'python'
        sys.path[:0] = sys.argv[1:3]
        sys.argv = sys.argv[3:]
        runpy.run_path(sys.argv[0], run_name='__main__')
        """;

    private final Path checkout;
    private final Path interpreter;
    private final Path packages;
    private final SymPyFactorizationPolicy policy;

    public FrozenSymPyOperationProbe(Path checkout, Path interpreter, Path packages, SymPyFactorizationPolicy policy) {
        this.checkout = checkout.toAbsolutePath().normalize();
        this.interpreter = interpreter.toAbsolutePath().normalize();
        this.packages = packages.toAbsolutePath().normalize();
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public FrozenSymPyOperationObservation observeIntegers(FactorizationRequest<BigInteger> request,
            FrozenSymPyOperationObservation.Coverage goal) throws IOException {
        return observe(request, SymPyFactorizationCodec.integers(), goal);
    }

    public FrozenSymPyOperationObservation observeRationals(FactorizationRequest<ExactRational> request,
            FrozenSymPyOperationObservation.Coverage goal) throws IOException {
        return observe(request, SymPyFactorizationCodec.rationals(), goal);
    }

    private <C> FrozenSymPyOperationObservation observe(FactorizationRequest<C> request, SymPyFactorizationCodec<C> codec,
            FrozenSymPyOperationObservation.Coverage goal) throws IOException {
        request.structuralViolation().ifPresent(reason -> { throw new IllegalArgumentException(reason); });
        var input = codec.encode(request);
        if (input.byteLength() > policy.maxInputBytes()) throw new IllegalArgumentException("payload exceeds input policy");
        Path scripts = checkout.resolve("scripts");
        String meterHash = meterHash(scripts);
        List<String> command = new ArrayList<>(List.of(interpreter.toString(), "-I", "-c", BOOTSTRAP, scripts.toString(),
            packages.toString(), scripts.resolve(METER_FILES.getFirst()).toString(), "--checkout", checkout.toString(),
            "--strict-native", "--frozen-runtime", "--stdin", "--limit", Long.toString(request.maxWorkUnits())));
        if (Objects.requireNonNull(goal) == FrozenSymPyOperationObservation.Coverage.COMPLETE_BACKEND_WORK) {
            command.add("--complete-work");
        }
        byte[] payload = input.payload().getBytes(StandardCharsets.UTF_8);
        var output = SymPyProcessRunner.run(command, payload, policy.timeout(), policy.maxOutputBytes(), policy.maxOutputBytes());
        if (!output.available() || output.timedOut() || output.outputLimitExceeded() || output.exitCode() != 0) {
            throw new IOException("historical backend did not return a complete receipt: " + output.stderr());
        }
        return FrozenSymPyOperationObservation.decode(output.stdout(), rawHash(payload), meterHash, request.maxWorkUnits(), goal);
    }

    private static String meterHash(Path scripts) throws IOException {
        StringBuilder manifest = new StringBuilder();
        for (String name : METER_FILES) {
            manifest.append(name).append('=').append(rawHash(Files.readAllBytes(scripts.resolve(name)))).append('\n');
        }
        return rawHash(manifest.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String rawHash(byte[] value) {
        return SymPyEvidence.sha256(value).substring("sha256:".length());
    }
}
