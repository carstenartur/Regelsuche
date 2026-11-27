# Umsetzung: spektrale Reparatur, Matrixzentren und Plan-Komposition

Grundlage ist die [Spezifikation](../specs/2026-11-27-spectral-matrix-composition.md).
Die drei mathematischen Komponenten werden getrennt implementiert und an
ihren gemeinsamen API-, Prüf- und Budgetgrenzen zusammengeführt.

## Arbeitspakete

- [x] Matrixzentren: affine Differenzrealisierung mit vollständigem
  Matrixeinheitenalphabet, erreichbarer Raum, unabhängige Invarianzprüfung,
  originale Gegenzeugenauswertung und begrenzte generische Zentrumssuche.
- [x] Spektraler Batch: rationale Eigenbasis, wechselnde Ausnahmeträger,
  Hermite-Reparaturtabellen, unabhängige Prüfung und ausführbarer Batchplan
  mit fairen Direktkosten und vollständiger Speicherreservierung.
- [x] Komposition: U/V/W als echte Operatorunterprobleme, Residual- und
  Spektralprovider, unabhängiger Gesamtprüfer, vorbereitete Ausführung und
  ein gemeinsamer Etat einschließlich fehlgeschlagener Unterversuche.
- [x] Integration: externe SDK-Beispiele, Dokumentation, vollständige Tests
  der betroffenen Module und Vorbereitung des prüfbaren Feature-PR.

## Prüfstrategie

Negative Kontrollen verändern Quelle, Zentrum, Träger, Reparaturtabelle oder
Stufenbeleg. Unabhängige direkte Rechnungen prüfen rationale Werte und
Koordinatenbasis-Eingaben. Ressourcenprüfungen treffen exakte Grenzen, einen
um eins zu kleinen Etat, Abbruch, verworfene Kandidaten und gleichzeitig
gehaltene vorbereitete Pläne. Ein Matrixpolynom, das auf Ordnung zwei
verschwindet, aber auf Ordnung drei nicht, schützt den universellen Anspruch.

Die drei Komponenten erhalten getrennte Reviews. Danach werden die gesamte
Abhängigkeitsclosure des SDK und das unabhängige Consumer-Projekt geprüft.
Alle neuen und geänderten Produktions- und Testklassen werden zusätzlich mit
`-Xlint:all -Werror` kompiliert. Quellverweise im Strategien-Index werden
mit dessen vorhandenem Generator aktualisiert und geprüft.
