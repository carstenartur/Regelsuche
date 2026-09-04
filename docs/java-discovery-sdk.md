# Java Discovery SDK

Das Discovery SDK richtet sich an Mathematikstudierende und Entwickler, die
eine eigene endliche oder budgetierte Suchdomäne formulieren möchten, ohne
Webanwendung, Spring oder Persistenz zu übernehmen.

## Verantwortungsgrenze

Regelsuche übernimmt:

- deterministische Best-First-Ausführung und kanonische Deduplizierung;
- harte Such- und Gegenbeispielbudgets;
- getrennte Erfolgs-, Widerlegungs-, Unentschiedenheits- und Budgetzustände;
- kanonische, content-addressed Evidence;
- ServiceLoader-basierte Auffindbarkeit externer Domänen.

Der Domänenautor definiert Zustände, legale Übergänge, mindestens eine
Invariante, Zielfunktion, Kandidatenbildung, Gegenbeispielsuche, unabhängigen
Evaluator und Zertifikat. Ein leerer Gegenbeispielfund ist niemals ein Beweis.

## Abhängigkeit

Der Checkout veröffentlicht den ersten Slice als

```text
de.regelsuche:regelsuche-discovery-sdk:0.4.0-SNAPSHOT
```

in ein isoliertes lokales Maven-Repository. Ein externer Gradle-Verbraucher
benötigt Java 25 und:

```gradle
repositories {
    maven { url = uri(regelsucheRepository) }
    mavenCentral()
}

dependencies {
    implementation "de.regelsuche:regelsuche-discovery-sdk:0.4.0-SNAPSHOT"
}
```

Die Checkout-Prüfung baut das Beispiel aus einer Kopie außerhalb des
Multi-Projekts. Interne Gradle-Projektabhängigkeiten können das veröffentlichte
Artefakt deshalb nicht unbemerkt ersetzen.

## Domäne definieren

Der Builder verdrahtet die portable `DiscoveryDomain`-Schnittstelle:

```java
var domain = DiscoveryDomainBuilder
    .<State, Candidate, Certificate>domain("my-domain", "v1")
    .generator(this::initialStates)
    .stateCodec(State::canonical)
    .invariant("valid-state", this::checkInvariant)
    .operator("next", this::successors)
    .objective(this::assess)
    .candidate(this::candidateFrom, Candidate::canonical)
    .counterexamples(this::findCounterexample)
    .evaluator(this::evaluateOnHoldout)
    .certificate(
        "MY_CERTIFICATE",
        Certificate::canonical,
        Certificate::canonical)
    .build();
```

Unvollständige Definitionen werden abgewiesen. Invariante,
Gegenbeispielsuche, Evaluator und Zertifikatsvertrag sind nicht optional. Die
vollständige, ausführbare Beispieldomäne liegt unter
`examples/external-consumers/geometric-sequence-domain-java25`.

## Lauf ausführen

```java
DiscoveryRun<Candidate, Certificate> run =
    RegelsucheDiscovery.forDomain(domain)
        .campaign("my-campaign")
        .seed("seed-1", payload, "student-example")
        .budget(DiscoveryBudgets.small())
        .run();
```

`DiscoveryRun` stellt Ergebniszustand, ausgewählten Kandidaten, Zertifikat,
Gegenbeispiele, verbrauchte Arbeit und kanonische Evidence getrennt bereit. Ein
zu kleines Budget endet `BUDGET_EXHAUSTED` ohne erfundenes Zertifikat; ein
widerlegter Kandidat endet `REFUTED`.

## Externe Provider

Eine Bibliothek registriert Domänen über `DiscoveryDomainProvider`:

```java
public final class MyProvider implements DiscoveryDomainProvider {
    public String id() {
        return "my-provider";
    }

    public Collection<DiscoveryDomain<?, ?, ?>> domains() {
        return List.of(MyDomain.domain());
    }
}
```

Der vollständig qualifizierte Providername steht in

```text
META-INF/services/de.regelsuche.sdk.discovery.DiscoveryDomainProvider
```

`RegelsucheDiscovery.loadDomains()` lädt den Katalog. Doppelte Provider-IDs und
doppelte Kombinationen aus Domain-ID und Revision werden abgewiesen. Die
Auffindbarkeit eines Providers ist keine Aussage über Artefaktvertrauen,
mathematische Korrektheit, Proof oder Promotion.

## Reproduktion

```bash
./gradlew --no-daemon --no-configuration-cache \
  verifyStudentJavaSdkConsumer
```

Die Prüfung veröffentlicht SDK und innere Laufzeitabhängigkeiten in ein
isoliertes Repository, baut und testet den externen Consumer, kontrolliert
Sources- und Javadoc-JARs, SHA-256-Werte sowie den Runtime-Abhängigkeitsbaum.

## Noch nicht enthalten

- ein öffentliches Maven-Central- oder GitHub-Packages-Release;
- Workbench-/CLI-Auswahl externer Provider;
- allgemeine Pareto- oder Optimalitätssuche;
- eine fertige mathematische Objektbibliothek;
- automatische Korrektheit beliebiger Erweiterungen.

Der erste Slice belegt eine kleine, dokumentierte und aus einem unabhängigen
Build verwendbare Java-Schnittstelle. Die weiteren Schritte bleiben in Issue
#904 getrennt nachvollziehbar.
