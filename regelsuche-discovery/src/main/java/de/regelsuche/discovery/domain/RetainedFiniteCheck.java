package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Binds historical finite-check rows to source data without executing the checker. */
final class RetainedFiniteCheck {
    private RetainedFiniteCheck() { }

    static void verify(DomainDiscoveryEvidence source, JsonNode check, int maxRows) {
        boolean recurrence = switch (source.descriptor().domainId()) {
            case FiniteDifferenceSequenceDomain.DOMAIN_ID -> false;
            case LinearRecurrenceSequenceDomain.DOMAIN_ID -> true;
            default -> throw new IllegalArgumentException("unsupported retained finite checker domain");
        };
        String contract = recurrence ? DomainFiniteDataCheck.RESIDUAL_CONTRACT : DomainFiniteDataCheck.NEWTON_CONTRACT;
        require(contract.equals(check.path("checkerContract").textValue()));
        var properties = source.domainEvidence().properties();
        var observed = terms(properties.get("observedTerms"));
        var holdout = terms(properties.get("holdoutTerms"));
        int total = observed.size() + holdout.size();
        require(numberEquals(check.path("observedCount"), observed.size())
            && numberEquals(check.path("holdoutCount"), holdout.size())
            && numberEquals(check.path("retainedTermCount"), total));
        require(source.certificate() != null);
        var generated = source.certificate().rendered().lines().filter(line -> line.startsWith("generatedTerms="))
            .map(line -> terms(line.substring("generatedTerms=".length()))).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("missing generated terms in source certificate"));
        require(generated.size() == total);
        int order = recurrence ? Integer.parseInt(properties.get("recurrenceOrder")) : 0;
        JsonNode rows = check.path("rows");
        require(rows.isArray() && rows.size() <= maxRows && rows.size() <= total);
        int firstMismatch = -1;
        boolean witnessMismatch = false;
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            boolean inObserved = index < observed.size();
            String retained = inObserved ? observed.get(index) : holdout.get(index - observed.size());
            require(numberEquals(row.path("index"), index)
                && (inObserved ? "OBSERVED" : "HOLDOUT").equals(row.path("partition").textValue()));
            verifyWindow(row, index, recurrence, order);
            verifyTerms(row, retained, generated.get(index));
            boolean dataAgrees = row.path("dataAgrees").booleanValue();
            if (!dataAgrees && firstMismatch < 0) firstMismatch = index;
            witnessMismatch |= !row.path("witnessAgrees").booleanValue();
        }
        verifySummary(check, total > 0 && rows.size() == total, firstMismatch, witnessMismatch);
    }

    private static void verifyTerms(JsonNode row, String retained, String witness) {
        require(retained.equals(row.path("retained").textValue())
            && witness.equals(row.path("witness").textValue())
            && row.path("computed").isTextual() && row.path("residual").isTextual());
        boolean dataAgrees = retained.equals(row.path("computed").textValue());
        boolean witnessAgrees = witness.equals(row.path("computed").textValue());
        require(booleanEquals(row.path("dataAgrees"), dataAgrees)
            && booleanEquals(row.path("witnessAgrees"), witnessAgrees)
            && dataAgrees == "0".equals(row.path("residual").textValue()));
    }

    private static void verifyWindow(JsonNode row, int index, boolean recurrence, int order) {
        boolean residual = recurrence && index >= order;
        String kind = recurrence ? (residual ? "RECURRENCE_RESIDUAL" : "SEED_IDENTITY") : "NEWTON_TERM";
        require(kind.equals(row.path("kind").textValue()));
        JsonNode start = row.path("windowStartInclusive"), end = row.path("windowEndExclusive");
        require(residual ? numberEquals(start, index - order) && numberEquals(end, index)
            : start.isNull() && end.isNull());
    }

    private static void verifySummary(JsonNode check, boolean complete, int firstMismatch, boolean witnessMismatch) {
        require(booleanEquals(check.path("complete"), complete));
        boolean found = firstMismatch >= 0;
        JsonNode reported = check.path("firstCounterexampleIndex");
        require(found ? numberEquals(reported, firstMismatch) : reported.isNull());
        String counterexample = found ? "FOUND" : complete ? "NONE_FOUND" : "INCONCLUSIVE";
        require(counterexample.equals(check.path("counterexampleStatus").textValue()));
        String status = check.path("status").textValue();
        if (complete) {
            String expected = found ? "REFUTED_FINITE_DATA" : witnessMismatch ? "INVALID_WITNESS" : "CONFIRMED_FINITE_DATA";
            require(expected.equals(status));
        } else {
            require(!"REFUTED_FINITE_DATA".equals(status) && !"CONFIRMED_FINITE_DATA".equals(status));
        }
    }

    private static List<String> terms(String text) {
        require(text != null && text.startsWith("[") && text.endsWith("]"));
        return text.length() == 2 ? List.of() : List.of(text.substring(1, text.length() - 1).split(",", -1));
    }

    private static boolean numberEquals(JsonNode value, int expected) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == expected;
    }

    private static boolean booleanEquals(JsonNode value, boolean expected) {
        return value.isBoolean() && value.booleanValue() == expected;
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("retained finite check disagrees with source or row evidence");
    }
}
