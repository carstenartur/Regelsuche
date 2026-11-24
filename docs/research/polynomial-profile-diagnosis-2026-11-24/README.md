# Ursachen der Null-Ergebnisse im öffentlichen Faktorisierungsvergleich

## Ergebnis und Geltungsbereich

Die Diagnose ist abgeschlossen. Im unveränderten Quellstand
`f183aedd1ff2ab061ed903559b96a11d28974e7f` ist kein allgemeiner Implementierungsfehler
nachgewiesen, der die beobachteten Null-Ergebnisse durch einen engen Fix beseitigt.
Die bestehende Kombination aus vollständiger Quellenprüfung, konservativer
Zulassung und den eingefrorenen Budgets erreicht keine verifizierten Kandidaten.
Die verbleibende Nutzlücke ist real und darf nicht als behoben berichtet werden.

Die historische Matrix wurde vollständig erneut ausgeführt und bytegleich
reproduziert. Ein zusätzlicher Diagnoseexport enthält alle 600 geplanten
Zeilen: 480 beobachtete und 120 weiterhin blockierte externe Zeilen. Die
24 Hashes der beobachteten Profil-/Checkpointgruppen stimmen exakt mit
`docs/research/polynomial-theory-runtime/public-profile-comparison.tsv` überein.
Die neuen Dateien ändern weder historische Befunde noch Suchbudgets.

Die Diagnose lief in einem sauberen Arbeitsbereich auf dem genannten öffentlichen Quellstand. Produktionscode, Tests und Budgets blieben unverändert.
Die geschützte Qualifikation wurde nicht geöffnet.

## Belege

Alle Begleitdateien liegen im unverändert gebundenen [Diagnosearchiv](evidence.tar.gz). Nach dem Entpacken stehen sie im aktuellen Verzeichnis:

- `public-profile-diagnosis.tsv`: vollständige Matrix mit Fallbudgets,
  terminalen Status, ausgeführter Arbeit je Dimension, konkreten
  Abbruchdetails, Request-/Kandidatenzahlen und ursprünglichen Zeilenidentitäten.
- `PublicProfileDiagnosis.java`: ruft die tatsächlichen registrierten Adapter
  auf den eingefrorenen öffentlichen Zeilen auf, prüft Ergebnisse und den
  laufgebundenen Cache-Verlauf und schreibt die Diagnosematrix.
- `public-profile-stage-diagnosis.tsv`: alle 144 Vorkommen des direkten
  Profils; zusätzlich verweigerte Arbeitsblöcke und primitive Vorbedingungen, innere
  Extraktions-/Enginebefunde und tatsächlich übergebene Requestgrenzen.
- `PublicProfileStageDiagnosis.java`: protokolliert verweigerte Belastungen
  über eine rein delegierende Arbeitsautorität. Für jedes Vorkommen wird
  die Pipeline-Identität, für jede Zeile das vollständige Rohledger gegen
  die Ausführung des wirklichen Adapters geprüft. Alle 144 Vorkommen stimmen.
- `factorization-baseline.log`, `factorization-focused.log` und
  `manifest.json`: frische Prüfungen sowie Quellstand und Dateihashes.

Die Programme sind Diagnosewerkzeuge. Sie geben keine alternative
Studienautorität aus, ändern keine Budgets und erzeugen keine Erfolgszeilen.

## Vollständige öffentliche Befunde

Jedes Profil besitzt 120 geplante Zeilen: 20 Fälle mal sechs Checkpoints.

| Profil | Beobachtet | Verifizierte Übergänge | Ausgeführte Faktoranforderungen | Verifizierte Kandidaten | Terminale Zeilenbefunde |
|---|---:|---:|---:|---:|---|
| Faktorisierung deaktiviert | 120 | 0 | 0 | 0 | 120 ohne Übergang |
| Direkte native Faktorisierung | 120 | 0 | 19 | 0 | 118 Budgetgrenzen, 2 nicht unterstützt |
| Verifizierter Cache | 120 | 0 | 2 | 0 | 120 Budgetgrenzen |
| Binäre Quartik-Spezialisierung | 120 | 0 | 11 | 0 | 109 Budgetgrenzen, 5 nicht unterstützt, 5 gemischt, 1 ohne Übergang |
| Externe verifizierte Faktorisierung | 0 | nicht beobachtet | nicht beobachtet | nicht beobachtet | 120 blockierte Zeilen |

