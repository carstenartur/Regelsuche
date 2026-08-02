# Discovery- und Forschungsstand

**Stand: 2. August 2026**

Diese Seite fasst den gegenwärtigen Forschungsstand zusammen. Sie trennt
implementierte Softwarefähigkeiten, reproduzierte Projektergebnisse,
vergleichende Benchmarks und noch nicht ausgeführte Experimente.

## Zusammenfassung

Regelsuche besitzt eine umfangreiche, checkout-lokal verifizierte Infrastruktur
für symbolische Suche, targetfreie Candidate Formation, Holdout-Prüfung,
Counterexample Search, Proof-Obligationen, reproduzierbare Evidence und
mehrere mathematische Objekttypen.

Das stärkere Flagship-Ziel — ein aus primitiven Operationen erlerntes,
interpretierbares `RewriteProgram`, das auf einem genau einmal verwendeten
FINAL TEST die Suche verbessert — ist **noch nicht ausgeführt**. Die
reversiblen technischen Vorarbeiten sind weit fortgeschritten; reales privates
VALIDATION-/FINAL-TEST-Material, das endgültige `FROZEN_NOT_RUN`-Receipt und die
anschließenden TRAIN-/VALIDATION-/FINAL-TEST-Ergebnisse fehlen noch.

## Status auf einen Blick

| Bereich | Stand | Aussagegrenze |
| --- | --- | --- |
| Produkt und Build | Web-Workbench, CLI, Docker, Full Mode, Proof-Image und checkout-eigener `ciCheck` sind vorhanden | Kein Produktions- oder Sicherheitszertifikat |
| Autonome Referenz-Campaign | Für den eng definierten internen Claim qualifiziert und reproduzierbar gebunden | Keine externe mathematische Neuheit |
| Mehrdomänen-Discovery | Expression Rewrite und endliche Differenzen sind getrennt qualifiziert | Kein universeller domänenunabhängiger Discovery-Nachweis |
| Targetfreie Simplification | Regelsuche erreicht 6/7, SymPy 7/7 eingefrorene Referenzformen | Negatives Track-Ergebnis; keine allgemeine Rangfolge |
| Flagship-Präregistrierung | Work Accounting, Corpus-/Reveal-/Split-/Freeze-Werkzeuge und Baseline-Verträge sind implementiert | Reales Experiment noch nicht freigegeben |
| TRAIN | Kein Flagship-Populationsresultat | Keine Aussage über erlernte Verbesserung |
| VALIDATION | Nicht geöffnet und nicht zur Auswahl verwendet | Keine ausgewählte Flagship-Konfiguration |
| FINAL TEST | Nicht reserviert oder konsumiert | Kein Flagship-Erfolg und kein Nullresultat |
| Externe Neuheit | `BLOCKED` | Keine weltweite Neuheitsbehauptung |
| Formaler Beweis des retained Produktionskandidaten | `NOT_EVALUATED` | Symbolische Validierung ist kein formaler Beweis |
| Promotion / Public Evidence | `NOT_EVALUATED` | Kein autoritativer Regelimport oder externer Evidenzclaim |

<!-- capability-status:start -->
## Maschinengebundener Capability-Status

Die folgende Kurzmatrix wird aus den kanonischen Release-, Domain- und Trust-Verträgen erzeugt. Die vollständige Matrix mit Evidence-Roots steht in [`capability-status.md`](generated/capability-status.md).

| Capability | Status |
|---|---|
| `AUTONOMOUS_CAMPAIGN` | `QUALIFIED` |
| `DOMAIN_GENERIC_DISCOVERY` | `QUALIFIED` |
| `EXTERNAL_NOVELTY_REVIEW` | `BLOCKED` |
| `FORMAL_PROOF_OF_RETAINED_CANDIDATE` | `NOT_EVALUATED` |
| `PLUGIN_ARTIFACT_TRUST` | `IMPLEMENTED` |
| `PLUGIN_INDEX_AUTHENTICATION` | `IMPLEMENTED` |
| `PLUGIN_TRUST_STATE_REVISIONS` | `IMPLEMENTED` |
| `PROMOTION` | `NOT_EVALUATED` |
| `PUBLIC_EVIDENCE` | `NOT_EVALUATED` |
| `PUBLIC_PLUGIN_DISTRIBUTION` | `BLOCKED` |

`QUALIFIED` autorisiert nur den jeweils benannten Claim. Externe mathematische Neuheit, formaler Beweis, Promotion und Public Evidence werden nicht aus einem anderen erfolgreichen Profil abgeleitet.
<!-- capability-status:end -->

## Bereits belegte Projektergebnisse

### Zielgerichtete Suchsteuerung

Eine dokumentierte TEST-Auswertung reduzierte die Zahl erkundeter Zustände von
sieben auf fünf, ohne den Zielpfad zu verändern. Dies belegt eine begrenzte,
erklärbare Suchsteuerung für die eingefrorene Suite, nicht mathematische
Discovery.

