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

Ein vollständiges externes Neuheitsurteil hat eigene Anforderungen. [Issue #391](https://github.com/carstenartur/Regelsuche/issues/391)

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
