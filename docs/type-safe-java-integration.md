# Typsichere Java-Integration

Java-Aufrufer übergeben Verfahren als Objekte und strukturierte Eingaben als
fachliche Typen. Freie Namen, Erklärungen und Herkunftsangaben bleiben Texte.
Die neuen Einstiege ergänzen die bestehende API: Serialisierungsformate,
Suchkern, mathematische Prüfschritte und Evidence-Verträge werden nicht ersetzt.

## Rewrite-Programme ohne Verfahrenskennungen als Strings

```java
import static de.regelsuche.search.program.RewritePrograms.*;
import de.regelsuche.search.program.RewriteProgram;

RewriteProgram strategy = firstApplicable(
    source(macroEngine),
    sequence(source(normalizationEngine), source(factorizationEngine)),
    source(ordinaryRuleEngine)
).named("learn-or-derive");
```

Die Engine-Variablen sind `TransformationEngine`-Objekte. `source("factorization")`
ist kein zulässiger Aufruf. Die Varianten der bestehenden versiegelten
`RewriteProgram`-Hierarchie bestimmen das Verhalten. Es ist kein neues Enum
nötig, um frei ergänzbare Engines oder gelernte Regeln einzuschränken.

Die namenslosen Überladungen liefern einen unveränderlichen `RewritePrograms.Draft`.
`named(...)` erzeugt daraus die bestehenden IR-Records. Die Wurzel erhält die
angegebene Kennung, die Kinder erhalten Pfade wie `learn-or-derive/1/0`.
Wiederholtes Bauen derselben Struktur erzeugt dieselben IDs; mehrfach verwendete
Teilprogramme erhalten an verschiedenen Positionen verschiedene IDs. Weder
UUIDs noch globale Zähler oder Klassennamen sind beteiligt. Bei einer Änderung
der Baumstruktur dürfen sich positionsbasierte IDs ändern.

`choice`, `firstApplicable`, `sequence`, `repeat`, `require`, `prioritize`, `prune`
und `budgetedSource` unterstützen diese Bauweise. Budgetierte Quellen behalten
ihre gesonderte Ausführungsgrenze; eine neue Schreibweise macht sie nicht zu
gewöhnlichen unbudgetierten Quellen. Freie Beschreibungen für Filter, Sortierung
und Kürzung sind weiterhin menschlich lesbare Metadaten.

Die bisherigen Überladungen mit expliziten Knoten-IDs bleiben unverändert für
persistierte Pläne, Quellpositionen und Importadapter verfügbar. Sie werden nicht
nachträglich umnummeriert. Ein Draft ist keine zweite Suchimplementierung.

## Regeln über ihre Objekte auswählen

```java
var normalizationRule = registry.registerAndGet(myNormalizationRule);
var factorizationRule = registry.registerAndGet(myFactorizationRule);
var ordering = preferRules(normalizationRule, factorizationRule);
```

`registerAndGet` erhält den konkreten Java-Typ der Regel. `preferRules` akzeptiert
`RewriteRule`-Objekte als Varargs oder Liste, auch aus Plugins oder aus dem Lernen.
Die stabilen IDs werden einmal übernommen; fehlende, leere und doppelte
Identitäten werden abgewiesen. Nicht bevorzugte Kandidaten bleiben erhalten.
Eine Präferenz registriert oder aktiviert keine Regel und verleiht keine
mathematische Autorität.

Textimporte lösen eine externe Kennung ausdrücklich mit
`registry.requireRule(externalId)` auf. Ein unbekannter Name ist dabei ein
Fehler. Das Auflösen einer registrierten, deaktivierten Regel aktiviert sie
nicht. Das alte `preferRuleOrder(List<String>)` bleibt als kompatible
Niedrigstufen-Schnittstelle bestehen; es führt keine Registerprüfung aus und ist
nicht der bevorzugte Java-Einstieg.

## Discovery-Eingaben an die Domäne binden

Der Domänenautor definiert einen Eingabetyp und genau dessen Codec:

```java
DiscoveryInputCodec<Input> codec = DiscoveryInputCodec.of(Input::payload, Input::parse);
// Der Generator der bestehenden DiscoveryDomain verwendet ebenfalls diesen Codec:
// .generator(seed -> initialStates(codec.decode(seed.payload())))
TypedDiscoveryDomain<Input, State, Candidate, Certificate> typedDomain =
    TypedDiscoveryDomain.of(domain, codec);
```

