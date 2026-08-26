package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Core deterministic p-adic lift execution after contract validation. */
final class HenselLiftExecution {
    private HenselLiftExecution() {
    }

    static HenselLiftingResult execute(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        ArrayList<HenselLiftStep> steps
    ) {
        UnivariatePolynomialView<BigInteger> source =
            UnivariatePolynomialView.from(request.source());
        HenselIntegerArithmetic.checkView(
            source,
            policy,
            work,
            "hensel.source-bounds");

        PrimeField field = PrimeField.of(selection.selectedPrime());
        BigInteger prime = field.modulus();
        BigInteger targetModulus =
            HenselIntegerArithmetic.targetModulus(
                prime,
                policy,
                work);
        HenselModularArithmetic.verifySourceCorrespondence(
            source,
            selection.modularSource(),
            field,
            work);

        List<UnivariatePolynomialView<BigInteger>> modularFactors =
            HenselModularArithmetic.anchoredModularFactors(
                selection,
                field,
                work);
        List<UnivariatePolynomialView<BigInteger>> liftedFactors =
            HenselIntegerArithmetic.initialIntegerFactors(
                source,
                modularFactors,
                policy,
                work);
        HenselIntegerArithmetic.verifyFixedReductions(
            liftedFactors,
            modularFactors,
            field,
            work,
            "hensel.verify.initial-reductions");
        UnivariatePolynomialView<BigInteger> initialProduct =
            HenselIntegerArithmetic.multiplyIntegerFactors(
                liftedFactors,
                policy,
                work,
                "hensel.initial-product");
        HenselIntegerArithmetic.verifyCongruence(
            source,
            initialProduct,
            prime,
            work,
            "hensel.verify.mod-p-product");

        if (policy.targetExponent() == 1) {
            return HenselLiftingResult.completed(
                targetModulus,
                HenselIntegerArithmetic.toSparse(liftedFactors),
                steps,
                work.ledger(),
                request,
                selection,
                policy);
        }

        List<HenselModularArithmetic.CrtEntry> crtEntries =
            HenselModularArithmetic.precomputeCrt(
                modularFactors,
                field,
                work);
        BigInteger currentModulus = prime;
        int currentExponent = 1;

        while (currentExponent < policy.targetExponent()) {
            long workBefore = total(work);
            String stage = "hensel.step-" + currentExponent;
            BigInteger nextModulus =
                HenselIntegerArithmetic.nextModulus(
                    currentModulus,
                    prime,
                    policy,
                    work,
                    stage + ".modulus");
            UnivariatePolynomialView<BigInteger> product =
                HenselIntegerArithmetic.multiplyIntegerFactors(
                    liftedFactors,
                    policy,
                    work,
                    stage + ".current-product");
            HenselIntegerArithmetic.verifyCongruence(
                source,
                product,
                currentModulus,
                work,
                stage + ".verify-current-product");
            UnivariatePolynomialView<BigInteger> error =
                HenselIntegerArithmetic.errorPolynomial(
                    source,
                    product,
                    currentModulus,
                    field,
                    policy,
                    work,
                    stage + ".error");
            List<UnivariatePolynomialView<BigInteger>> corrections =
                HenselModularArithmetic.corrections(
                    error,
                    crtEntries,
                    field,
                    work,
                    stage + ".corrections");
            HenselModularArithmetic.verifyCorrectionEquation(
                error,
                corrections,
                crtEntries,
                work,
                stage + ".verify-corrections");

            ArrayList<UnivariatePolynomialView<BigInteger>> updated =
                new ArrayList<>(liftedFactors.size());
            for (int index = 0;
                    index < liftedFactors.size();
                    index++) {
                updated.add(HenselIntegerArithmetic.applyCorrection(
                    liftedFactors.get(index),
                    corrections.get(index),
                    currentModulus,
                    nextModulus,
                    policy,
                    work,
                    stage + ".factor-" + index));
            }
            liftedFactors = List.copyOf(updated);
            HenselIntegerArithmetic.verifyFixedReductions(
                liftedFactors,
                modularFactors,
                field,
                work,
                stage + ".verify-reductions");
            UnivariatePolynomialView<BigInteger> liftedProduct =
                HenselIntegerArithmetic.multiplyIntegerFactors(
                    liftedFactors,
                    policy,
                    work,
                    stage + ".lifted-product");
            HenselIntegerArithmetic.verifyCongruence(
                source,
                liftedProduct,
                nextModulus,
                work,
                stage + ".verify-lifted-product");

            steps.add(HenselLiftStep.issue(
                currentExponent,
                currentExponent + 1,
                currentModulus,
                nextModulus,
                AlgorithmEvidence.sha256(error.canonicalMaterial()),
                corrections.stream()
                    .map(correction -> AlgorithmEvidence.sha256(
                        correction.canonicalMaterial()))
                    .toList(),
                AlgorithmEvidence.sha256(
                    liftedProduct.canonicalMaterial()),
                total(work) - workBefore));
            currentExponent++;
            currentModulus = nextModulus;
        }

        if (!currentModulus.equals(targetModulus)) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_TARGET_MODULUS_MISMATCH");
        }
        return HenselLiftingResult.completed(
            targetModulus,
            HenselIntegerArithmetic.toSparse(liftedFactors),
            steps,
            work.ledger(),
            request,
            selection,
            policy);
    }

    private static long total(PolynomialWorkBudget work) {
        return work.ledger().totalWorkUnits();
    }
}
