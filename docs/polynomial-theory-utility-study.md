# Nutzenstudie für die Polynomtheorie

## Status

Die Nutzenstudie ist vorbereitet, aber noch nicht ausgeführt. Profilvertrag,
zielblinde Fallformation, Ausführungsplan und die daraus abgeleiteten
Adaptereingaben sind vor dem ersten Held-out-Lauf inhaltsadressiert. Es wurde
weder ein Produktstandard noch eine Hybridpolitik oder ein Nutzennachweis
ausgewählt.

| Vertrag | Status |
| --- | --- |
| Profil-Präregistrierung | `FROZEN_NOT_EXECUTED` |
| Fallformation | `FROZEN_NOT_EXECUTED` |
| Ausführungsplan | `FROZEN_NOT_EXECUTED` |
| Adaptereingaben | `READY_NOT_EXECUTED` |
| Qualifikation | `SEALED_NOT_OPENED` |
| Profilausführung | `NOT_STARTED` |
| Standardentscheidung | `NOT_SELECTED` |

Ein grüner Build belegt nur die interne Konsistenz und Reproduzierbarkeit
dieser Verträge. Er belegt nicht, dass Faktorisierung, Cache-Replay oder ein
externes Backend den Suchnutzen verbessern.

## Einseitige Fallgrenze

Die Studie verwendet eine vor der Ausführung sichtbare Formation und eine
getrennte, bis nach dem Ergebnis-Freeze versiegelte Qualifikation.

### Sichtbare Formation

`polynomial-theory-utility-formation-corpus-v1.json` enthält ausschließlich
Informationen, die jedes Profil vor der Ausführung erhalten darf:

- Quellausdruck und deklarierte Domäne;
- Grad-, Koeffizientengrößen- und Dichtestratum;
- Auftretenstiefe, Auftretenslayout und Wiederverwendungszahl;
- Identität der Annahmenmenge;
- Budgets für primitive, mechanische und Faktorisierungsarbeit.

Der Loader bindet die Qualifikation über Pfad, Bytelänge und SHA-256, öffnet
oder parst sie jedoch nicht.

Identität der Formation:

- Bytelänge: `7346`;
- SHA-256:
  `2fd889c51b086afcf36ec450a38a3cbaf15b05cb0b27cf1fa5222b22e906636b`.

### Versiegelte Qualifikation

`polynomial-theory-utility-qualification-corpus-v1.json` enthält Felder, die
erst nach einem versionierten Ergebnis-Freeze verwendet werden dürfen:

- erforderlicher Fallausgang;
- Reduzibilitäts- und Multiplizitätsstatus;
- Referenzausdruck;
- erwarteter Klassifikatorausgang.

Identität der Qualifikation:

- Bytelänge: `5146`;
- SHA-256:
  `09455d9540547b48a741679f1d7b07bb1b35d2c44af4a5561b94b225c77963d6`.

Formation, Profilauswahl, Ausführungsplan und Adaptereingaben dürfen diese
Felder nicht lesen. Ihre Artefakte enthalten nur die unveränderliche Bindung an
Pfad, Länge und Hash.

## Eingefrorene Abdeckung

Die 20 geordneten Fälle umfassen ganzzahlige und rationale univariate
Polynome, nicht unterstützte multivariate und nichtpolynomiale Eingaben,
symbolische Exponenten, wiederholte Faktoren, irreduzible Kontrollen,
verschachtelte Auftreten und wiederholte Wiederverwendung. Die Grade reichen
bis 10; dichte, gemischte und dünn besetzte Formen sind enthalten.

Die versiegelte Verteilung lautet:

| Ausgang | Fälle |
| --- | ---: |
| `POSITIVE` | 12 |
| `NEGATIVE` | 3 |
| `NEAR_MISS` | 1 |
| `UNSUPPORTED` | 2 |
| `BUDGET_INCONCLUSIVE` | 2 |

Die budgetbedingt unentschiedenen Fälle sind ansonsten reduzierbar. Sie prüfen
damit die vorab deklarierte Arbeitsgrenze und nicht fehlende mathematische
Fähigkeit.

## Eingefrorener Ausführungsplan

Der Ausführungsplan bildet das kartesische Produkt aus 20 Fällen, fünf
präregistrierten Profilen und sechs kumulativen Arbeitscheckpoints:

```text
5 Profile × 6 Checkpoints = 30 Läufe
30 Läufe × 20 Fälle = 600 Planzeilen
```

Die Checkpoints liegen bei `1/12`, `1/6`, `1/3`, `1/2`, `3/4` und dem vollen
Fallbudget. Teilbudgets werden positiv aufgerundet. Für einen Fall und einen
Checkpoint erhalten alle fünf Profile exakt dieselben Grenzen für primitive,
mechanische und Faktorisierungsarbeit.

