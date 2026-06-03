package de.regelsuche.validation;

public interface OracleValidator {
    OracleValidation validateEquivalence(String leftExpression, String rightExpression);

    record OracleValidation(OracleValidationStatus status, String evidence) {
        public OracleValidation {
            status = status == null ? OracleValidationStatus.UNAVAILABLE : status;
            evidence = evidence == null ? "" : evidence;
        }

        public static OracleValidation agrees(String evidence) {
            return new OracleValidation(OracleValidationStatus.AGREE, evidence);
        }

        public static OracleValidation disagrees(String evidence) {
            return new OracleValidation(OracleValidationStatus.DISAGREE, evidence);
        }

        public static OracleValidation unavailable(String evidence) {
            return new OracleValidation(OracleValidationStatus.UNAVAILABLE, evidence);
        }
    }

    enum OracleValidationStatus {
        AGREE,
        DISAGREE,
        UNAVAILABLE
    }
}
