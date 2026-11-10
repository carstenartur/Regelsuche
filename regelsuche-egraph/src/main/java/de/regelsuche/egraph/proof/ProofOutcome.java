package de.regelsuche.egraph.proof;

import java.util.Objects;
import java.util.Optional;

/** Accepted evidence or an explicit unavailable reason. */
public final class ProofOutcome {
    public enum Status {
        COMPLETE,
        NOT_REQUESTED,
        UNSUPPORTED_RULE,
        LIMIT_EXCEEDED,
        REPLAY_REJECTED,
        EXTRACTION_UNAVAILABLE,
        UNSUPPORTED_OBJECTIVE
    }

    private final Status status;
    private final String detail;
    private final long captureWork, buildWork, replayWork;
    private final ExtractionProofChecker.CheckedCertificate checked;

    private ProofOutcome(
            Status status,
            String detail,
            long capture,
            long build,
            long replay,
            ExtractionProofChecker.CheckedCertificate checked) {
        this.status = Objects.requireNonNull(status);
        this.detail = Objects.requireNonNull(detail);
        if (capture < 0 || build < 0 || replay < 0)
            throw new IllegalArgumentException("negative work");
        captureWork = capture;
        buildWork = build;
        replayWork = replay;
        this.checked = checked;
    }

    public static ProofOutcome complete(
            ExtractionProofChecker.CheckedCertificate checked, long captureWork, long buildWork) {
        Objects.requireNonNull(checked);
        return new ProofOutcome(
                Status.COMPLETE,
                "checked extraction equality",
                captureWork,
                buildWork,
                checked.workUsed(),
                checked);
    }

    public static ProofOutcome unavailable(
            Status status, String detail, long captureWork, long buildWork, long replayWork) {
        if (status == Status.COMPLETE)
            throw new IllegalArgumentException("complete requires checker token");
        return new ProofOutcome(status, detail, captureWork, buildWork, replayWork, null);
    }

    public static ProofOutcome notRequested() {
        return unavailable(Status.NOT_REQUESTED, "not requested", 0, 0, 0);
    }

    public Status status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public long captureWork() {
        return captureWork;
    }

    public long buildWork() {
        return buildWork;
    }

    public long replayWork() {
        return replayWork;
    }

    public Optional<ExtractionProofChecker.CheckedCertificate> checked() {
        return Optional.ofNullable(checked);
    }

    public boolean complete() {
        return status == Status.COMPLETE;
    }
}