Jeder Lauf bildet einen zusammenhängenden Block von 20 Zeilen und verarbeitet
die Fälle in der eingefrorenen Formationsreihenfolge. Die Zeilenordnung lautet
`RUN_MAJOR_CONTIGUOUS`. Ein Streaming-Runner kann den Laufwechsel dadurch
eindeutig am `runId` erkennen und veränderlichen Zustand genau einmal an der
Laufgrenze zurücksetzen.

Profil- und Checkpoint-Läufe teilen keinen Zustand. Das Cache-Profil startet
jeden Lauf leer und darf Einträge nur innerhalb desselben
Profil-/Checkpoint-Laufs behalten. Backend-Substitution und ein verstecktes
Best-of sind verboten.

Identität des Plans:

- Bytelänge: `235651`;
- SHA-256:
  `6cfac16d65611820b713cf1f2aca0fdb724fc542ccef6b9de80981dc290af619`.

## Zielblinde Adaptereingaben

`polynomial-theory-utility-execution-inputs-v1.json` enthält genau einen
Eingabeumschlag für jede Planzeile. Die 600 Umschläge bleiben in derselben
run-major Reihenfolge und tragen ausschließlich den Status
`READY_NOT_EXECUTED`.

Jeder Umschlag bindet:

- `inputId`, `rowId` und `runId`;
- Fall-, Profil- und Checkpoint-ID;
- die bereits vor Ergebnissen festgelegte Adapter-ID;
- die drei unveränderten Arbeitsgrenzen;
- die Inhaltsadressen von Präregistrierung, Formation, Qualifikation und Plan.

Ein Umschlag enthält weder Quellausdruck noch Qualifikations-, Ergebnis- oder
Entscheidungsfelder. Der spätere Runner löst den Formationsfall ausschließlich
über die eingefrorene Fall-ID auf. Die Profilpolitik stammt ausschließlich aus
dem Ausführungsplan.

Die fünf Adapter-IDs sind:

| Profil | Adapter-ID |
| --- | --- |
| `NO_FACTORIZATION` | `regelsuche.polynomial-theory-utility.no-factorization/v1` |
| `ON_DEMAND_VERIFIED_FACTORIZATION` | `regelsuche.polynomial-theory-utility.on-demand-verified-factorization/v1` |
| `VERIFIED_DERIVED_MACRO_CACHE` | `regelsuche.polynomial-theory-utility.verified-derived-macro-cache/v1` |
| `SPECIALIZED_BINARY_QUARTIC_CONTROL` | `regelsuche.polynomial-theory-utility.specialized-binary-quartic-control/v1` |
| `OPTIONAL_EXTERNAL_VERIFIED_FACTORIZATION` | `regelsuche.polynomial-theory-utility.optional-external-verified-factorization/v1` |

Die Adaptereingaben verleihen keine Entscheidungsautorität. Ihr einzig
zulässiger nächster Ausgang ist ein getrennt versionierter zielblinder
Candidate-Freeze.

Identität der Adaptereingaben:

- Bytelänge: `336406`;
- SHA-256:
  `d93e2d3c4c3e72d435fa37d6bc988d2a8d873a3d1bc5584232fb1383b64d62c8`.

## Verifikation im Checkout

Formation, Plan und Adaptereingaben werden mit folgenden Befehlen exportiert:

```bash
./gradlew :regelsuche-experiments:freezePolynomialTheoryUtilityCaseCorpus
./gradlew :regelsuche-experiments:freezePolynomialTheoryUtilityExecutionPlan
./gradlew :regelsuche-experiments:freezePolynomialTheoryUtilityExecutionInputs
```

Die fokussierten beziehungsweise modulweiten Tests laufen über:

```bash
./gradlew :regelsuche-experiments:test
./gradlew :regelsuche-experiments:check
```

Die `check`-Lebenszyklen des Moduls und des Root-Projekts führen alle Exporte
aus. Keine der Aufgaben deklariert die versiegelte Qualifikation als Eingabe.
Plan- und Eingabeexport schlagen fehl, falls eine Qualifikationsdatei im
jeweiligen Ausgabeordner erscheint.

## Nächster Evidence-Slice

Als Nächstes werden die fünf ausführbaren Profiladapter gegen die 600
unveränderlichen Eingabeumschläge implementiert. Sie dürfen ausschließlich
einen versionierten zielblinden Candidate-Freeze erzeugen. Erst nachdem dessen
Bytes gebunden sind, darf der getrennte Qualifikationsschritt die versiegelte
Datei öffnen. Politikauswahl und eine mögliche Standardempfehlung bleiben
weitere mechanisch abgeleitete Schritte.
