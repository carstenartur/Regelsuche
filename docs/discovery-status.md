# Aktueller Discovery-Stand

Stand: 14. Juli 2026

Diese Seite fasst den gemessenen Forschungsstand von Regelsuche zusammen. Sie trennt technische Suchverbesserungen, Rediscovery, projektinterne Open-Target-Hypothesen und mögliche externe mathematische Neuheit.

## Kurzfassung

Regelsuche besitzt inzwischen eine durch Tests und CI abgesicherte Kette von der Suche bis zur kontrollierten Promotion eines Open-Target-Kandidaten:

1. untargetete Suchgraphen und konvergente Pfade,
2. Generalisierung zu einer parametrisierten Hypothese,
3. Kompilierung als quarantänisierter ausführbarer Operator,
4. frische positive und negative Holdouts,
5. deterministische Gegenbeispielsuche,
6. projektinterne Exact-/Alpha-Neuheitsprüfung,
7. versionierte symbolische Proof-Obligation,
8. Übergabe an den bestehenden `HypothesisCandidate`-Lebenszyklus,
9. getrennte Promotion- und Public-Evidence-Entscheidung mit Proof-Policy, Ablation und vollständiger Provenienz.

Die vollständige Einzelkandidaten-Kette aus #221 ist damit implementiert. Cross-Family-Bridge-Clustering wurde mit #319 begonnen und wird in #222 um Bridge-Hypothesen und familienweise Held-out-Validierung erweitert.

## Gemessene Ergebnisse

