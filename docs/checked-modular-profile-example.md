# Geprüfte gemeinsame Rechenpläne als externes SDK Beispiel

Dieses Entwicklerbeispiel zeigt, wie eine externe Domäne mit Regelsuche
verwandte Berechnungen zu einem geprüften gemeinsamen Rechenplan verbindet.
Die vollständige Implementierung und Untersuchung gehören Primachsenraum;
Regelsuche stellt den vorhandenen Such-, Plan- und Prüfablauf bereit.
Stand: 18. Oktober 2026, begrenzter experimenteller SDK-Anwendungsfall.

## Aufgabe und wiederverwendbarer Zustand

Für disjunkte Offsetmengen `A`, `B`, `C`, `D` werden mehrere Vereinigungen
unter zwei Moduli untersucht. Gesucht ist jeweils die Zahl belegter
Restklassen. Die einzelne Belegungszahl reicht für spätere Löschungen nicht:
Verschiedene Offsets können dieselbe Restklasse belegen. Der Adapter hält
deshalb zusätzlich die Multiplizität jeder Restklasse fest.

Bei disjunkten Mengen gilt `h_p(A ∪ B) = h_p(A) + h_p(B)` für das Histogramm
`h_p`. Ein gemeinsamer Plan kann `A ∪ B` einmal aufbauen, für `A ∪ B ∪ C`
nur `C` hinzufügen und für `A ∪ C` anschließend `B` entfernen. Die Belegung
wird bei diesen Änderungen mitgeführt. Das setzt geprüfte Mitgliedschaft und
globale Disjunktheit voraus, nicht bloß erfolgreiche numerische Beispiele.

## Zuständigkeit und Prüfablauf

Der externe `ModularProfileOptimizer` implementiert die vorhandenen Verträge
`ComputationBackend` und `JointPlanSearch.Domain`. Er liefert Operatorsemantik,
Kandidaten und einen vom numerischen Executor unabhängigen symbolischen Prüfer.
Der generische Suchlauf kombiniert Kandidaten unter einem Arbeitsbudget;
`prepareVerified` prüft den ausgewählten Plan erneut vor wiederholter Ausführung.

Der externe Java-Lowerer übersetzt ausschließlich erneut geprüfte Pläne. Er
berücksichtigt lebende Arrayzweige, separat gespeicherte Skalarwerte und die
bewiesenen Wertebereiche. Die Eingabeguards bleiben bei jedem Aufruf aktiv.
Die Wiederverwendung liegt innerhalb einer Gruppe verwandter Abfragen, nicht
in einem zustandsbehafteten Siebstrom über mehrere Aufrufe hinweg.

Ein optionales Portfolio enthält vier explizite Vorlagen für direkte Belegung,
gemeinsame Blatthistogramme und zwei Aktualisierungsreihenfolgen sowie höchstens
einen zusätzlichen Suchplan. Seine Formauswahl löst keine Suche oder
Kompilierung pro Eingabe aus. Diese Vorlagen sind vorgegeben, nicht gelernt.

Die Plan-/Backend-Typen dieses Beispiels beziehen sich auf den gepinnten
SDK-Stand `1f324b8f851464c66efd80117c77cbb9b1c3730c`; daraus entsteht keine
neue stabile SDK-API. Histogrammoperatoren, spezielle Eingabegrenzen, Prüfer,
Kostenannahmen und Java-Codegenerator bleiben im äußeren Adapter.

## Nachvollziehen und reproduzieren

Der abgelegte Untersuchungsstand ist unveränderlich verlinkt:

- [Modulvertrag und SDK-Bereitstellung](https://github.com/carstenartur/primachsenraum/blob/8977d54ab006bc4a440c5ef7552284deb66e062d/integrations/regelsuche-periodic-signals/README.md)
- [Ausführbarer Domain-Adapter](https://github.com/carstenartur/primachsenraum/blob/8977d54ab006bc4a440c5ef7552284deb66e062d/integrations/regelsuche-periodic-signals/src/main/java/org/primachsenraum/periodic/ModularProfileOptimizer.java)
- [Untersuchung mit vollständigen Vergleichs- und Referenzdaten](https://github.com/carstenartur/primachsenraum/blob/8977d54ab006bc4a440c5ef7552284deb66e062d/docs/research/modular-profile-java-execution.md)

Primachsenraum ist privat. Die Links benötigen dort eine GitHub-Berechtigung;
ein 404 ohne Berechtigung ist keine Aussage über das Vorhandensein der Dateien.
Die allgemeine SDK-Benutzung erfordert keinen Zugriff auf dieses Repository.

Nach Bereitstellung des SDK-Repositorys gemäß Modulvertrag, im Primachsenraum-
Checkout dieses Untersuchungsstands, mit Java 25 und Gradle 9.7.0:

```bash
gradle -p integrations/regelsuche-periodic-signals \
  -PregelsucheRepository="$PWD/build/periodic-sdk-repository" \
  clean test periodicSignalExample

gradle -p integrations/regelsuche-periodic-signals \
  -PregelsucheRepository="$PWD/build/periodic-sdk-repository" \
  -PprofileCase=balanced modularProfilePortfolioExample
```

Der JUnit-Lauf prüft die Implementierung. Die Beispiele liefern unter anderem
ausgeführte Pläne, erzeugten Java-Code, Kostenbilanzen und Vergleichsmessungen
auf stdout. Die vollständigen historischen Messreihen bleiben in Primachsenraum;
es gibt hier keine zweite Kopie ihrer Datensätze oder einen neuen Workflow.

## Welche Aussage das Beispiel trägt

Der Pilot belegt begrenzte Komposition bereitgestellter Regeln und überprüfte
Ausführung, nicht selbständiges Entdecken der Update-Operatoren. In allen elf
Formen der abgelegten Portfolio-Vergleichsreihe wird eine vorgegebene Vorlage
gewählt, niemals der zusätzliche Suchplan. Die dortigen Laufzeitverbesserungen
sind deshalb kein Nachweis zusätzlichen Suchnutzens gegenüber diesen Vorlagen.
Das Kostenmodell ist keine kalibrierte Laufzeitvorhersage; die vollständige
Studie erhält auch die langsameren Fälle und Vorbereitungskosten.

Ein allgemeiner Nutzen der Suche wäre erst mit einem echten Verbraucher und
neuen Aufgaben zu zeigen: dieselben starken Vorlagen ohne Suche gegen Vorlagen
plus Suche, einschließlich Vorbereitung, Prüfung, Ausführung und Speicherbedarf.
Eine Übernahme allgemeiner Analysemechanismen in den Kern braucht außerdem
einen zweiten Anwendungsfall ohne Restklassen-Sonderwissen. Weder Sparse FFT,
Primfaktorisierung noch neue Primzahlzusammenhänge sind hier nachgewiesen.

Weiterführend: [Java Discovery SDK](java-discovery-sdk.md),
[SDK-API-Policy](java-sdk-api-policy.md) und
[ursprüngliches Signalplugin](periodic-signal-discovery.md).