Die Methoden `payload` und `parse` gehören zum jeweiligen Domänenmodell. Der
Codec muss deterministisch serialisieren und ungültige Felder beim Lesen
abweisen. Er ist keine allgemeine mathematische Validierung. Insbesondere ist
eine lesbare Zustandsdarstellung nicht automatisch ein parsebares Seed-Format.
Der Domänenautor ist dafür verantwortlich, dass Generator und Binding denselben
Codec und dieselbe Bedeutung des Payloads verwenden.

```java
var run = RegelsucheDiscovery.forDomain(typedDomain)
    .campaign("my-campaign")
    .seed("seed-1", input, "student-example")
    .budget(DiscoveryBudgets.small())
    .run();
```

`input` hat den Typ `Input`. Der zurückgegebene `TypedRequest` ist nicht von der
untypisierten Request-Klasse abgeleitet und hat weder einen `Object`-Ausweg noch
eine Überladung für rohe Seeds oder String-Payloads. Eine fremde Eingabeklasse
wird schon beim Kompilieren abgelehnt. Eine Domäne kann ausdrücklich `String`
als fachlichen Eingabetyp wählen; das ist dann ihr deklarierter Vertrag.

Beim Setzen des Seeds wird das Eingabeobjekt serialisiert. Dadurch ist der
Request nicht von späteren Änderungen eines veränderlichen Eingabeobjekts
abhängig. Ausführung und Replay laufen weiter durch dieselbe bestehende Fassade.

Das Folgenbeispiel verwendet `GeometricSequenceDomainProvider.typedDomain()`
und den Record `Input(List<Long> observed, List<Long> holdout, int maxMultiplier)`.
Sein Codec erhält das bisherige Seed-Format und den Standardwert 8 bei fehlendem
`maxMultiplier`; unbekannte Felder wie `maxMultipler`, doppelte Schlüssel,
Felder ohne Gleichheitszeichen und abgeschnittene Zahlenlisten sind Fehler.
Die kanonischen Zustands- und Zertifikatdarstellungen bleiben unverändert.

## Dynamische Provider und Kompatibilität

Ein über `ServiceLoader` geladener Katalog kennt die Java-Eingabetypen beliebiger
Plugins nicht statisch. `forRegistration(...)` bleibt deshalb eine ausdrückliche
Textgrenze. Das ausführbare Folgenbeispiel erzeugt auch dort ein `Input`-Objekt
und kodiert es erst an dieser Grenze. Es behält die tatsächlich geladene
Registration und deren host-beobachtete Artefaktprovenienz; eine neu konstruierte
Domäne wird nicht als registrierter Provider ausgegeben.

Vorhandene `DiscoveryDomain<S,C,K>`-Implementierungen und gespeicherte Seeds
benötigen keine Migration. Es gibt keine unchecked Casts, keinen parallelen
Runner und keine automatische Freigabe externer Provider. Fachliche Gültigkeit,
Anwendbarkeit, Budgetgrenzen und mathematische Bestätigung bleiben Laufzeitprüfungen.

## Regressionstests

`TypedDiscoveryCompilationTest` und `RewriteProgramsCompilationTest` übersetzen
positive Consumer-Fixtures mit dem echten JDK-Compiler und prüfen anschließend,
dass fremde Eingabeobjekte beziehungsweise String-Verfahrensauswahlen an
Typfehlern scheitern. Fehlende Abhängigkeiten dürfen negative Tests nicht
fälschlich erfolgreich machen. Normale Modultests prüfen Struktur-IDs,
Regelpriorisierung, Registerauflösung und die Gleichheit der Evidence zwischen
typisiertem und bisherigem Seed-Einstieg. Der externe Folgen-Consumer prüft
zusätzlich Bestätigung, Widerlegung, Budgeterschöpfung und strikten Import.

```bash
./gradlew :regelsuche-discovery-sdk:test :regelsuche-search:test
./gradlew :app:test --tests de.regelsuche.plugin.RuleRegistryTypedSelectionTest
./gradlew --no-daemon --no-configuration-cache verifyStudentJavaSdkConsumer
./gradlew --no-configuration-cache ciCheck
```

Die gezielten Tests ersetzen nicht den vollständigen Checkout-/Consumer-Build.
Für den Projektbuild wird weiterhin JDK 25 benötigt.
