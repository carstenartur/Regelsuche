# Geometric sequence discovery domain

Dieses Verzeichnis ist absichtlich ein eigenständiges Java-25-Gradle-Projekt.
Es verwendet ausschließlich das veröffentlichte
`de.regelsuche:regelsuche-discovery-sdk`-Artefakt und keine Regelsuche-
Projektabhängigkeit.

Der Provider sucht einen ganzzahligen Multiplikator für eine geometrische
Folge. Die Tests decken ab:

- bestätigter Kandidat `2` einschließlich zuvor gefundenem Gegenbeispiel für
  Kandidat `1`;
- falscher Holdout mit Ergebnis `REFUTED`;
- zu kleines Budget mit Ergebnis `BUDGET_EXHAUSTED`;
- Auffindbarkeit des Providers über `ServiceLoader`.

Die Tests verwenden die JUnit-unabhängige SDK-Assertionsschicht:

```java
assertThat(run)
    .isConfirmed()
    .hasCounterexampleContaining("multiplier 1")
    .candidateSatisfies(candidate ->
        assertEquals(2, candidate.multiplier()))
    .hasContentAddressedEvidence();
```

Dadurch bleiben Status-, Zertifikats- und Evidence-Semantik sichtbar, ohne dass
ein externer Autor die internen Evidence-Records einzeln prüfen muss.
Domänenspezifische Aussagen bleiben normale Java-Assertions.

Nach Veröffentlichung der SDK-Artefakte in ein lokales Maven-Repository:

```bash
gradle clean test run \
  -PregelsucheRepository=/pfad/zum/repository \
  -PregelsucheVersion=0.4.0-SNAPSHOT
```

Die autoritative Checkout-Reproduktion übernimmt
`scripts/verify-student-java-sdk-consumer.py`.
