# Nutzenstudie für die Polynomtheorie

## Status

Die Nutzenstudie für die Polynomtheorie ist vorbereitet, aber noch nicht
ausgeführt. Profilvertrag, zielblinde Fallformation und die vollständige
Ausführungsmatrix sind eingefroren, bevor ein Held-out-Ergebnis eines Profils
vorliegt. Es wurde weder ein Produktstandard noch eine Hybridpolitik oder ein
Nutzennachweis ausgewählt.

| Vertrag | Status |
| --- | --- |
| Profil-Präregistrierung | `FROZEN_NOT_EXECUTED` |
| Fallformation | `FROZEN_NOT_EXECUTED` |
| Ausführungsplan | `FROZEN_NOT_EXECUTED` |
| Qualifikation | `SEALED_NOT_OPENED` |
| Profilausführung | `NOT_STARTED` |
| Standardentscheidung | `NOT_SELECTED` |

Diese Unterscheidung ist verbindlich. Ein grüner Build belegt nur, dass die
eingefrorenen Verträge im Checkout intern konsistent und reproduzierbar sind.
Er belegt nicht, dass Faktorisierung, Cache-Replay oder ein externes Backend den
Suchnutzen verbessern.

## Einseitige Fallgrenze

Die Studie verwendet zwei getrennte, jeweils inhaltsadressierte Ressourcen.

### Vor der Ausführung sichtbare Formation

`polynomial-theory-utility-formation-corpus-v1.json` enthält ausschließlich
Informationen, die jedes Profil vor der Ausführung erhalten darf:

- Quellausdruck und deklarierte Domäne;
- Grad-, Koeffizientengrößen- und Dichtestratum;
- Auftretenstiefe, Auftretenslayout und geplante Wiederverwendungszahl;
- Identität der Annahmenmenge;
- Budgets für zugelassene primitive Arbeit, gesamte mechanische Arbeit und
  Faktorisierungsarbeit.

Der Formation-Loader bindet die Qualifikation über Pfad, Bytelänge und SHA-256,
öffnet oder parst sie jedoch nie. Sein Exportbefehl kopiert ausschließlich die
Formation und schlägt fehl, sobald im Ausgabeverzeichnis eine
Qualifikationsdatei erscheint.

Identität der Formation:

- Bytelänge: `7346`;
- SHA-256:
  `2fd889c51b086afcf36ec450a38a3cbaf15b05cb0b27cf1fa5222b22e906636b`.

### Bis zum Ergebnis-Freeze zurückgehaltene Qualifikation

`polynomial-theory-utility-qualification-corpus-v1.json` enthält Felder, die
erst nach einem versionierten Ergebnis-Freeze verwendet werden dürfen:

- erforderlicher Fallausgang;
- Reduzibilitäts- und Multiplizitätsstatus;
- Referenzausdruck;
- erwarteter Klassifikatorausgang.

Fallformation und Profilauswahl dürfen diese Felder ausdrücklich nicht lesen.
Die Ressource bleibt an die Formation gebunden, damit ein späterer
Qualifikationsschritt nicht unbemerkt eine andere Antwortmenge einsetzen kann.

Identität der Qualifikation:

- Bytelänge: `5146`;
- SHA-256:
  `09455d9540547b48a741679f1d7b07bb1b35d2c44af4a5561b94b225c77963d6`.

## Eingefrorene Abdeckung

Die 20 geordneten Fälle umfassen ganzzahlige und rationale univariate
Polynome, nicht unterstützte multivariate und nichtpolynomiale Eingaben,
symbolische Exponenten, wiederholte Faktoren, irreduzible Kontrollen,
verschachtelte Auftreten und wiederholte Wiederverwendung. Die Grade reichen
bis 10; dichte, gemischte und dünn besetzte Formen sind enthalten.

Die versiegelte Verteilung der Ausgänge lautet:

| Ausgang | Fälle |
| --- | ---: |
| `POSITIVE` | 12 |
| `NEGATIVE` | 3 |
| `NEAR_MISS` | 1 |
| `UNSUPPORTED` | 2 |
| `BUDGET_INCONCLUSIVE` | 2 |