### Hidden-Rule Rediscovery

In der retained Referenz wurden 19 von 20 entfernten bekannten Regeln über vier
Familien wiederaufgebaut. 38 negative Holdouts erzeugten keine False Positives;
zwei Prüfungen blieben explizit übersprungen. Die bekannten Referenzregeln
wurden erst post hoc zur Klassifikation verwendet. Das ist Rediscovery, keine
externe Neuheit.

### Open-Target Candidate Formation

Aus mehreren alpha-distinkten, targetfreien Suchbeobachtungen wurde eine
parametrisierte Hypothese gebildet, kompiliert und durch getrennte Stufen
geführt. Dies zeigt Candidate Formation ohne vorgegebenen Zielausdruck, nicht
allgemeine Wahrheit oder fachliche Bedeutung.

### Autonome Referenz-Campaign

Die qualifizierte Campaign bindet Research Brief, Seed-Familien, zwölf
Observations, Aggregate Mining, Kandidaten-Lineage, Validation,
Counterexample Search, Projekt-Novelty, Proof-Obligation, Lifecycle-Handoff,
Ressourcenbilanz und mehrere reproduzierte Läufe.

Der retained Kandidat lautet:

```text
(A + 2) * x + A * x → (2 * A + 2) * x
```

Dieser Kandidat ist gegenüber dem damaligen Projektinventar neu und innerhalb
der gebundenen Suite qualifiziert. Er ist weder als extern neu noch als
mathematisch bedeutend bewertet.

### Mehrdomänen-Discovery

Expression Rewrite und eine Zahlenfolgen-Domäne durchlaufen dieselben
domänenneutralen Generation-, Such-, Validierungs- und Evidence-Grenzen. Das
separate Profil `DOMAIN_GENERIC_DISCOVERY` ist qualifiziert, erweitert aber den
algebraischen Autonomie-Claim nicht automatisch.

## Aktueller vergleichender Benchmark

Der Track `target-free-simplification-head-to-head` vergleicht tatsächliche
Simplifier. Weder Regelsuche noch SymPy erhält die gepinnte Referenzform als
Suchziel.

| Fall | Familie | Regelsuche | SymPy `simplify` |
| --- | --- | --- | --- |
| `(x + 0) * 1 → x` | Identität | erreicht | erreicht |
| `x * 0 + y → y` | Annihilator | erreicht | erreicht |
| `(a + b) * (a + b) → (a + b)^2` | Potenzfaltung | erreicht | erreicht |
| `x + x → 2 * x` | Linearkombination | erreicht | erreicht |
| `(2 * x + 4) / 2 → x + 2` | rationale Reduktion | erreicht | erreicht |
| `(x^2 - 1) / (x - 1) → x + 1` | rationale Kürzung | erreicht | erreicht |
| `(x^3 - 1) / (x - 1) → x^2 + x + 1` | Polynomdivision | **nicht erreicht** | erreicht |

Regelsuche erreicht sechs von sieben Referenzformen. Der Track bleibt deshalb
mit Status `NEGATIVE` retained. Die exakte Polynomdivision ist als
standardmäßig deaktiviertes Pack verfügbar; sie wird nicht nachträglich in das
gemessene Default-Inventar hineindefiniert.

Vollständiger Vertrag, Coverage Gaps und Reproduktion:
[Comparative Discovery Benchmarks](discovery-benchmarks.md).

## Flagship-Experiment: proof-carrying self-improvement

