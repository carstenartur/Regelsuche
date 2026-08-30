# Nutzenstudie für die Polynomtheorie

## Status

Die Nutzenstudie für die Polynomtheorie ist vorbereitet, aber noch nicht
ausgeführt. Der Profilvertrag und die zielblinde Fallformation sind eingefroren,
bevor ein Held-out-Ergebnis eines Profils vorliegt. Es wurde weder ein
Produktstandard noch eine Hybridpolitik oder ein Nutzennachweis ausgewählt.

| Vertrag | Status |
| --- | --- |
| Profil-Präregistrierung | `FROZEN_NOT_EXECUTED` |
| Fallformation | `FROZEN_NOT_EXECUTED` |
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

## Verifikation im Checkout

Die zielblinde Formation wird mit folgendem Befehl exportiert:

```bash
./gradlew :regelsuche-experiments:freezePolynomialTheoryUtilityCaseCorpus
```

Die fokussierten beziehungsweise modulweiten Tests laufen über:

```bash
./gradlew :regelsuche-experiments:test
./gradlew :regelsuche-experiments:check
```

Die `check`-Lebenszyklen des Moduls und des Root-Projekts führen den
Formation-Export aus. Die Gradle-Aufgabe deklariert ausschließlich die
Formation als Eingabe und prüft, dass keine Qualifikationsdatei ausgegeben
wird.

## Nächster Evidence-Slice

Als Nächstes ist die Ausführungsmatrix aus allen 20 Fällen, den fünf
präregistrierten Profilen und gemeinsamen kumulativen Arbeitscheckpoints
einzufrieren. Erst nachdem diese Matrix und die Profiladapter versioniert sind,
darf die Ausführung beginnen. Qualifikation, Politikauswahl und eine mögliche
Standardempfehlung bleiben getrennte Schritte nach dem Ergebnis-Freeze.
