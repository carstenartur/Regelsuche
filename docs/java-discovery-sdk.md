# Java Discovery SDK

Das Discovery SDK richtet sich an Mathematikstudierende und Entwickler, die
eine eigene endliche oder budgetierte mathematische Suchdomäne formulieren
möchten, ohne die Webanwendung, Spring oder die Persistenzschicht zu übernehmen.

## 1. Was das SDK übernimmt

Regelsuche übernimmt:

- deterministische Best-First-Ausführung;
- Zustands- und Kandidatendeduplizierung über kanonische Formen;
- harte Such- und Gegenbeispielbudgets;
- getrennte Status für Bestätigung, Widerlegung, Unentschiedenheit,
  Nichtunterstützung und Budgeterschöpfung;
- kanonische, content-addressed Evidence;
- ServiceLoader-basierte Auffindbarkeit externer Domänen.

Der Domänenautor bleibt verantwortlich für:

- mathematische Zustände und legale Übergänge;
- mindestens eine explizite Invariante;
- die Zielfunktion;
- die Kandidatenbildung;
- eine Gegenbeispielsuche;
- einen unabhängigen Evaluator;
- ein Zertifikat für bestätigte Kandidaten.

Diese Trennung ist beabsichtigt. Das SDK darf aus „kein Gegenbeispiel gefunden“
keine Wahrheit ableiten.

## 2. Entwicklungsabhängigkeit

Der erste SDK-Slice wird im Checkout als Maven-Artefakt

```text
de.regelsuche:regelsuche-discovery-sdk:0.4.0-SNAPSHOT
```

in ein isoliertes lokales Repository veröffentlicht. Ein späterer Release muss
dieselben Koordinaten, Sources und Javadoc als unveränderliche Release-Artefakte
bereitstellen, bevor ein öffentlicher Repository-Snippet als verfügbar gilt.

Ein externer Gradle-Verbraucher verwendet:

```gradle
plugins {
    id 'application'
}

repositories {
    maven { url = uri(regelsucheRepository) }
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation "de.regelsuche:regelsuche-discovery-sdk:0.4.0-SNAPSHOT"
}
```

Die CI baut das mitgelieferte Beispiel tatsächlich aus einer Kopie außerhalb
des Multi-Projekts. Dadurch kann Gradle keine internen Projektabhängigkeiten
anstelle der veröffentlichten Artefakte verwenden.

## 3. Eine kleine Domäne definieren

Das folgende Schema sucht einen ganzzahligen Multiplikator für eine Folge:

```java
var domain = DiscoveryDomainBuilder
    .<State, Candidate, Certificate>domain(
        "example-geometric-sequence",
        "v1"
    )
    .generator(seed -> List.of(initialState(seed.payload())))
    .stateCodec(State::canonical)
    .invariant(
        "valid-input",
        state -> state.valid()
            ? InvariantResult.pass()
            : InvariantResult.fail("invalid-input")
    )
    .operator("next-multiplier", this::nextMultipliers)
    .objective(this::assess)
    .candidate(
        context -> candidateFrom(context.currentState()),
        Candidate::canonical
    )
    .counterexamples(this::findObservedCounterexample)
    .evaluator(this::evaluateOnHoldout)
    .certificate(
        "GEOMETRIC_SEQUENCE_WITNESS",
        Certificate::canonical,
        Certificate::canonical
    )
    .build();
```

Der Builder lehnt unvollständige Definitionen ab. Insbesondere sind Invariante,
Gegenbeispielsuche, Evaluator und Zertifikatsvertrag nicht optional.

## 4. Einen Lauf ausführen

```java
DiscoveryRun<Candidate, Certificate> run =
    RegelsucheDiscovery.forDomain(domain)
        .campaign("geometric-sequence-demo")
        .seed(
            "powers-of-two",
            "observed=2,4,8,16;holdout=32,64;maxMultiplier=6",
            "student-example"
        )
        .budget(DiscoveryBudgets.small())
        .run();

System.out.println(run.outcome());
System.out.println(run.selectedCandidate());
System.out.println(run.counterexamples());
System.out.println(run.executedWork());
System.out.println(run.evidence().contentHash());
```

Der Referenzlauf untersucht zuerst Multiplikator `1`, behält dessen konkretes
Gegenbeispiel und bestätigt anschließend Multiplikator `2` auf dem getrennten
Holdout. Ein absichtlich falscher Holdout endet `REFUTED`; ein absichtlich zu
kleines Budget endet `BUDGET_EXHAUSTED` und enthält kein Zertifikat.

## 5. Eine externe Domäne registrieren

```java
public final class MyProvider implements DiscoveryDomainProvider {
    @Override
    public String id() {
        return "my-provider";
    }

    @Override
    public Collection<DiscoveryDomain<?, ?, ?>> domains() {
        return List.of(MyDomain.domain());
    }
}
```

Dazu gehört die Datei

```text
META-INF/services/de.regelsuche.sdk.discovery.DiscoveryDomainProvider
```

mit dem vollständig qualifizierten Providernamen. Laden:

```java
DiscoveryDomainCatalog catalog = RegelsucheDiscovery.loadDomains();
var registration = catalog.find("my-domain", "v1").orElseThrow();
```

Doppelte Provider-IDs und doppelte Kombinationen aus Domain-ID und Revision
werden abgewiesen. Die Auffindbarkeit eines Providers ist keine Aussage über
Artefaktvertrauen, mathematische Korrektheit, Proof oder Promotion.

## 6. Reproduktion im Checkout

```bash
gradle --no-daemon --no-configuration-cache \
  -PstudentSdkRepository="$PWD/build/student-sdk-repository" \
  :regelsuche-core:publishMavenJavaPublicationToStudentSdkRepository \
  :regelsuche-egraph:publishMavenJavaPublicationToStudentSdkRepository \
  :regelsuche-search:publishMavenJavaPublicationToStudentSdkRepository \
  :regelsuche-validation:publishMavenJavaPublicationToStudentSdkRepository \
  :regelsuche-discovery:publishMavenJavaPublicationToStudentSdkRepository \
  :regelsuche-discovery-sdk:publishMavenJavaPublicationToStudentSdkRepository

python3 scripts/verify-student-java-sdk-consumer.py --gradle gradle
```

Der Verifier kontrolliert auch die SDK-Sources- und Javadoc-JARs, die
SHA-256-Werte der veröffentlichten Artefakte und den Runtime-Abhängigkeitsbaum
des externen Beispiels.

## 7. Noch nicht Bestandteil dieses Slices

- ein öffentlicher Maven-Central- oder GitHub-Packages-Release des SDK;
- Workbench-/CLI-Auswahl externer `DiscoveryDomainProvider`;
- eine allgemeine Pareto- oder Optimalitätssuche;
- eine fertige Zahlentheorie-Objektbibliothek;
- automatische mathematische Korrektheit beliebiger Erweiterungen.

Diese Punkte bleiben in Issue #904 getrennt nachvollziehbar. Der erste Slice
belegt zunächst eine kleine, dokumentierte und aus einem externen Build
verwendbare Java-Schnittstelle.
