# Regelsuche Discovery SDK

Headless Java-25-Fassade für eigene begrenzte mathematische
`DiscoveryDomain<State, Candidate, Certificate>`-Implementierungen.

Die wichtigsten Einstiegstypen sind `DiscoveryDomainBuilder`,
`RegelsucheDiscovery`, `DiscoveryRun`, `DiscoveryDomainProvider` und
`DiscoveryDomainCatalog`. Vereinfachte Verdrahtung ändert keine mathematische
Aussage: `CONFIRMED` erfordert weiterhin einen expliziten Evaluator und ein
Zertifikat; ein leerer Gegenbeispielfund ist kein Beweis.

Quickstart, ServiceLoader-SPI und externer Consumer:
[`docs/java-discovery-sdk.md`](../docs/java-discovery-sdk.md).
