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

Weiterführend: [15-Minuten-Quickstart](java-sdk-quickstart.md), [Domain-Tutorial](java-sdk-domain-tutorial.md), [API-Vertrag und Release-Distributionsweg](java-sdk-api-policy.md), [typsichere Java-Integration](type-safe-java-integration.md).

## Abhängigkeit

Der Checkout veröffentlicht den ersten Slice als

```text
de.regelsuche:regelsuche-discovery-sdk:0.5.0-SNAPSHOT
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
    implementation "de.regelsuche:regelsuche-discovery-sdk:0.5.0-SNAPSHOT"
}
```

Die Checkout-Prüfung baut das Beispiel aus einer frischen Kopie außerhalb des
Multi-Projekts und mit einem leeren eigenen `GRADLE_USER_HOME`. Interne
Projektabhängigkeiten, ein globaler Dependency-Cache oder gleichnamige
Central-Artefakte können das checkout-eigene SDK deshalb nicht unbemerkt
ersetzen.

## Domäne definieren

Der Builder verdrahtet die portable `DiscoveryDomain`-Schnittstelle. Ein
domäneneigener Codec verbindet den fachlichen Java-Eingabetyp mit dem vorhandenen
Seed-Format; derselbe Codec wird im Generator und im typisierten Binding benutzt:

```java
DiscoveryInputCodec<Input> inputCodec = DiscoveryInputCodec.of(Input::payload, Input::parse);
var domain = DiscoveryDomainBuilder
    .<State, Candidate, Certificate>domain("my-domain", "v1")
    .generator(seed -> initialStates(inputCodec.decode(seed.payload())))
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
var typedDomain = TypedDiscoveryDomain.of(domain, inputCodec);
```

`Input`, `payload`, `parse` und `initialStates(Input)` gehören zur jeweiligen
Domänendefinition. Die Strings bei `invariant`, `operator` und `certificate`
benennen neu definierte Beiträge; die übergebenen Methoden bestimmen deren
Verhalten. Sie wählen keine versteckten Verfahren über String-Konstanten aus.

Unvollständige Definitionen und doppelte Operator- oder Invariantenidentitäten
werden abgewiesen. Invariante, Gegenbeispielsuche, Evaluator und
Zertifikatsvertrag sind nicht optional. Die vollständige, ausführbare
Beispieldomäne liegt unter
`examples/external-consumers/geometric-sequence-domain-java25`.

## Lauf ausführen

```java
DiscoveryRun<Candidate, Certificate> run =
    RegelsucheDiscovery.forDomain(typedDomain)
        .campaign("my-campaign")
        .seed("seed-1", input, "student-example")
        .budget(DiscoveryBudgets.small())
        .run();
```

`input` muss zum Eingabetyp der gewählten Domäne passen. Eine fremde
Eingabeklasse oder ein roher String-Payload kompiliert an diesem Einstieg nicht.
Die ursprüngliche `forDomain(domain).seed(id, payload, source)`-Form bleibt als
explizite Text-/Kompatibilitätsgrenze verfügbar. Der Codec erhält die bisherigen
Payloadbytes, wenn bestehende Evidence-Identitäten unverändert bleiben sollen.

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
Promotion. Die registrierte Domäne bindet Provider-ID, API-Revision und den SHA-256 der beobachteten Provider-Artefaktbytes in `sdk.provider.*`-Eigenschaften der Evidence. `forRegistration(...)` führt genau diese Domäne aus; eine neu konstruierte Instanz über `forDomain(...)` behauptet keine Providerregistrierung.

Ein dynamisch geladenes Providerregister ist eine explizite Textgrenze:
`forRegistration(...)` erhält weiterhin serialisierte Seeds. Das Folgenbeispiel
kodiert dort ein `Input`-Objekt mit dem domäneneigenen Codec und erhält die
host-beobachtete Registration. Statische Java-Consumer verwenden bevorzugt
`forDomain(GeometricSequenceDomainProvider.typedDomain())`.

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

- überschreibt kein vorhandenes Ziel, auch keinen symbolischen Link;
- validiert Java-Paket und stabile IDs vor dem Schreiben;
- erzeugt ein eigenständiges Java-25-Projekt mit gepinntem Gradle Wrapper;
- registriert den Provider über `META-INF/services`;
- enthält positive, widerlegte und budgeterschöpfte Tests mit
  `DiscoveryRunAssertions`;
- schreibt die gewählten Identitäten nach `regelsuche-starter.json`.

Auch `--package example` und `--package example.student` sind zulässig.
Gewählte IDs bleiben unverändert, selbst wenn sie Text der Vorlage enthalten.
Fehlende Wrapper-Dateien, eine falsche Distributions-URL oder eine fehlende,
kommentierte beziehungsweise ungültige SHA-256-Konfiguration werden vor dem
Anlegen des Zielverzeichnisses abgewiesen.

Nach Bereitstellung des SDK-Repositorys lässt sich das erzeugte Projekt ohne
Änderung und ohne separate Gradle-Installation bauen:

```bash
cd ../my-first-regelsuche-domain
./gradlew clean test run \
  -PregelsucheRepository=/pfad/zum/repository \
  -PregelsucheVersion=0.5.0-SNAPSHOT
```

Unter Windows wird `gradlew.bat` statt `./gradlew` verwendet. Java 25 und ein
bereitgestelltes SDK-Repository bleiben Voraussetzungen; beim ersten Build
werden Gradle und Fremdabhängigkeiten heruntergeladen.

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

Die schnellen Generator-Regressionstests sind über
`verifyStudentDiscoveryStarter` eine Voraussetzung derselben Consumer-Prüfung
und können auch ohne Java-Build ausgeführt werden:

```bash
python3 -B -m unittest discover -s scripts \
  -p 'test_student_discovery_starter.py' -v
```

Diese Dateisystemtests verwenden eine kleine Vorlage und Wrapper-Platzhalter.
Sie ersetzen nicht den separaten echten Java-25-Build des generierten Projekts.

## Noch nicht enthalten

- ein öffentliches Maven-Central- oder GitHub-Packages-Release;
- eine vollständige Attestation aller externen Provider-Abhängigkeiten;
- eine automatische Freigabe von externem Provider-Code;
- allgemeine Pareto- oder Optimalitätssuche;
- eine fertige mathematische Objektbibliothek;
- automatische Korrektheit beliebiger Erweiterungen.

Der erste Slice belegt eine kleine, dokumentierte und aus einem unabhängigen
Build verwendbare Java-Schnittstelle. Die weiteren Schritte bleiben in Issue
#904 getrennt nachvollziehbar.