Damit liegt kein Beleg für einen Fehler bei Kandidatenindex, Renderer oder
Übernahme vor: Sämtliche 32 ausgeführten Faktoranforderungen liefern bereits
null verifizierte Kandidaten. Keine der ausgeführten Zeilen bezahlt
Produktverifikation oder Rendering. Der ausgeschaltete Arm ist eine
Nullkontrolle; seine 120 Zeilen sind kein zusätzliches Scheitern eines Solvers.

## 1. Die Quellenprojektion verbraucht den größten Teil der Arbeit

| Faktorisierungsprofil | Mechanische Arbeit insgesamt | Quellenprojektion/Matching | Quellenvalidierung | Faktorisierung | Cache-Lookup | Evidenzdatensätze |
|---|---:|---:|---:|---:|---:|---:|
| Direkt | 26.831 | 22.603 | 1.800 | 2.288 | 0 | 140 |
| Cache | 6.058 | 4.545 | 334 | 670 | 378 | 131 |
| Spezialisiert | 23.265 | 22.507 | 585 | 38 | 0 | 135 |

Die Matching-Dimension umfasst die tatsächlich ausgeführte Quellenprojektion,
Positionskontrolle und ihre Hashbelege. Im direkten Profil sind dies 84,2 %
der gesamten mechanischen Arbeit, im spezialisierten Profil 96,7 %.
Allein `projection.range-commitment-code-units` trägt nach der unveränderten
v2-Projektion 14.278 direkte beziehungsweise 14.806 spezialisierte Einheiten.

Der Projector bindet Knotentypen, Operatoren, Quellintervalle und die
Zertifikathashes der Literale. Seine explizite Vierfachzählung wird im
kanonischen Projektor bereits durch vier geteilt. Für `x^2-1` sind 784 rohe
Einheiten genau 196 kanonische Einheiten; es liegt keine fehlende Division
des bekannten Implementierungsmultiplikators vor. Die Produktions- und
Multiplikatorkontrollen bestätigen den vorhandenen Vertrag.

### Konkrete CP06-Abbrüche bei unveränderten Budgets

| Fall im direkten Profil | Verbrauchte Mechanik / Grenze | Faktorarbeit / Grenze | Nachgewiesene Ursache |
|---|---:|---:|---|
| `x^2-1` | 248 / 256 | 0 / 64 | Der nächste atomare Textvergleich kostet 18 Einheiten; 266 wären erforderlich. Noch keine Engineanforderung. |
| `x^4-5*x^2+4` | 512 / 512 | 0 / 128 | Das nächste Format-Codezeichen würde 513 Einheiten ergeben. Noch keine Engineanforderung. |
| `x^2-(5/6)*x+1/6` | 335 / 384 | 0 / 96 | Der nächste Range-Commitment-Block mit 284 rohen Einheiten entspricht 71 kanonischen Einheiten; 406 wären erforderlich. |
| `x^3-1` | 383 / 384 | 61 / 96 | Engineanforderung mit 61 Einheiten endet in `SQUARE_FREE_WORK_BUDGET_EXCEEDED`; die nachfolgende Quellenautorisierung würde zusätzlich die mechanische Grenze überschreiten. |
| `x^10-1` | 846 / 1.536 | 383 / 384 | Der Request erhält 382 rohe Engine-/Verifiereinheiten und endet in `MODULAR_FACTORIZATION_INCONCLUSIVE`. Die Faktorgrenze bindet trotz verfügbarer Mechanik. |

