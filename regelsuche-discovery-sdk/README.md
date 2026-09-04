# Regelsuche Discovery SDK

Headless Java-25-Fassade für eigene begrenzte mathematische
`DiscoveryDomain<State, Candidate, Certificate>`-Implementierungen.

Die wichtigsten Einstiegstypen sind `DiscoveryDomainBuilder`,
`RegelsucheDiscovery`, `DiscoveryRun`, `DiscoveryRunAssertions`,
`DiscoveryDomainProvider` und `DiscoveryDomainCatalog`. Vereinfachte
Verdrahtung ändert keine mathematische Aussage: `CONFIRMED` erfordert weiterhin
einen expliziten Evaluator und ein Zertifikat; ein leerer Gegenbeispielfund ist
kein Beweis.

Ein eigenständiges Starterprojekt lässt sich aus dem Checkout erzeugen:

```bash
python3 scripts/create-student-discovery-domain.py \
  --output ../my-first-regelsuche-domain
```

Die Consumer-CI baut sowohl das gepflegte externe Beispiel als auch einen
frisch erzeugten, nicht nachbearbeiteten Starter gegen die isoliert
veröffentlichten SDK-Artefakte.

Quickstart, Assertions, ServiceLoader-SPI, Generator und externer Consumer:
[`docs/java-discovery-sdk.md`](../docs/java-discovery-sdk.md).