Das primäre nächste Forschungsziel ist in
[Issue #521](https://github.com/carstenartur/Regelsuche/issues/521) beschrieben:
Aus primitiven, ausführbaren Operationen und ausschließlich TRAIN-basierter
Evidence soll ein echtes Strategieprogramm mit Sequenzen, Entscheidungen,
Guards, Prioritäten und explizitem Pruning gelernt werden.

Ein positives Ergebnis darf nur folgende begrenzte Aussage stützen:

> Unter der eingefrorenen Grammatik, dem Regelinventar, dem Corpus, der
> Informationsgrenze und dem Ressourcenbudget hat Regelsuche ein ausführbares
> Rewrite-Programm synthetisiert, das die held-out symbolische Suche ohne
> Korrektheitsregression verbessert.

### Implementierte reversible Voraussetzungen

- getrennte primitive, programminterne, äußere Such- und Exact-Audit-
  Arbeitszähler;
- öffentliche numerische Erfolgs- und Nullresultat-Schwellen;
- eingefrorene Baseline- und Ablationsidentitäten;
- Performance-Messplan mit semantischer Paritätsanforderung;
- konkreter öffentlicher TRAIN-Corpus mit rationalen und polynomialen Fällen;
- privater Reveal-Vertrag mit split-spezifischer Autorisierung;
- strikter Loader sowie lokale exakte Prüfung vor dem Versiegeln;
- öffentliche Hash-Commitments ohne private Ausdrücke;
- Split-Manifest mit Case-, Family-, Exact-, Alpha-, Input- und Target-
  Kollisionskontrollen;
- öffentlicher Freeze-Assembler mit `FROZEN_NOT_RUN`-Semantik;
- Regel-Tiers, Profile, content-addressed Regelinventar und Ablationsfähigkeit.

### Noch fehlende irreversible Schritte

1. reale VALIDATION- und FINAL-TEST-Fälle außerhalb des Repositorys erstellen;
2. diese Fälle mit dem vertrauenswürdigen lokalen Werkzeug exakt prüfen und
   versiegeln;
3. Commitments, Split-Manifest und sämtliche Experimentverträge in einem realen
   `FROZEN_NOT_RUN`-Receipt binden;
4. unabhängig bestätigen, dass zu diesem Zeitpunkt keine TRAIN-, VALIDATION-
   oder FINAL-TEST-Ergebnisse existieren;
5. die TRAIN-Population unter dem eingefrorenen Vertrag ausführen;
6. ausschließlich mit VALIDATION eine Konfiguration auswählen und einfrieren;
7. den FINAL TEST genau einmal reservieren und konsumieren;
8. alle Baselines, Ablationen, Fehler und Nullresultate vollständig retainen.

Bis Schritt 4 abgeschlossen ist, darf die Flagship-TRAIN-Ausführung nicht
beginnen. [Issue #533](https://github.com/carstenartur/Regelsuche/issues/533)
bleibt daher sachlich offen.

## Claim-Stufen richtig lesen

Regelsuche unterscheidet mindestens:

1. **Search Improvement:** Ein bekanntes Ziel wird unter einer Suite effizienter
   erreicht.
2. **Hidden-Rule Rediscovery:** Eine bekannte, vor dem Lauf entfernte Regel wird
   ohne Referenz-Leakage wiederaufgebaut.
3. **Open-Target Candidate Formation:** Ohne Zielausdruck entsteht eine
   projektintern neue Hypothese.
4. **Autonomous Campaign Qualification:** Ein autonom erzeugter Kandidat besteht
   die eingefrorene interne Qualifikation und Reproduktion.
5. **Flagship Held-out Improvement:** Ein erlerntes Strategieprogramm verbessert
   einen genau einmal verwendeten FINAL TEST.
6. **Externally Novel Mathematics:** Literatur-, Datenbank- und unabhängige
   fachliche Prüfung stützen eine externe Neuheitsentscheidung.

Keine Stufe impliziert automatisch die nächste. Wahrheit, Projekt-Neuheit,
externe Neuheit, Interessantheit, Suchnutzen, formaler Beweis, Promotion und
Public Evidence bleiben getrennte Achsen.

## Reproduktion des vorhandenen Stands

Autoritativer Repository-Lebenszyklus:

```bash
./gradlew ciCheck
```

Qualifizierte autonome Referenz-Campaign:

```bash
./gradlew :regelsuche-release:runQualifiedReleaseReadinessWithHiddenRuleEvidence
```

Vergleichende Benchmarks:

```bash
bash scripts/run-comparative-benchmarks-verification.sh
```

Unabhängiges Reproduktionspaket und Claim-Grenzen:
[Independent Reproduction](independent-reproduction.md).

## Nächste Prioritäten

1. reales Flagship-`FROZEN_NOT_RUN`-Receipt erzeugen und #533 abschließen;
2. TRAIN-Populationen, VALIDATION-Auswahl und genau einmaligen FINAL TEST für
   #521 ausführen;
3. information-paritäre Baselines und Ablationen aus #235 vervollständigen;
4. Performance-Optimierungen nur bei byteidentischer Evidence- und
   Work-Accounting-Parität aktivieren;
5. das Methodenpaper ausschließlich aus kanonischen Ergebnissen generieren;
6. externe Interestingness- und Novelty-Prüfungen als getrennte reale Studien
   durchführen.

## Verbindliche Grenzen

- Oracles und Prover validieren oder widerlegen; sie erzeugen nicht den zu
  bewertenden Kandidaten.
- Target-, Referenz-, Familien-, VALIDATION-, FINAL-TEST- und Review-
  Informationen dürfen nicht in eine frühere Formation einfließen.
- Konfigurierte, ausgeführte, übersprungene und verbleibende Arbeit wird
  vollständig bilanziert.
- Fehlende Evidence führt zu `BLOCKED` oder `NOT_EVALUATED`, niemals zu einem
  impliziten Erfolg.
- Laufzeitmessungen ersetzen keine kanonische mathematische Arbeitsbilanz.
- Ein negatives oder null Ergebnis bleibt ein veröffentlichbares Ergebnis.
- Externe mathematische Neuheit benötigt eine eigenständige externe Prüfung.
