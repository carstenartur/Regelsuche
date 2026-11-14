# Literaturreferenzen und Prüfung bekannter Vorarbeit

Regelsuche unterscheidet die Herkunft einer Regel, eine in einem Schritt verwendete
Quelle und einen Literaturfund nach der Herleitung. Die Literaturdaten werden separat
versioniert. Die Prüfsummen der gespeicherten Herleitung und der Beweisartefakte
bleiben auch bei einer späteren Zuordnung einer Fundstelle gültig.

## Quellen im Replay und Export

Mit `REGELSUCHE_LITERATURE_CATALOG=/pfad/zum/katalog.json` oder der JVM-Eigenschaft
`regelsuche.literature.catalog` lädt Regelsuche beim Start eine lokale Datei.
Das Replay zeigt die freigegebenen Bezüge am Schritt. Die Markdown- und
LaTeX-Exporte nummerieren die verwendeten Fundstellen und führen sie einmal auf.
Nur Zuordnungen mit `"released": true` erscheinen im Replay und den Exporten.

Der Replay-Endpunkt `GET /api/paths/{id}/replay` liefert den `ruleId` und
`literatureOccurrence` eines Schritts. Eindeutige deklarative Regeln aus dem
AST-Engine-Inventar tragen außerdem eine `ruleRevision`. Eine Quellenangabe für
diese Revision kann über mehrere Herleitungen hinweg verwendet werden.
Ein expliziter `occurrence` bindet die Zuordnung an genau einen Schritt und
funktioniert auch für ältere Herleitungen.

Beispiel für eine nachträgliche Zuordnung. Die markierten Werte stammen aus
dem konkreten Replay und der Prüfung der Fundstelle.

```json
{
  "schema": "regelsuche.rule-literature/v1",
  "sources": [{
    "id": "cassini-proofwiki",
    "authors": ["ProofWiki contributors"],
    "title": "Cassini's Identity",
    "year": null,
    "identifier": "ProofWiki:Cassini's Identity",
    "version": "oldid=667858",
    "locator": "Theorem and Proof 1",
    "url": "https://proofwiki.org/w/index.php?title=Cassini%27s_Identity&oldid=667858"
  }],
  "links": [{
    "ruleId": "<ruleId aus dem Replay>",
    "ruleRevision": null,
    "occurrence": "<literatureOccurrence aus dem Replay>",
    "sourceId": "cassini-proofwiki",
    "relation": "EQUIVALENT",
    "role": "FOUND_AFTER_FREEZE",
    "assumptions": ["Fibonacci-Rekurrenz mit F_0 = 0 und F_1 = 1", "n ≥ 0"],
    "reviewedBy": "<prüfende Person>",
    "reviewedOn": "<JJJJ-MM-TT>",
    "rationale": "Die Form mit F_n F_(n+2) folgt mit um eins verschobenem Index.",
    "released": false
  }]
}
```

Die Rollen `USED_AS_LEMMA` und `FOUND_AFTER_FREEZE` erfordern eine Schrittbindung.
Für eine `RULE_SOURCE` genügt die exakte Regelrevision. Neben `EXACT`,
`EQUIVALENT` und `RELATED` geben `SOURCE_SPECIAL_CASE` und
`SOURCE_GENERALIZATION` die Richtung des Bezugs aus Sicht der Quelle an.
Für einen Aufsatz kann die Fundstelle einen DOI oder eine versionierte arXiv-ID
mit Satznummer enthalten. Für Mathlib eignen sich ein Commit und der
vollständig qualifizierte Name des Lemmas.

## Private Prüfung am gespeicherten Kandidaten

Im Kandidatendossier eines gespeicherten Laufs lässt sich **Private Literaturprüfung**
öffnen. Sie steht auf einer an Loopback gebundenen Workbench oder mit
konfigurierter Workbench-Authentifizierung zur Verfügung. Die Ansicht unterstützt
den gebundenen Brückenkandidaten und gespeicherte transformierte Zustände des
nativen Laufs.

