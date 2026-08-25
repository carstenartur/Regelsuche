package de.regelsuche.polynomial;

/** One exact factorization implementation behind the typed engine SPI. */
public interface FactorizationEngine<C> {
    String engineId();

    String coefficientDomainId();

    FactorizationReport<C> factor(
        FactorizationRequest<C> request
    );
}