Ein nicht vollständig ausgeschöpfter Zähler bedeutet somit keinen
unerklärlichen frühen Abbruch. Der nächste atomare Arbeitsblock muss vor
seiner Ausführung vollständig zugelassen werden. Auch eine konservative
Reserve ist keine bereits ausgeführte Arbeit und erscheint deshalb nicht
als verbrauchter Betrag.

Die innere Diagnose der 19 direkten Engineanforderungen liefert vier
Abbrüche bei der Inhaltsnormalisierung, 13 bei der quadratfreien Zerlegung
und zwei bei der modularen Faktorisierung. Alle tragen
`BUDGET_INCONCLUSIVE`; alle haben null Kandidaten. Der äußere Befund
`SOURCE_EVIDENCE_VALIDATION_AUTHORITY_INSUFFICIENT` kann einen schon vorher
budgetbedingt gescheiterten Engineversuch überlagern. Die separat erhaltenen
Reports erlauben diese Unterscheidung.

Die beiden expliziten Tiny-Budget-Fälle dürfen bereits wegen ihrer maximal
vier primitiven Einheiten keine siebenstufige direkte/Cache-Transition oder
sechsstufige Spezialtransition beginnen. Auch dies ist eine vorhandene
Vertragsgrenze.

## 2. Cache-Einfügungen sind unter den bestehenden Grenzen unerreichbar

Der Quelltextindex reserviert Lookup und vollständigen Nachweis gemeinsam.
Die konservative Zulassung verwendet
`remainingOpaqueWorkUnits() = min(Restmechanik, Restfaktorisierung)`.
Für die fünfstellige Quelle `x^2-1` beträgt die Lookup-Obergrenze 242,
für die sechsstellige Quelle `x^10-1` 245 Einheiten. Die Budgetierung
berücksichtigt vorab auch den größeren Hit-Nachweis, während ein tatsächlicher
Miss weniger Arbeit verbraucht.

Nur `z10-unity-full-budget` überschreitet diese Schwelle unter CP05 und
CP06. Genau diese beiden Zeilen enthalten die beiden beobachteten Misses.
Beide nachfolgenden nativen Requests enden bei der modularen Faktorisierung
ohne Kandidaten. Die weiteren Fälle verweigern den Lookup oder ein früheres
Vorbereitungsstück. Es gibt keine Einfügung, keine Verdrängung und keinen Replay.

Unabhängig davon existiert eine strengere strukturelle Grenze:

1. `VerifiedPolynomialTransitionCacheStore.retainMeasured` verlangt zuerst
   `1024 + Quelllänge + Ersetzungslänge + Provenienzlänge` an Restautorität.
2. Kein vollständiges eingefrorenes Faktorbudget übersteigt 384. Kleinere
   Checkpoints sind höchstens so groß. Damit ist schon die historische
   Mindestreserve niemals erreichbar.
3. Die folgende konservative Graph-/Identitätsgrenze lautet zusätzlich
   `65536 + 256 * material`. Selbst 65.536 liegt über dem größten
   mechanischen Fallbudget von 1.536.
4. Jeder Lauf startet leer. Ohne erste Einfügung kann in diesem Vertrag
   anschließend auch kein Cache-Hit entstehen.

Diese Argumentation hängt nicht vom Ausgang einer einzelnen Faktorisierung
ab. Selbst ein hypothetisch kostenloser erfolgreicher Faktorvorschlag könnte
mit den bestehenden Regeln nicht in den Studiencache aufgenommen werden.
Eine bloße engere Lookup-Zulassung würde diese Grenze nicht aufheben.

Die Verwendung derselben konservativen Opaque-Obergrenze für Cacheoperationen
ist ein möglicher Ansatz für eine künftige, beweisbar genauere
Zulassungsrechnung. Die gegenwärtige Implementierung widerspricht damit
jedoch keinem behaupteten Vollständigkeitsvertrag: Die konservative
Reservierung und die 1.024-Grenze sind ausdrücklich dokumentiert. Ihre
Entfernung oder eine neue Discountregel wäre kein nachgewiesener enger
Bugfix. Insbesondere darf sie keine positiven Studienzeilen erzwingen.

