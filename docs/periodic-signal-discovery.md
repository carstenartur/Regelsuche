# Periodische Signale als externes Primachsenraum-Plugin

Die Signaldomäne des Prototyps aus PR #1072 wird in **Primachsenraum** gepflegt:

- [Plugin, Eingabeformat und Reproduktion](https://github.com/carstenartur/primachsenraum/blob/f55bcb90c5fb57c8bae3943acd9f487dfbb7d3e1/integrations/regelsuche-periodic-signals/README.md)
- [Mathematische Regeln, Ergebnisse und Grenzen](https://github.com/carstenartur/primachsenraum/blob/f55bcb90c5fb57c8bae3943acd9f487dfbb7d3e1/docs/research/periodic-signal-algebra.md)

Die Links sind auf den Merge-Commit von
[Primachsenraum #343](https://github.com/carstenartur/primachsenraum/pull/343)
festgelegt und bleiben auch nach Löschen des Feature-Branches gültig.
Primachsenraum ist ein privates Repository; zum Öffnen ist eine entsprechende
GitHub-Berechtigung nötig. Ein 404 ohne Berechtigung bedeutet nicht, dass das
Plugin oder die Dokumentation fehlt.

Regelsuche stellt den unveränderten generischen Discovery-SDK mit Suchkern,
Budgets, ServiceLoader-Vertrag und Evidence-Format bereit. Signalmodell,
Fourier-Auswertung, Produkt-/Faltungsregeln, Teilerklassen und unabhängiger
endlicher Checker gehören dem externen Plugin. Der Plugin-Build verwendet den
SDK-Stand `1f324b8f851464c66efd80117c77cbb9b1c3730c` und benötigt keine
Signalklassen aus Regelsuche.

Das Plugin registriert `primachsenraum-periodic-signals@v2` über
`de.regelsuche.sdk.discovery.DiscoveryDomainProvider`. Ein Host lädt das Plugin-JAR
mit seinen SDK-Abhängigkeiten und verwendet `DiscoveryDomainCatalog.load()` sowie
`RegelsucheDiscovery.forRegistration(...)`. Der komplette Startbefehl und ein
Java-Beispiel stehen in der verlinkten Plugin-Dokumentation; die frühere
Gradle-Aufgabe `:regelsuche-discovery-sdk:periodicSignalExample` entfällt.

Das Verfahren wählt auf Trainingsdaten zwischen drei bekannten exakten Plänen
und prüft den eingefrorenen Plan auf Holdout-Daten. **Es implementiert keine
Sparse FFT und keinen Primfaktorisierungsalgorithmus.** Korrektheit der
angefragten endlichen Koeffizienten, diagnostische Rechenkosten, tatsächliche
Laufzeit und mathematische Neuheit bleiben getrennte Aussagen.

Die Auslagerung ist mit Primachsenraum #343 und Regelsuche #1072 gemergt. Die
ursprünglichen Signalklassen waren zuvor nur Teil des ungemergten Prototyps;
eine veröffentlichte Signal-API wurde nicht entfernt. Die generische
Plugin-Schnittstelle blieb unverändert. Allgemeine Dokumentation:
[Java Discovery SDK](java-discovery-sdk.md).