Die Bewertungsvorlage enthält die exakte Run-ID, Kandidaten-ID und Prüfsumme des
gebundenen Artefakts. Ergänze Prüfer, Datum, Bestände samt Fassung oder Datum,
Suchanfragen sowie die geprüften Fundstellen und importiere die Datei im Dossier.
`matches` enthält pro Fundstelle ein `source`-Objekt wie im Quellenkatalog,
`relation`, `assumptions` und `rationale`. Eine Verallgemeinerung zählt als
bekannte Vorarbeit, wenn `entailmentRationale` herleitet, wie der Kandidat aus
der Quelle folgt.

| Status | Bedeutung |
| --- | --- |
| `PRIOR_ART_FOUND` | Eine geprüfte Fundstelle trägt die Aussage. |
| `NO_PRIOR_ART_FOUND_WITHIN_SCOPE` | Im festgehaltenen Suchumfang wurde keine Vorarbeit gefunden. |
| `UNRESOLVED` | Die Prüfung bleibt offen. |

Die aktuelle Vorlage (`regelsuche.literature-assessment/v2`) erfasst für jeden
Bestand den Zugangsstatus `SEARCHED`, `PARTIAL` oder `INACCESSIBLE`, die
konkreten Suchgrenzen (`limitations`) und eine Liste offener Treffer
(`unresolvedHits`). Ein offener Treffer enthält seine Fundkennung (`identifier`)
und den Grund (`reason`), etwa den fehlenden Zugang zum vollständigen Satz.
Jede geprüfte Fundstelle in `matches` verweist mit `scopeIndex` auf den
zugehörigen zugänglichen Bestand. Die Zählung beginnt bei 0.
Eine ergebnislose Suche kann nur als `NO_PRIOR_ART_FOUND_WITHIN_SCOPE` gelten,
wenn alle angegebenen Bestände durchsucht wurden, alle Treffer geklärt sind
und keine einschlägige Vorarbeit belegt ist. Bei vollständig fehlendem Zugang
kann eine offene Bewertung auch ohne ausgeführte Suchanfrage gespeichert werden.
Eine bestätigte Vorarbeit in einem zugänglichen Bestand lässt sich auch bei
Lücken in weiteren Beständen dokumentieren. Historische v1-Bewertungen behalten
ihre eigene Fassung und Inhaltsidentität.

### Einen eingefrorenen Literaturbefund unabhängig nachprüfen

Die Java-Schnittstelle `de.regelsuche.literature.ExternalNoveltyReview` prüft private Gutachterpakete für einzelne skalare Gleichungen. Sie liest keine externen Dienste und veröffentlicht keine Suchanfrage. Das vorhandene v2-Dossier mit Quellen, Annahmen, Relationen, offenen Treffern und Zugangsproblemen bleibt Bestandteil des Pakets. Die bestehende Workbench-Literatursuche bleibt freiwillig; das neue Format ist eine separate Java-Schnittstelle.