## 3. Der Spezialarm deckt das eingefrorene Fragment tatsächlich eng ab

`BinaryQuarticFactorizationEngine` fordert eine homogene binäre Quartik
mit beiden von null verschiedenen Extremkoeffizienten. Die gemessene
Spezialpipeline homogenisiert univariate Polynome bis Grad vier auf Grad
vier. Polynome kleineren Grades erhalten dabei einen Nullkoeffizienten
vor `x^4` und können deshalb außerhalb dieses Spezialfragments liegen.

Die elf spezialisierten Requests liefern zehnmal
`REQUIRES_BINARY_HOMOGENEOUS_QUARTIC_WITH_NONZERO_EXTREME_TERMS` und einmal
`NO_BOUNDED_INTEGER_QUADRATIC_DECOMPOSITION` (`x^4+4*x+2` auf CP06).
Höhere Grade erreichen an elf Vorkommen die lokale Grenze
`MAX_DEGREE_EXCEEDED`; dieser Status ist eine strukturelle Budgetgrenze,
kein ausgeschöpfter mechanischer Zähler. Bei rationalen Quotienten und
symbolischen Exponenten greifen die deklarierten Semantikgrenzen.

Die beiden für diesen Arm naheliegenden positiven quartischen Eingaben
`x^4-5*x^2+4` und `x^4-2*x^2+1` kommen auf CP06 noch nicht durch die
Quellenprojektion. Aus den vorhandenen Beobachtungen folgt deshalb weder
ein Übernahmefehler noch ein Beleg für die behauptete Nutzwirkung.

## 4. Die externen Entwicklungsproben schließen die Studienlücke nicht

`public-probe.json` zeigt die erfolgreiche eigenständige Faktorisierung
von `x^2-1` über `ZZ` in der gepinnten GraalPy-25.1.3-/SymPy-1.14.0-Laufzeit.
Die Datei bindet Laufzeit und Bridge, enthält aber keine vollständige
kanonische interne Arbeitsmessung für eine Studienzeile.

`public-coefficient-work.json` misst fünf ausgewählte Kerne
(`dup_mul`, `dup_sqr`, `gf_mul`, `gf_sqr`, `gf_div`). Die öffentlichen
Beispiele `x^4+x-1` und `x^4+x+1` verbrauchen dort 199 beziehungsweise
457 gemessene Einheiten. Die Datei setzt ausdrücklich
`completeBackendWork=false` und `studyRowsExecuted=0`.
Offen bleiben unter anderem innere Inversion, weitere Division/GCD,
Zugriffe, Auswahl, Rekombination und Konvertierung. Die zwei
Entwicklungsbeispiele und ihre partiellen Maße sind kein Ersatz für die
600-Zeilen-Matrix.

Die Registry blockiert daher korrekt mit
`EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE`. Zusätzlich nennt der
Checkout-Lock GraalPy `25.5.5.0.3`, der eingefrorene Plan jedoch `25.1.3`;
der Preflight meldet `EXTERNAL_RUNTIME_LOCK_MISMATCH`. Die Abweichung
allein zu korrigieren würde die fehlende Messabdeckung nicht herstellen.
Kein externer Studienlauf wurde gestartet, kein natives Backend eingesetzt
und kein fehlender Messwert als Null ausgegeben.

## Geprüfte Alternativursachen

