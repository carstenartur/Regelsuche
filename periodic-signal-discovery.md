# Periodische Signale als externes Primachsenraum-Plugin

Die Signaldomäne des Prototyps aus PR #1072 wird in **Primachsenraum** gepflegt:

- [Plugin, Eingabeformat und Reproduktion](https://github.com/carstenartur/primachsenraum/tree/codex/periodic-signal-plugin/integrations/regelsuche-periodic-signals)
- [Mathematische Regeln, Ergebnisse und Grenzen](https://github.com/carstenartur/primachsenraum/blob/codex/periodic-signal-plugin/docs/research/periodic-signal-algebra.md)

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

Die Änderung war noch nicht gemergt oder veröffentlicht; deshalb ist keine
veraltete Signal-API im generischen SDK nötig. Die Plugin-Schnittstelle bleibt
unverändert. Allgemeine Dokumentation: [Java Discovery SDK](java-discovery-sdk.md).
