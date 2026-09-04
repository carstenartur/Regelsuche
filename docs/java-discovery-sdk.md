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
benötigt Java 25. Die Regelsuche-Gruppe sollte exklusiv aus diesem Repository
bezogen werden, während Fremdabhängigkeiten weiterhin aus Maven Central kommen:

```gradle
repositories {
    exclusiveContent {
        forRepository {
            maven { url = uri(regelsucheRepository) }
        }
        filter { includeGroup "de.regelsuche" }
    }
    mavenCentral()
}

dependencies {
    implementation "de.regelsuche:regelsuche-discovery-sdk:0.4.0-SNAPSHOT"
}
```

Die Checkout-Prüfung baut das Beispiel aus einer frischen Kopie außerhalb des
Multi-Projekts und mit einem leeren eigenen `GRADLE_USER_HOME`. Interne
Projektabhängigkeiten, ein globaler Dependency-Cache oder gleichnamige
Central-Artefakte können das checkout-eigene SDK deshalb nicht unbemerkt
ersetzen.

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

Unvollständige Definitionen und doppelte Operator- oder Invariantenidentitäten
werden abgewiesen. Invariante, Gegenbeispielsuche, Evaluator und
Zertifikatsvertrag sind nicht optional. Die vollständige, ausführbare
Beispieldomäne liegt unter
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
widerlegter Kandidat endet `REFUTED`. Die öffentliche API bietet keinen
`DiscoveryRun`-Konstruktor; gewöhnliche Consumer erhalten Läufe über die
SDK-Fassade. Das ist keine Sicherheitsgrenze gegen Reflection oder absichtlich
erzeugte Split Packages. Maßgeblich bleiben die kanonische Evidence und ihre
unabhängige Prüfung.

## Ergebnisse verständlich prüfen

`DiscoveryRunAssertions` bietet eine kleine, von JUnit unabhängige
Assertionsschicht. Sie prüft ausschließlich die öffentliche Ergebnissicht und
wirft bei einer Abweichung einen `AssertionError` mit dem Evidence-Hash. Sie
verändert weder den Status noch die mathematische Bedeutung eines Laufs.

```java
import static de.regelsuche.sdk.discovery.DiscoveryRunAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

assertThat(run)
    .isConfirmed()
    .hasCounterexampleContaining("multiplier 1")
    .candidateSatisfies(candidate ->
        assertEquals(2, candidate.multiplier()))
    .certificateSatisfies(certificate ->
        assertEquals(2, certificate.multiplier()))
    .hasContentAddressedEvidence();
```

Die terminalen Zustände bleiben ausdrücklich verschieden:

```java
assertThat(refutedRun).isRefuted();
assertThat(inconclusiveRun).isInconclusive();
assertThat(shortRun).isBudgetExhausted();
```

`isConfirmed()` verlangt Kandidat und Zertifikat. Alle nicht bestätigten
Statusmethoden verlangen, dass keine ausgewählten Objekte veröffentlicht
werden. Domänenspezifische Aussagen bleiben normale Java-Assertions innerhalb
von `candidateSatisfies(...)` beziehungsweise `certificateSatisfies(...)`.
Weitere Prüfungen decken Gegenbeispielanzahl, ausgeführte Ressourcen,
Übergangsannahmen und die content-addressed Evidence ab.

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

`RegelsucheDiscovery.loadDomains()` lädt den Katalog. Doppelte Provider-IDs,
doppelte Kombinationen aus Domain-ID und Revision sowie mehrdeutige
Komponentenidentitäten werden abgewiesen. Die Auffindbarkeit eines Providers ist
keine Aussage über Artefaktvertrauen, mathematische Korrektheit, Proof oder
Promotion. Die Einbettung der Provider-Artefaktprovenienz in jede kanonische
Run-Evidence bleibt eine getrennte Restarbeit aus #904.

## Eigenständiges Starterprojekt erzeugen

Der Checkout enthält einen kleinen Generator, der das tatsächlich in der
Consumer-CI gebaute Beispiel als Vorlage verwendet:

```bash
python3 scripts/create-student-discovery-domain.py \
  --output ../my-first-regelsuche-domain \
  --package org.example.discovery \
  --project-name my-first-regelsuche-domain \
  --domain-id my-first-domain \
  --provider-id my-first-provider
```

Der Generator

- überschreibt niemals ein vorhandenes Ziel;
- validiert Java-Paket und stabile IDs;
- erzeugt ein eigenständiges Java-25-Gradle-Projekt;
- registriert den Provider über `META-INF/services`;
- enthält positive, widerlegte und budgeterschöpfte Tests mit
  `DiscoveryRunAssertions`;
- schreibt die gewählten Identitäten nach `regelsuche-starter.json`.

Nach Bereitstellung des SDK-Repositorys lässt sich das erzeugte Projekt ohne
Änderung bauen:

```bash
cd ../my-first-regelsuche-domain
gradle clean test run \
  -PregelsucheRepository=/pfad/zum/repository \
  -PregelsucheVersion=0.4.0-SNAPSHOT
```

Die autoritative Consumer-CI erzeugt zusätzlich ein Projekt mit von der Vorlage
abweichenden Paket-, Domain- und Provider-IDs, startet es mit einem eigenen
leeren Gradle-Cache und verweigert interne Projektabhängigkeiten sowie
unerwünschte Runtime-Module.

## Reproduktion

```bash
./gradlew --no-daemon --no-configuration-cache \
  verifyStudentJavaSdkConsumer
```

Die Prüfung veröffentlicht SDK und innere Laufzeitabhängigkeiten in ein
isoliertes Repository, baut und testet den externen Consumer sowie einen frisch
erzeugten Starter mit getrennten isolierten Dependency-Caches, kontrolliert
Sources- und Javadoc-JARs, SHA-256-Werte sowie die Runtime-Abhängigkeitsbäume.

## Noch nicht enthalten

- ein öffentliches Maven-Central- oder GitHub-Packages-Release;
- content-addressed Provider-Artefaktprovenienz in jeder Run-Evidence;
- Workbench-/CLI-Auswahl externer Provider;
- allgemeine Pareto- oder Optimalitätssuche;
- eine fertige mathematische Objektbibliothek;
- automatische Korrektheit beliebiger Erweiterungen.

Der erste Slice belegt eine kleine, dokumentierte und aus einem unabhängigen
Build verwendbare Java-Schnittstelle. Die weiteren Schritte bleiben in Issue
#904 getrennt nachvollziehbar.