| Verdacht | Ergebnis |
|---|---|
| Budgets gelangen falsch zum Profil | Der Vergleich prüft für jeden Fall Gleichheit aller drei Budgets über alle fünf Profile; die Inputs sind an die eingefrorene Matrix gebunden. |
| Kandidat wird nach erfolgreicher Engine verworfen | Kein einziger Request liefert einen verifizierten Kandidaten. Auswahl-/Renderpfad kann diese Null-Ergebnisse nicht verursachen. |
| Rohzähler werden versehentlich doppelt oder ohne Divisor gerechnet | Cumulative v2-Partition, Projektormultiplikatoren und Produktionsledger sind geprüft; Range-Commitments werden korrekt durch vier geteilt. |
| Cache wird versehentlich zwischen Zeilen geleert | Ein Run besitzt seinen eigenen Store. Komponentenprüfung belegt Einfügung, Replay, Generation und FIFO. Öffentliche Zeilen erreichen keine erste Einfügung. |
| Runtime-Probe belegt externen Studienerfolg | Probe und Kernmessung deklarieren einen anderen, begrenzten Umfang; die Kernmessung nennt null Studienzeilen. |
| Native Engine kann grundsätzlich keine Faktoren ausgeben | Die vorhandenen Komponentenprüfungen mit ihren eigenen unveränderten Komponentenautoritäten faktorieren allgemeine ganzzahlige/rationale Polynome und prüfen Vielfachheiten. Das ist kein Erfolg unter dem eingefrorenen Studienbudget. |

## Verifikation

Frischer unveränderter Reproduktionslauf:

```sh
mvn -B -ntp \
  -pl regelsuche-experiments -am \
  -Dtest=PolynomialTheoryUtilityPublicProfileComparisonTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Ergebnis: ein Test, null Fehler, null Fehlschläge, keine übersprungenen Tests.
Der Test vergleicht die komplette neu gebildete TSV mit dem historischen
Artefakt. Zusätzlich stimmen alle 24 Zeilenbindungs-Hashes des erweiterten
Exports mit dem historischen Vergleich überein.

Fokussierte Kontrolle der konkreten verbleibenden Risiken:

```sh
mvn -B -ntp \
  -pl regelsuche-experiments -am \
  -Dtest=PolynomialTheoryUtilityWorkAuthorityTest,PolynomialTheoryUtilityCanonicalWorkProjectionTest,PolynomialTheoryUtilityProjectionProductionStageTest,PolynomialTheoryUtilityProjectionMultiplierCompletenessTest,PolynomialTheoryUtilityObservedProfileAdapterTest,PolynomialTheoryUtilityDerivedCacheAdapterTest,PolynomialTheoryUtilitySpecializedAdapterTest,PolynomialTheoryUtilityProfileRegistryTest,NativeUnivariateFactorizationEngineTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Ergebnis: 42 Tests insgesamt (14 im Mathematikmodul, 28 im Experimentmodul),
null Fehler, null Fehlschläge, keine übersprungenen Tests. Zusammen mit
der Matrixreproduktion sind dies 43 erfolgreiche Tests.

Die Diagnoseprogramme lassen sich mit JDK 25 und dem Test-Classpath aus dem
Surefire-XML des Experimentmoduls als Java-Quelldateien ausführen. Jeweils
ein Argument bezeichnet die TSV-Ausgabe. Der Stage-Diagnoselauf hat für
alle 120 direkten Zeilen und ihre 144 Vorkommen identische Rohpräfixe und
Pipeline-Identitäten gegenüber dem wirklichen Adapter bestätigt.

## Konsequenz für die Remediation

Die belastbare Änderung des Befunds ist die nun präzise Erklärung der
Null-Ergebnisse. Ein Produktivfix wird nicht vorgetäuscht. Die Studie
belegt unter diesen Grenzen weiterhin keinen Faktorisierungs- oder
Cache-Nutzen.

Konkrete nächste Forschungsarbeiten wären eine tatsächlich billigere,
weiterhin quellengebundene Projektion; eine nachgewiesen engere und
vollständig gemessene Cache-Zulassung; ausreichend günstige allgemeine
native Rechenwege sowie vollständige externe Messabdeckung. Ein neuer
Vergleich müsste solche Implementierungsänderungen mit denselben
historischen Budgets getrennt ausweisen und sämtliche bisherigen
Befunde behalten. Budgeterhöhung, Fall-Sonderlogik, entfernte
Nachweisprüfung und externe Nullarbeit sind keine zulässige Erledigung.
