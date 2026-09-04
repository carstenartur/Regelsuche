package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayStatus;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Verifies the canonical semantics of independently loaded exact finite
 * polynomial replay-receipt bytes.
 *
 * <p>The input must already have crossed the independent byte boundary. This
 * verifier checks the exact receipt schema, current revisions, status/count
 * invariants, candidate identities, receipt content hash and canonical JSON.
 * It does not load a plan run, rerun the solver, replay primitive rewrites or
 * issue proof, execution or promotion authority.</p>
 */
public final class ExactFinitePolynomialPlanReplayReceiptArtifactVerifier {
    public static final String VERIFIER_ID =
        "regelsuche.exact-finite-polynomial-plan-replay-receipt-artifact-verifier/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            VERIFIER_ID,
            ExactFinitePolynomialPlanReplayArtifactVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayArtifactVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanReplayVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            ReplayReceipt.SCHEMA,
            "strict-ordered-canonical-receipt-parser",
            "status-count-and-candidate-invariants",
            "receipt-content-hash-and-full-json-reconstruction",
            "sealed-verifier-owned-non-executable-semantic-view"));

    public VerifiedReplayReceiptArtifact verify(
        VerifiedArtifactBytes artifact
    ) {
        Objects.requireNonNull(artifact, "artifact");
        ArtifactReference reference = artifact.reference();
        requireReceiptReference(reference);

        byte[] bytes = artifact.copyBytes();
        String json = artifact.utf8();
        if (!Arrays.equals(bytes, json.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException(
                "verified receipt bytes differ from their UTF-8 view");
        }

        ParsedReceipt receipt = new CanonicalReceiptReader(json).read();
        List<String> candidateHashes = validateReceipt(receipt);
        String payload = render(receipt, candidateHashes, null);
        String expectedContentHash = SchematicProofPlan.hash(payload);
        if (!expectedContentHash.equals(receipt.contentHash())) {
            throw new IllegalArgumentException(
                "replay receipt contentHash does not match its contents");
        }
        String canonicalJson = render(
            receipt,
            candidateHashes,
            receipt.contentHash());
        if (!canonicalJson.equals(json)) {
            throw new IllegalArgumentException(
                "replay receipt bytes are not the canonical representation");
        }

        String verificationHash = SchematicProofPlan.hash(lengthPrefixed(
            VERIFIER_ID,
            REVISION_HASH,
            reference.artifactId(),
            reference.byteHash(),
            receipt.contentHash(),
            receipt.planHash(),
            receipt.planRunHash(),
            receipt.solverResultHash()));
        return new VerifiedReceiptArtifact(
            reference,
            receipt.planHash(),
            receipt.planRunHash(),
            receipt.solverResultHash(),
            receipt.solverRevisionHash(),
            receipt.runStatus(),
            receipt.totalAssignments(),
            receipt.evaluatedAssignments(),
            receipt.matchingAssignments(),
            receipt.retainedSolutions(),
            candidateHashes,
            receipt.replayStatus(),
            receipt.contentHash(),
            canonicalJson,
            verificationHash);
    }

    private static void requireReceiptReference(
        ArtifactReference reference
    ) {
        Objects.requireNonNull(reference, "artifact reference");
        if (!ExactFinitePolynomialPlanReplayArtifactVerifier.REFERENCE_SCHEMA
                .equals(reference.referenceSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE
                    .equals(reference.role())
                || !ReplayReceipt.SCHEMA.equals(reference.contentSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .RECEIPT_MEDIA_TYPE.equals(reference.mediaType())) {
            throw new IllegalArgumentException(
                "verified bytes are not an exact replay-receipt artifact");
        }
    }

    private static List<String> validateReceipt(ParsedReceipt receipt) {
        if (!ReplayReceipt.SCHEMA.equals(receipt.schema())) {
            throw new IllegalArgumentException(
                "unsupported replay receipt schema");
        }
        if (!ExactFinitePolynomialPlanReplayVerifier.VERIFIER_ID.equals(
                receipt.replayVerifierId())) {
            throw new IllegalArgumentException(
                "unexpected replay verifier ID");
        }
        if (!ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH.equals(
                receipt.replayVerifierRevisionHash())) {
            throw new IllegalArgumentException(
                "unexpected replay verifier revision");
        }
        SchematicProofPlan.requireSha256(receipt.planHash(), "planHash");
        SchematicProofPlan.requireSha256(receipt.planRunHash(), "planRunHash");
        SchematicProofPlan.requireSha256(
            receipt.solverResultHash(),
            "solverResultHash");
        SchematicProofPlan.requireSha256(
            receipt.solverRevisionHash(),
            "solverRevisionHash");
        if (!ExactFinitePolynomialHoleSolver.REVISION_HASH.equals(
                receipt.solverRevisionHash())) {
            throw new IllegalArgumentException(
                "receipt requires the current exact finite solver revision");
        }
        if (receipt.replayStatus()
                != ReplayStatus.CONFIRMED_IDENTICAL_REPLAY) {
            throw new IllegalArgumentException(
                "receipt does not report an identical confirmed replay");
        }
        if (receipt.totalAssignments() < 1
                || receipt.evaluatedAssignments()
                    != receipt.totalAssignments()
                || receipt.matchingAssignments() < 0
                || receipt.matchingAssignments()
                    > receipt.evaluatedAssignments()
                || receipt.retainedSolutions() < 0
                || receipt.retainedSolutions()
                    > receipt.matchingAssignments()) {
            throw new IllegalArgumentException(
                "replay receipt assignment counts are inconsistent");
        }

        List<String> candidateHashes = receipt.resolvedCandidateHashes()
            .stream()
            .map(value -> SchematicProofPlan.requireSha256(
                value,
                "resolved candidate hash"))
            .toList();
        if (candidateHashes.size() != receipt.retainedSolutions()) {
            throw new IllegalArgumentException(
                "replay receipt candidate count differs from retained count");
        }
        if (new HashSet<>(candidateHashes).size()
                != candidateHashes.size()) {
            throw new IllegalArgumentException(
                "replay receipt candidate hashes must be unique");
        }
        List<String> sorted = candidateHashes.stream()
            .sorted(Comparator.naturalOrder())
            .toList();
        if (!candidateHashes.equals(sorted)) {
            throw new IllegalArgumentException(
                "replay receipt candidate hashes are not canonically ordered");
        }
        validateStatusCounts(
            receipt.runStatus(),
            receipt.matchingAssignments(),
            receipt.retainedSolutions());
        SchematicProofPlan.requireSha256(
            receipt.contentHash(),
            "contentHash");
        return List.copyOf(candidateHashes);
    }

    private static void validateStatusCounts(
        ExactFinitePolynomialPlanRun.Status status,
        long matchingAssignments,
        int retainedSolutions
    ) {
        boolean valid = switch (status) {
            case COMPLETE_WITHOUT_SOLUTION ->
                matchingAssignments == 0 && retainedSolutions == 0;
            case COMPLETE_WITH_RESOLUTIONS ->
                matchingAssignments > 0
                    && matchingAssignments == retainedSolutions;
            case COMPLETE_RESOLUTION_SET_TRUNCATED ->
                matchingAssignments > retainedSolutions
                    && retainedSolutions > 0;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                "replay receipt status differs from assignment counts");
        }
    }

    private static String render(
        ParsedReceipt receipt,
        List<String> candidateHashes,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", receipt.schema())
            .property("verifierId", receipt.replayVerifierId())
            .property(
                "verifierRevisionHash",
                receipt.replayVerifierRevisionHash())
            .property("planHash", receipt.planHash())
            .property("planRunHash", receipt.planRunHash())
            .property("solverResultHash", receipt.solverResultHash())
            .property("solverRevisionHash", receipt.solverRevisionHash())
            .property("runStatus", receipt.runStatus().name())
            .property("totalAssignments", receipt.totalAssignments())
            .property(
                "evaluatedAssignments",
                receipt.evaluatedAssignments())
            .property("matchingAssignments", receipt.matchingAssignments())
            .property("retainedSolutions", receipt.retainedSolutions())
            .stringArray("resolvedCandidateHashes", candidateHashes)
            .property("replayStatus", receipt.replayStatus().name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value);
        }
        return result.toString();
    }

    public sealed interface VerifiedReplayReceiptArtifact
            permits VerifiedReceiptArtifact {
        ArtifactReference reference();

        String planHash();

        String planRunHash();

        String solverResultHash();

        String solverRevisionHash();

        ExactFinitePolynomialPlanRun.Status runStatus();

        long totalAssignments();

        long evaluatedAssignments();

        long matchingAssignments();

        int retainedSolutions();

        List<String> resolvedCandidateHashes();

        ReplayStatus replayStatus();

        String receiptContentHash();

        String canonicalJson();

        String verificationHash();

        boolean matches(ReplayReceipt receipt);
    }

    private record VerifiedReceiptArtifact(
        ArtifactReference reference,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        int retainedSolutions,
        List<String> resolvedCandidateHashes,
        ReplayStatus replayStatus,
        String receiptContentHash,
        String canonicalJson,
        String verificationHash
    ) implements VerifiedReplayReceiptArtifact {
        private VerifiedReceiptArtifact {
            reference = Objects.requireNonNull(reference, "reference");
            resolvedCandidateHashes = List.copyOf(
                Objects.requireNonNull(
                    resolvedCandidateHashes,
                    "resolvedCandidateHashes"));
            canonicalJson = Objects.requireNonNull(
                canonicalJson,
                "canonicalJson");
            verificationHash = SchematicProofPlan.requireSha256(
                verificationHash,
                "verificationHash");
        }

        @Override
        public boolean matches(ReplayReceipt receipt) {
            return receipt != null
                && receiptContentHash.equals(receipt.contentHash())
                && canonicalJson.equals(receipt.toCanonicalJson());
        }
    }

    private record ParsedReceipt(
        String schema,
        String replayVerifierId,
        String replayVerifierRevisionHash,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        int retainedSolutions,
        List<String> resolvedCandidateHashes,
        ReplayStatus replayStatus,
        String contentHash
    ) {
        private ParsedReceipt {
            resolvedCandidateHashes = List.copyOf(
                resolvedCandidateHashes);
        }
    }

    private static final class CanonicalReceiptReader {
        private final String input;
        private int index;

        private CanonicalReceiptReader(String input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        private ParsedReceipt read() {
            expect("{\"schema\":");
            String schema = string();
            expect(",\"verifierId\":");
            String verifierId = string();
            expect(",\"verifierRevisionHash\":");
            String verifierRevisionHash = string();
            expect(",\"planHash\":");
            String planHash = string();
            expect(",\"planRunHash\":");
            String planRunHash = string();
            expect(",\"solverResultHash\":");
            String solverResultHash = string();
            expect(",\"solverRevisionHash\":");
            String solverRevisionHash = string();
            expect(",\"runStatus\":");
            ExactFinitePolynomialPlanRun.Status runStatus = runStatus();
            expect(",\"totalAssignments\":");
            long totalAssignments = nonNegativeLong();
            expect(",\"evaluatedAssignments\":");
            long evaluatedAssignments = nonNegativeLong();
            expect(",\"matchingAssignments\":");
            long matchingAssignments = nonNegativeLong();
            expect(",\"retainedSolutions\":");
            int retainedSolutions = nonNegativeInt();
            expect(",\"resolvedCandidateHashes\":");
            List<String> candidateHashes = stringArray();
            expect(",\"replayStatus\":");
            ReplayStatus replayStatus = replayStatus();
            expect(",\"contentHash\":");
            String contentHash = string();
            expect("}");
            if (index != input.length()) {
                throw malformed("trailing receipt content");
            }
            return new ParsedReceipt(
                schema,
                verifierId,
                verifierRevisionHash,
                planHash,
                planRunHash,
                solverResultHash,
                solverRevisionHash,
                runStatus,
                totalAssignments,
                evaluatedAssignments,
                matchingAssignments,
                retainedSolutions,
                candidateHashes,
                replayStatus,
                contentHash);
        }

        private ExactFinitePolynomialPlanRun.Status runStatus() {
            String value = string();
            try {
                return ExactFinitePolynomialPlanRun.Status.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw malformed("unknown plan-run status", exception);
            }
        }

        private ReplayStatus replayStatus() {
            String value = string();
            try {
                return ReplayStatus.valueOf(value);
            } catch (IllegalArgumentException exception) {
                throw malformed("unknown replay status", exception);
            }
        }

        private List<String> stringArray() {
            expect("[");
            List<String> values = new ArrayList<>();
            if (consume(']')) {
                return List.of();
            }
            while (true) {
                values.add(string());
                if (consume(']')) {
                    return List.copyOf(values);
                }
                expect(",");
            }
        }

        private String string() {
            if (!consume('\"')) {
                throw malformed("expected JSON string");
            }
            int start = index;
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '\"') {
                    return input.substring(start, index - 1);
                }
                if (current == '\\'
                        || current < 0x20
                        || current > 0x7e) {
                    throw malformed(
                        "receipt strings must use unescaped canonical ASCII");
                }
            }
            throw malformed("unterminated JSON string");
        }

        private int nonNegativeInt() {
            long value = nonNegativeLong();
            if (value > Integer.MAX_VALUE) {
                throw malformed("integer exceeds signed 32-bit range");
            }
            return (int) value;
        }

        private long nonNegativeLong() {
            int start = index;
            while (index < input.length()
                    && input.charAt(index) >= '0'
                    && input.charAt(index) <= '9') {
                index++;
            }
            if (start == index) {
                throw malformed("expected non-negative decimal integer");
            }
            String value = input.substring(start, index);
            if (value.length() > 1 && value.charAt(0) == '0') {
                throw malformed("decimal integers must not have leading zeros");
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                throw malformed("integer exceeds signed 64-bit range", exception);
            }
        }

        private boolean consume(char expected) {
            if (index < input.length()
                    && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(String expected) {
            if (!input.startsWith(expected, index)) {
                throw malformed("expected " + expected);
            }
            index += expected.length();
        }

        private IllegalArgumentException malformed(String message) {
            return new IllegalArgumentException(
                "non-canonical replay receipt at offset " + index
                    + ": " + message);
        }

        private IllegalArgumentException malformed(
            String message,
            RuntimeException cause
        ) {
            return new IllegalArgumentException(
                "non-canonical replay receipt at offset " + index
                    + ": " + message,
                cause);
        }
    }
}