1. **Vor der Recherche** `Plan` an die drei Identitäten des gespeicherten Kandidaten (`Subject`), die unveränderte Gleichung, die Annahmen, die Hashes der Formation und des Zugriffsprotokolls und den Zeitpunkt des Einfrierens binden. Den Hash aus `freeze(plan)` und den Hash des versionierten Quellenkatalogs außerhalb des später importierten Pakets aufbewahren. Der Katalog beschreibt pro Bestand Fassung, Datum, Möglichkeiten, Zugangsbedingungen und Grenzen. `scalarCatalogue()` lädt ein reproduzierbares Ausgangsprofil für DLMF 1.2.8 und eine feste mathlib-Revision. Weitere einschlägige Bestände kann die prüfende Stelle vor dem ersten Suchlauf ergänzen und den gewählten Katalog neu einfrieren.
2. `NoveltyQueryForms.forEquation(equation, assumptions)` erzeugt das Original, eine systematische Variablenumbenennung, eine Strukturbeschreibung, vertauschte Gleichungsseiten und Klassifikationsterme. Dazu kommen die angegebenen Annahmen und, soweit darstellbar, die exakte Polynomnormalform. Das Profil ist auf einzelne Gleichungen von höchstens 128 Zeichen begrenzt. Es verspricht keine vollständige Suche nach mathematisch äquivalenten Aussagen. Für jeden durchsuchten Bestand Suchfamilie, erzeugten Text, tatsächlich eingegebene quellspezifische Anfrage, Zeitpunkt, Ergebniszahl und beobachtete Trefferkennungen festhalten. Sind von einer Trefferliste nur Teilseiten zugänglich, `PARTIAL` verwenden. Im v2-Dossier muss jede davon beim richtigen Bestand entweder mit einer inspizierten Quelle oder als offener Treffer erklärt sein.
3. Den Hash aus `packetRoot(packet)` an die Begutachtung geben. Ein Votum bindet das gesamte Paket, die pseudonyme Person, die Hashes ihrer Qualifikations- und Konfliktprüfung sowie Entscheidung und Begründung. Signiert werden die Bytes aus `signingBytes(opinion)` mit Ed25519. Die empfangende Stelle liefert eine **eigene** Liste aller für das Paket zugeteilten, unabhängig geprüften Personen mit ihren öffentlichen Schlüsseln und den beiden Nachweisen. Schlüssel und Zuordnungen aus dem eingereichten Paket sind keine Autorität.
4. `replay(expectedFreeze, expectedCatalogue, assignedReviewers, bytes)` rekonstruiert die Entscheidung. `artifacts(...)` liefert sieben benannte private JSON-Dateien: Plan, Katalog, Suchprotokoll, Trefferdossier, Eingang der Gutachten, Entscheidung und Gesamtprotokoll. Das Ergebnis erteilt weder einen mathematischen Beweis noch Promotion oder Veröffentlichungsfreigabe.

Bestätigte Vorarbeit bleibt Vorarbeit. Bei fehlendem Zugang oder ungeklärtem Treffer bleibt die Neuheit ungeklärt; eine vollständige Suche ohne einschlägigen Treffer ist zunächst nur ein Befund innerhalb des Katalogumfangs. Die positive Protokollentscheidung benötigt mindestens zwei gültige Zustimmungen und die vollständige zugeteilte Gruppe. Die Prüfstelle muss die Identitäten, die Unabhängigkeit, Zugriffsrechte und Echtheit der vorab festgehaltenen Wurzeln selbst feststellen. Zeitstempel allein beweisen keine Geheimhaltung. Automatisierte Tests verwenden ausschließlich synthetische Quellen und Testschlüssel.

## Freiwilliger Abruf über TheoremSearch

Der externe Abruf ist standardmäßig ausgeschaltet. Auf einer privaten Workbench
aktiviert ihn `REGELSUCHE_THEOREMSEARCH_ENABLED=true` oder
`-Dregelsuche.literature.theoremsearch.enabled=true` beim Start. Der Schritt
**Anfrage prüfen** zeigt den genauen Suchtext. Erst **An TheoremSearch senden**
überträgt ihn zusammen mit der festen Ergebnisgrenze von fünf Treffern.
TheoremSearch protokolliert Suchtexte. [Datenschutzhinweise](https://www.theoremsearch.com/privacy)

Die Workbench begrenzt den Abruf auf eine gleichzeitige Anfrage und fünf
Anfragen pro laufender Instanz und Stunde. Ein `Retry-After` verschiebt den
nächsten zulässigen Abruf. Automatische Tests verwenden einen lokalen HTTP-Server.

Abrufprotokolle und importierte Bewertungen liegen in den Verzeichnissen
`<run-verzeichnis>-literature` und `<run-verzeichnis>-literature-assessments`
neben dem Run-Verzeichnis. Sie enthalten den exakten Suchtext bzw. das geprüfte
Quellenmaterial und gehören in den privaten Arbeitsbereich. Die UI zeigt
Treffer als ungeprüft. Die freigegebenen Zuordnungen im Quellenkatalog steuern
unabhängig davon die zitierbaren Bezüge in öffentlichen Exporten.