| Stufe | Ergebnis | Bedeutung | Keine daraus folgende Behauptung |
| --- | --- | --- | --- |
| Zielgerichtete Suchsteuerung (#219) | TEST von 7 auf 5 erkundete Zustände, also 28,5 % Verbesserung ohne Korrektheitsverlust | Erklärbare TRAIN-Evidenz beeinflusst die reale Frontier-Priorität | Noch keine mathematische Entdeckung |
| Hidden-Rule-Rediscovery (#227) | 19 von 20 akzeptierte ausführbare Rediscoveries über 4 Familien; 95 %; 0 False Positives unter 38 ausgeführten Negativ-Holdouts; 2 Prüfungen explizit übersprungen | Die Discovery-Kette kann bekannte, aus dem Inventar entfernte Regeln aus atomaren Pfaden wiederaufbauen | Keine externe Neuheit; die Referenzregeln waren post-hoc bekannt |
| Open-Target-Formation (#311) | Parametrisierte Hypothese aus mehreren alpha-distinkten, untargeteten Konvergenzbeobachtungen | Kandidatenbildung funktioniert ohne Zielausdruck oder versteckte erwartete Antwort | Noch keine Wahrheit, Novelty oder Promotion |
| Falsifikation und Holdouts (#313) | Ausführbarer Kandidat, vollständige Prüfungsbilanz und sichtbare Ablehnung einer überbreiten Hypothese | Kandidaten werden nach ihrer Bildung aktiv angegriffen | `NO_COUNTEREXAMPLE_FOUND` ist kein formaler Beweis |
| Projektinterne Novelty (#314) | Exact- und Alpha-Vergleich gegen aktives Inventar und frühere Campaigns | Interne Neuheit wird getrennt und reproduzierbar geprüft | Keine Aussage über die mathematische Literatur |
| Symbolische Prüfung (#315) | Versionierte Proof-Obligation und isoliertes Symbolic-Backend-Ergebnis | Wahrheitsevidenz ist von Mining und Ranking getrennt | Kein formaler Theorem-Prover-Nachweis, sofern nicht explizit ausgewiesen |
| Lifecycle-Handoff (#316) | Vollständige akzeptierte Open-Target-Evidenz wird konservativ zu `HypothesisCandidate` mit `VALIDATED_BY_EXAMPLES` | Die vorhandene Promotion-Infrastruktur kann den Kandidaten übernehmen | Keine automatische Aktivierung oder Veröffentlichung |
| Promotion/Public Evidence (#322) | Evaluation, Lifecycle, Projekt-Novelty, Proof, strukturierte Ablation und `ProofPolicy` werden auf dieselbe Kandidatenidentität gebunden und durch die bestehenden Gates entschieden | Nur vollständig belegte Kandidaten erreichen `PROMOTED`; Duplikate oder unvollständige Proof-Evidenz bleiben sichtbar darunter | Public Evidence ist keine automatische Behauptung externer mathematischer Neuheit |
| Cross-Family-Clustering (#319) | Familien- und Rule-ID-blinde Strukturcluster mit unabhängigen Alpha-/Value-Profilen und sichtbaren Ablehnungsstatus | Wiederkehrende gerichtete Strukturen können post-hoc über Familien hinweg erkannt werden | Ein Cluster ist noch keine übertragene oder bewiesene Bridge-Hypothese |

## Evidenzstufen

Regelsuche unterscheidet vier Stufen:

1. **Search improvement:** Ein vorgegebenes Ziel wird effizienter erreicht.
2. **Hidden-rule rediscovery:** Eine bekannte, vor dem Lauf entfernte Regel wird wiederentdeckt.
3. **Inventory-new open-target hypothesis:** Ohne Zielausdruck wird eine gegenüber dem Projektinventar neue parametrisierte Hypothese gebildet und unabhängig geprüft.
4. **Externally novel mathematics:** Zusätzlich sind Literatur-, Datenbank- und fachliche Neuheitsprüfungen erforderlich.

Nur Stufe 4 rechtfertigt einen Anspruch auf weltweit neue Mathematik. Ein internes Novelty-Flag oder eine angenommene Public-Evidence-Entscheidung darf nicht als externe Neuheit interpretiert werden.

## Statusanzeigen richtig lesen

Statusfelder wie Existenz, Novelty, Proof oder Public-Evidence beziehen sich auf einen konkreten Kandidaten und eine konkrete Evidenzstufe. Ein Wert `no`, `NOT_EVALUATED` oder `INCONCLUSIVE` ist kein globaler Status des Projekts. Er bedeutet, dass die jeweilige Aussage für diesen Kandidaten nicht belegt wurde oder noch nicht geprüft ist.

Insbesondere bleiben folgende Achsen getrennt:

- mathematische Gültigkeit,
- projektinterne Neuheit,
- externe Neuheit,
- Interessantheit,
- Suchnutzen,
- Evidenzvollständigkeit.

## Open-Target-Promotion-Gate

Das Schema [`regelsuche.open-target-promotion-gate/v1`](schemas/regelsuche-open-target-promotion-gate-v1.schema.json) hält die letzte Entscheidung reproduzierbar fest. Es enthält getrennt:

- Projekt-Novelty und externen Novelty-Status,
- symbolischen und formalen Proof-Status,
- Proof-Policy und tatsächlichen Prover-Ausführungsstatus,
- strukturierte gepaarte Ablation,
- Promotion-Blocker und Public-Evidence-Ablehnungsgründe,
- Kandidaten-, Evaluation-, Signatur-, Proof-, Pfad- und Annahmen-Provenienz,
- einen deterministischen Evidence-Hash.

Ein Exact-/Alpha-Duplikat kann mathematisch bestätigt und `VALIDATED` sein, wird aber nicht als projektneue Regel promoted. Ein unentschiedener symbolischer Proof löscht bestandene Beispielvalidierung nicht, blockiert jedoch die Promotion. Verlangt die `ProofPolicy` einen formalen Nachweis, genügt symbolische Übereinstimmung nicht.

## Aktive Arbeit

### #222 – Cross-Family Structural Clustering

Der erste Slice aus #319 ist gemergt. Als Nächstes wird aus einem zulässigen Cluster ohne Nutzung der Held-out-Familie eine Bridge-Hypothese gebildet. Frische Positiv-/Negativfälle, konfigurierte/ausgeführte/übersprungene Prüfungen und Gegenbeispiele werden für jede beitragende sowie mindestens eine vollständig zurückgehaltene Familie getrennt ausgewiesen.

### #223 – Interestingness und Surprise

Die Bewertung kann jetzt auf realen, validierten Kandidaten aus #221 und strukturellen Bridge-Kandidaten aus #222 kalibriert werden. Interessantheit bleibt ein Ranking-Signal und kann Wahrheit, Novelty, Proof oder Evidenzvollständigkeit nicht überschreiben.

## Empfohlene Reihenfolge

1. #222 um Bridge-Hypothese und frische familienweise Held-out-Validierung erweitern.
2. #223 auf realen Kandidaten aus #221/#222 kalibrieren.
3. #225 als budgetierten Open-Target-Autopiloten implementieren.
4. #233 und #234 für solver-neutrale Obligations-IR und Backend-Orchestrierung bearbeiten.
5. #235 als informationsparitären Vergleichsbenchmark aufbauen.
6. #226 als maschinengeprüftes Release-Gate abschließen.

#220, #224 und #104 bleiben wichtige längerfristige Vorhaben, sollten die aktuelle Discovery-Kette aber nicht unterbrechen.

## Verbindliche wissenschaftliche Grenzen

- Suchpfade und Kandidaten müssen von Regelsuche selbst erzeugt werden.
- Oracles und Prover validieren oder widerlegen; sie konstruieren nicht die Hypothese.
- Ziel-, Referenz-, Familien- und Testinformationen dürfen nicht in Open-Target-Mining einfließen.
- Konfigurierte, ausgeführte und übersprungene Prüfungen werden getrennt bilanziert.
- Unvollständige Evidenz darf nicht vakuos als bestanden gelten.
- Public Evidence benötigt die bestehenden Novelty-, Proof-, Ablation- und Provenance-Gates.
- Eine externe Neuheitsbehauptung braucht eine separate externe Prüfung.
