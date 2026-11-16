# Exakte Beobachtungszustände für wechselnde Übergänge

Regelsuche erhält rationale affine Übergänge $x \mapsto A_jx+b_j$ und
Beobachtungen $Cx+d$. Das Programm bestimmt selbst, welche linearen Formen
es für die Ausgaben nach beliebigen Ereignissen aufbewahren muss. Im Lauf
genügt der reduzierte Zustand $z=Px$.

Die Herleitung beginnt mit den Zeilen von $C$. Für jede unabhängige
Zustandsform $p$ untersucht sie alle Bilder $pA_j$. Besitzt ein Bild nach
exakter Elimination einen neuen Pivot, wird sein Rest eine weitere
Zustandsform. Eine Schleife verarbeitet jede neu eingefügte Form für jeden
Übergang. Die Konstruktion endet spätestens mit dem vollen Rang des
Eingangszustands. Jeder abgeschlossene Zeilenraum, der $C$ enthält, muss
auch jede so gefundene Form enthalten. Die Dimension ist daher unter den
affin-linearen Zustandszusammenfassungen minimal, die für alle rationalen
Anfangszustände und Ereignisfolgen funktionieren. Konstante Terme belegen
keine Zustandskoordinaten. Die mathematische Methode gehört zur
Beobachtbarkeitsreduktion geschalteter linearer Systeme.
[Petreczky, Bako und van Schuppen](https://doi.org/10.1016/j.automatica.2013.07.022)

Vor der Ausgabe eines Plans prüft Regelsuche den Ablauf mit ursprünglichen
und reduzierten Übergängen am Nullvektor und an allen Einheitsvektoren. Diese
affinen Stützstellen genügen, um $P(A_jx+b_j)=B_jPx+c_j$ und
$Cx+d=DPx+d$ für jedes $x$ zu beweisen. Damit gelten die beobachteten
Werte auch nach einer beliebigen Folge von Übergängen. Der Plan lässt sich
für weitere Anfangszustände binden. `AffineObservationReduction.prepare`
liefert den unveränderlichen Plan. `bind` setzt den Anfangszustand.
Der gebundene Zustand bietet `advance`, `observe` und `trace`.
Für ein gemeinsames Budget erhält jede Phase den Rest der vorigen.
Bei erschöpftem Budget oder wenn Zähler oder Nenner mehr als 16.384 Bit
benötigen, liefert sie den Status mit der verbrauchten Arbeit.

## Versuchsaufbau

Die Eingaben, beide Folgen, die vier Längen und das Abnahmekriterium wurden
vor der Messung fixiert. Jede Ausführung beginnt ohne vorbereiteten Plan.
Nach jedem Ereignis wird die Beobachtung exakt berechnet. Das Ergebnis
qualifiziert sich bei 1.024 Ereignissen, wenn für beide Seeds je Zielaufgabe
mindestens eine zusätzliche Zustandsform entsteht, die Zustandsdimension
sinkt und mindestens 20 Prozent Gesamtarbeit gespart werden.
[Festgelegtes Protokoll](https://github.com/carstenartur/Regelsuche/blob/85affd588aa36bad61ebb8c08c1292a49b521525/config/benchmarks/switched-observation-v1.json)

| Fall | Eingabe und Beobachtung |
|---|---|
| Wechselnder Markov-Transport | Zwei verschiedene rationale Übergänge über sechs Einträge. Beobachtet wird die Masse eines Zustandsblocks. Die beiden Matrizen sind nach dem Prinzip der exakten Aggregation konstruiert. Die Gruppenzuordnung dient ausschließlich dem Versuchsaufbau. [Buchholz](https://doi.org/10.2307/3215235) |
| Schwerpunkt eines Federsystems | Zwei gleiche Massen. Drift und Impuls wirken als getrennte Ereignisse. Beobachtet wird die Schwerpunktposition. Die Herleitung findet die mittlere Geschwindigkeit aus den vier Orts- und Geschwindigkeitskoordinaten. [Hairer, Lubich und Wanner](https://doi.org/10.1017/S0962492902000144) |
| Ungleiche Federimpulse | Eine geänderte Kopplung lässt den relativen Zustand in die beobachtete Position einfließen. |
| Einzelkoordinate | Die erste Koordinate hängt bei beiden Ereignissen nur von sich selbst ab. |

Beide Zielfälle sind synthetische Modelle aus verschiedenen Gebieten. Ihre
reduzierbare Struktur ist Teil der Aufgabenkonstruktion. Die Reduktion
erhält nur Übergänge und Beobachtungskoeffizienten. Die Prüfung gilt über
dem vollen rationalen Zustandsraum und schließt im Markov-Beispiel
beliebige Gesamtmassen ein.

Der direkte Vergleich verfolgt nur originalgetreue Koordinaten, die über
mindestens einen erlaubten Übergang für eine Beobachtung nötig werden.
Beide Varianten bereiten die Übergänge als dünn besetzte Zeilen vor,
überspringen unveränderte Koordinaten und vereinfachen Produkte mit
Einheitskoeffizienten. Wenn sich durch einen Übergang keine beobachtete
Koordinate ändert, verwenden sie den letzten Ausgabewert wieder.
Für den Federfall berechnet eine zweite direkte
Variante den beiden Körpern gemeinsamen Impuls und verwendet ihn für beide
Geschwindigkeiten. Sie verwendet beim Impuls die zuletzt berechnete
Schwerpunktposition. Bei der Drift genügt eine Addition und eine Skalierung
der soeben aktualisierten Positionen. Der geringere der direkten Aufwände zählt.

Arbeitseinheiten erfassen unter anderem durchlaufene Koeffizienten,
Zustandszugriffe und rationale Rechenoperationen. Vorbereitung,
Beweisprüfung, Binden, alle Ereignisse und alle Ausgaben sind enthalten.
Jede Variante hat für diese Phasen zusammen eine Million Einheiten. Die
Prozentangaben beziehen sich auf diese Arbeitseinheiten. Gemeinsame
Eingaben werden vor der Messung eingelesen und die Ergebnisprüfsummen
danach berechnet. Beim vollen Rang stellt der Vergleich nach der bezahlten
Herleitung auf die direkte Auswertung um.

## Ergebnis

Alle exakten Beobachtungswerte stimmen in den 32 Läufen überein. Bei
1.024 Ereignissen bestehen beide Zielfälle das Abnahmekriterium für beide
Ereignisfolgen. Die Spannweiten ergeben sich aus den zwei Seeds.

| Fall | Ursprüngliche → hergeleitete Dimension | Gesamtarbeit mit Herleitung | Beste direkte Gesamtarbeit | Änderung |
|---|---:|---:|---:|---:|
| Wechselnder Markov-Transport | 6 → 3 | 42.703–42.727 | 134.551 | 68,24–68,26 % weniger |
| Schwerpunkt eines Federsystems | 4 → 2 | 11.430–11.470 | 15.867–15.875 | 27,75–27,96 % weniger |
| Ungleiche Federimpulse | 4 → 4 | 28.107–28.115 | 26.817–26.825 | 4,81 % mehr |
| Einzelkoordinate | 4 → 1 | 12.737–12.743 | 12.334–12.340 | 3,27 % mehr |

Für ein Ereignis gewinnt in allen Fällen die direkte Auswertung. Bei 32
Ereignissen amortisiert sich die Herleitung in beiden Markov-Läufen. Der
Federfall ist bei 32 Ereignissen direkt günstiger und spart bei 256
Ereignissen 12,57 bis 14,02 Prozent. Die
Einzelkoordinate zeigt die Bedeutung der direkten Abhängigkeitsanalyse.
Sie benötigt ebenfalls nur eine Zustandskoordinate und spart den
Aufwand für Herleitung und Beweis.

Der Versuch belegt automatische Zustandsreduktion über zwei Arten
geschalteter rational-affiner Modelle. Die Übertragbarkeit auf
unabhängig kuratierte Anwendungen und auf nichtlineare Zustandsformen
bleibt eine Forschungsfrage.

## Reproduktion

```bash
mvn -pl regelsuche-math-algorithms -am \
  -Dtest=AffineObservationReductionTest,AffineObservationComparisonTest test
```

Der Vergleich liest das Protokoll, prüft die exakten Ausgaben einzeln und
vergleicht den neu berechneten Arbeitsbeleg samt SHA-256-Prüfsummen mit der
eingecheckten Tabelle. Mit
`-DaffineObservationOutput=/tmp/switched-observation-work.tsv` schreibt der
Test einen neuen Beleg. [Arbeitsbeleg](switched-observation/work.tsv)
