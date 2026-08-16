package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sortedStrings;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/** Domain-neutral, content-addressed input reference with a display form. */
public record RepresentationDiscoveryRunInput(
    String domainId,
    String inputSchema,
    String canonicalInputHash,
    String displayText,
    List<String> assumptions,
    String contentHash
) {
    public static final String EXPRESSION_INPUT_SCHEMA =
        "regelsuche.representation-discovery-expression-input/v1";

    public RepresentationDiscoveryRunInput {
        domainId = requireText(domainId, "domainId");
        inputSchema = requireText(inputSchema, "inputSchema");
        canonicalInputHash = requireSha256(
            canonicalInputHash, "canonicalInputHash");
        displayText = requireText(displayText, "displayText");
        assumptions = sortedStrings(assumptions, "assumptions");
        contentHash = requireSha256(contentHash, "contentHash");
        if (EXPRESSION_INPUT_SCHEMA.equals(inputSchema)) {
            String normalized = normalizeExpression(displayText);
            if (!normalized.equals(displayText)) {
                throw new IllegalArgumentException(
                    "expression input displayText is not normalized");
            }
            String expectedInputHash = expressionInputHash(
                normalized, assumptions);
            if (!expectedInputHash.equals(canonicalInputHash)) {
                throw new IllegalArgumentException(
                    "canonical expression input hash mismatch");
            }
        }
        String expected = inputHash(
            domainId,
            inputSchema,
            canonicalInputHash,
            displayText,
            assumptions
        );
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "run input content hash mismatch");
        }
    }

    public static RepresentationDiscoveryRunInput create(
        String domainId,
        String inputSchema,
        String canonicalInputHash,
        String displayText,
        List<String> assumptions
    ) {
        String normalizedDomain = requireText(domainId, "domainId");
        String normalizedSchema = requireText(inputSchema, "inputSchema");
        String normalizedHash = requireSha256(
            canonicalInputHash, "canonicalInputHash");
        String normalizedDisplay = requireText(displayText, "displayText");
        List<String> normalizedAssumptions = sortedStrings(
            assumptions, "assumptions");
        String hash = inputHash(
            normalizedDomain,
            normalizedSchema,
            normalizedHash,
            normalizedDisplay,
            normalizedAssumptions
        );
        return new RepresentationDiscoveryRunInput(
            normalizedDomain,
            normalizedSchema,
            normalizedHash,
            normalizedDisplay,
            normalizedAssumptions,
            hash
        );
    }

    public static RepresentationDiscoveryRunInput expression(
        String expression,
        List<String> assumptions
    ) {
        String normalized = normalizeExpression(expression);
        List<String> normalizedAssumptions = sortedStrings(
            assumptions, "assumptions");
        return create(
            "expression-rewrite",
            EXPRESSION_INPUT_SCHEMA,
            expressionInputHash(normalized, normalizedAssumptions),
            normalized,
            normalizedAssumptions
        );
    }

    private static String inputHash(
        String domainId,
        String inputSchema,
        String canonicalInputHash,
        String displayText,
        List<String> assumptions
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, WORKSPACE_SCHEMA + "/input");
        append(descriptor, domainId);
        append(descriptor, inputSchema);
        append(descriptor, canonicalInputHash);
        append(descriptor, displayText);
        append(descriptor, Integer.toString(assumptions.size()));
        assumptions.forEach(value -> append(descriptor, value));
        return sha256(descriptor.toString());
    }

    private static String expressionInputHash(
        String expression,
        List<String> assumptions
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, EXPRESSION_INPUT_SCHEMA);
        append(descriptor, expression);
        append(descriptor, Integer.toString(assumptions.size()));
        assumptions.forEach(value -> append(descriptor, value));
        return sha256(descriptor.toString());
    }

    private static String normalizeExpression(String expression) {
        return ExpressionFormatter.format(
            new ExpressionParser().parseTerm(requireText(
                expression, "expression")));
    }
}