Die budgetbedingt unentschiedenen Fälle sind ansonsten reduzierbar. Sie prüfen
damit die vorab deklarierte Arbeitsgrenze und nicht fehlende mathematische
Fähigkeit. Wiederverwendungsfälle enthalten ein, zwei oder vier äquivalente
Auftreten, sodass Cache-Lookup, Replay und Amortisation später gemessen werden
können, ohne die Fallmenge zu ändern.

## Eingefrorener Ausführungsplan

Der Ausführungsplan bildet das kartesische Produkt aus 20 Fällen, fünf
präregistrierten Profilen und sechs kumulativen Arbeitscheckpoints. Er enthält
somit genau 600 Zeilen. Jede Zeile besitzt eine inhaltsadressierte Identität und
beginnt mit `NOT_EXECUTED`.

Die Checkpoints liegen bei `1/12`, `1/6`, `1/3`, `1/2`, `3/4` und dem vollen
Fallbudget. Teilbudgets werden positiv aufgerundet. Für einen Fall und einen
Checkpoint erhalten alle fünf Profile exakt dieselben Grenzen für zugelassene
primitive Arbeit, gesamte mechanische Arbeit und Faktorisierungsarbeit.

Die Ausführung ist vorab in 30 voneinander isolierte Läufe gruppiert:

```text
5 Profile × 6 Checkpoints = 30 Läufe
30 Läufe × 20 Fälle = 600 Ergebniszeilen
```

Jeder Lauf bildet im Artefakt einen zusammenhängenden Block von 20 Zeilen und
verarbeitet die Fälle in der eingefrorenen Formationsreihenfolge. Dadurch kann
ein Streaming-Runner den Laufwechsel eindeutig am `runId` erkennen und Cache
oder sonstigen veränderlichen Zustand genau einmal an der Laufgrenze
zurücksetzen.

Profil- und Checkpoint-Läufe teilen weder Cache noch sonstigen veränderlichen
Zustand. Das Cache-Profil startet jeden Lauf leer und darf Einträge nur innerhalb
des jeweiligen Profil-/Checkpoint-Laufs über die 20 Fälle hinweg behalten.
Damit sind Wiederverwendung innerhalb eines Falls und über spätere identische
Auftreten messbar, ohne Ergebnisse zwischen Vergleichsprofilen zu übertragen.

Der Plan bindet außerdem:

- den gemeinsamen exakten Verifizierer und die Transformationsevidence;
- Cache-Schema, Cache-Revision, Kapazität 128 und FIFO-Eviction;
- native, spezialisierte und externe Engine-Identitäten;
- GraalPy 25.1.3, SymPy 1.14.0, mpmath 1.3.0 und die eingecheckte Lockdatei;
- explizit aufsteigende Kandidatenauswahl statt eines versteckten Best-of;
- das Verbot einer Backend-Substitution nach einem Fehler;
- die vollständige Aufbewahrung negativer, nicht unterstützter,
  budgetbedingt unentschiedener und technischer Ausgänge.

Identität des erzeugten Plans:

- Bytelänge: `235617`;
- SHA-256:
  `0a9be9ab83076ac2e507aa7d0f3c343ec2840556441c7cf8ce750772f215855e`.

## Verifikation im Checkout

Formation und Ausführungsplan werden mit folgenden Befehlen exportiert:

```bash
./gradlew :regelsuche-experiments:freezePolynomialTheoryUtilityCaseCorpus
./gradlew :regelsuche-experiments:freezePolynomialTheoryUtilityExecutionPlan
```

Die fokussierten beziehungsweise modulweiten Tests laufen über:

```bash
./gradlew :regelsuche-experiments:test
./gradlew :regelsuche-experiments:check
```

Die `check`-Lebenszyklen des Moduls und des Root-Projekts führen beide
Exporte aus. Weder Formation noch Ausführungsplan deklarieren die versiegelte
Qualifikation als Eingabe. Beide Aufgaben schlagen fehl, falls eine
Qualifikationsdatei im Ausgabeordner erscheint.

## Nächster Evidence-Slice

Als Nächstes werden die fünf ausführbaren Profiladapter gegen die jetzt
unveränderliche Matrix implementiert. Danach darf genau ein versionierter
zielblinder Ergebnis-Freeze erzeugt werden. Erst auf dessen gebundene Bytes darf
der getrennte Qualifikationsschritt zugreifen. Politikauswahl und eine mögliche
Standardempfehlung bleiben weitere, mechanisch abgeleitete Schritte nach diesem
Ergebnis-Freeze.
