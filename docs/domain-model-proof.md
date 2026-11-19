# Ein gespeichertes Sequenzmodell prüfen

Die Workbench kann aus dem ausgewählten Kandidaten eines gespeicherten
Sequenz-Exports ein ausdrückliches rationales affines Modell bilden und dessen
Rekurrenzgesetz mit dem vorhandenen Java-Prüfer verifizieren. Der Modellbeleg
bindet die ursprüngliche Quelle, die unabhängige endliche Prüfung, das Modell,
die geprüfte Rekurrenz und die berechneten Modellwerte.

Die Aussage gilt unter einer ausdrücklich genannten Annahme: **Der ausgewählte
Kandidat definiert jeden weiteren Schritt des Modells.** Endliche Beobachtungen
beweisen nicht, dass eine anderweitig definierte unendliche Folge diesem Modell
folgt. Ein berechneter Wert außerhalb der gespeicherten Daten ist deshalb eine
bedingte Modellvorhersage. Der ursprüngliche Export wird nicht verändert.

## Benutzung in der Workbench

1. **Eigene Discovery-Domänen** öffnen und unter **Gespeicherte Sequenz-Exporte**
   einen Export auswählen oder seine vier Originaldateien importieren. Der
   [Workspace-Vertrag](domain-export-workspaces.md) unterstützt die eingebauten
   Domänen `integer-sequence-finite-difference@v1` und
   `integer-sequence-linear-recurrence@v1`.
2. **Modellgesetz prüfen** ausführen. Die Aktion reproduziert die vollständige
   Quelle, entnimmt deren tatsächlich ausgewählten Kandidaten und prüft dessen
   endliche Daten unabhängig. Eine zuvor angezeigte Bestätigung oder ein
   heruntergeladener Beleg ersetzt diese Schritte nicht.
3. Im Modellbereich Ergebnis, Annahme, Zustandsgleichungen und Anfangszustand
   lesen. Die skalare Rekurrenz und die exakten Werte beziehen sich auf dieses
   Modell. Die endliche Prüfung und die drei mathematischen Arbeitsphasen haben
   eigene Anzeigen. Vor der Anzeige lädt die Workbench den gespeicherten Beleg
   über seine Hash-Adresse erneut und verlangt dieselben Antwortbytes und
   Quellenheader wie bei der Erzeugung. Dieser Abruf führt die unten beschriebene
   erneute Prüfung aus. Die aufklappbare Quellenbindung zeigt die Belegidentitäten.
4. **Modellbeleg herunterladen** lädt den vollständigen gespeicherten JSON-Beleg
   über seine Hash-Adresse. Auch dieser Abruf reproduziert den Beleg innerhalb
   seiner aufgezeichneten Budgets und liefert ihn nur bei vollständiger
   Übereinstimmung aus.

Ein Wechsel des Exports entfernt das bisherige Modellergebnis und seinen
Downloadlink sofort. Beim Neuladen der Seite wird über `#export=<runDigest>` die
Quelle wieder ausgewählt; ein vorheriger Modellbeleg wird nicht automatisch
angezeigt. Die Aktion erneut ausführen, um das Modell dieser Quelle erneut zu
prüfen. Bei identischem Ergebnis bleibt die Belegidentität gleich.

**Endliche Daten prüfen** bleibt eine eigene Aktion mit einem eigenen
[Validierungsbeleg](domain-downstream-finite-validation.md). Eine erfolgreiche
endliche Prüfung allein ist noch kein geprüftes Modellgesetz.

## Welches Modell wird geprüft?

Für beide Adapter gilt mit rationaler Matrix `A`, rationalem Offset `b` und
festem Anfangszustand:

\[
x(n+1)=A\,x(n)+b,\qquad a(n)=x_0(n),\qquad n\geq 0.
\]

Index `0` bezeichnet den ersten beobachteten Wert. Die Holdout-Werte schließen
unmittelbar an den beobachteten Präfix an; ihr Index beginnt nicht wieder bei
null. Die Zustandskoordinaten heißen `x0`, `x1`, `x2`. Unterstützt werden genau
ein bis drei Koordinaten; größere Modelle werden weder abgeschnitten noch
angenähert.

### Endliche Differenzen

Für einen Kandidaten der Differenzordnung `k` enthält der Zustand die ersten
`k` Differenzstufen:

\[
x(n)=\bigl(a(n),\Delta a(n),\ldots,\Delta^{k-1}a(n)\bigr).
\]

Die Kandidatenwerte `initialDifferences[0..k-1]` bilden den Anfangszustand.
`initialDifferences[k]` ist die konstante höchste Differenz und wird zum Offset
der letzten Koordinate. Jede vorhergehende Koordinate addiert beim Übergang die
nächste Koordinate. Die ursprüngliche Domäne stellt auch eine konstante Folge
als Ordnung `1` mit Offset `0` dar.

| Gespeicherte Folge | Anfangszustand | Offset | Modellwert `a(100)` |
| --- | --- | --- | ---: |
| `5,5,5,…` | `[5]` | `[0]` | `5` |
| `3,7,11,15,…` | `[3]` | `[4]` | `403` |
| `1,4,9,16,…` | `[1,3]` | `[0,2]` | `10201` |
| `1,8,27,64,…` | `[1,7,12]` | `[0,0,6]` | `1030301` |

Die Auslassungspunkte in dieser Tabelle stehen für die durch das jeweilige
Modell definierte Fortsetzung. Sie behaupten keine zusätzliche Beobachtung.
Ein Modell mit drei Differenzkoordinaten kann wegen des affinen Offsets eine
skalare Rekurrenz der Ordnung vier besitzen.

### Lineare Rekurrenz

Der Quellkandidat speichert seine Koeffizienten vom jüngsten zum ältesten Wert:

\[
a(n+k)=c_0a(n+k-1)+\cdots+c_{k-1}a(n).
\]

Der Zustand lautet dagegen `x(n)=[a(n),…,a(n+k-1)]`. Die Begleitmatrix schiebt
die Werte nach links; ihre letzte Zeile ist daher
`[c[k-1],…,c[0]]`. Der Offset ist null. Die ersten `k` beobachteten Werte bilden
den Anfangszustand. Beispielsweise werden die Quellkoeffizienten `[4,-5,2]` zur
letzten Matrixzeile `[2,-5,4]`; eine unveränderte Reihenfolge würde ein anderes
Modell definieren.

Der Beleg schreibt auch die **geprüften** Rekurrenzkoeffizienten in dieser
aufsteigenden Zeitreihenfolge als `coefficientsOldestFirst`:

\[
a(n+r)=\sum_{j=0}^{r-1}q_j a(n+j).
\]

Matrixeinträge, Offset, Anfangswerte, Rekurrenzkoeffizienten und Ergebnisse sind
exakte rationale Strings, etwa `-3`, `0` oder `1/2`. Es gibt keine
Gleitkomma-Näherung. Die Herkunftsdomäne, der Adaptervertrag und die
Quellordnung bleiben im Beleg erkennbar.

## Ausführung und Aussagegrenzen

`DomainModelProof.prove(workspace[, budget])` akzeptiert ausschließlich einen
unveränderlichen `DomainExportWorkspace`. Der Ablauf ist:

1. Replay-Budget und Payload zulassen, dann die **gesamte kanonische Evidence**
   mit dem unveränderten Quellenbudget reproduzieren. Auch ein neu gehashtes
   falsches Suchprotokoll muss daran scheitern.
2. Den tatsächlich ausgewählten typisierten Kandidaten und sein Zertifikat
   entnehmen. Fehlende Kandidatenkörper werden nicht aus Hashes oder
   Darstellungsstrings rekonstruiert. Dimension und Koeffizientenbreite müssen
   vor dem mathematischen Backend in das unterstützte Fragment passen.
3. Den vorhandenen unabhängigen `DomainFiniteDataCheck` ausführen. Nur eine
   vollständige Bestätigung aller gespeicherten Werte und des endlichen
   Zeugen erlaubt den nächsten Schritt.
4. Das explizite affine Modell bilden. `RecurrenceObservationKrylov.prepare`
   ermittelt die skalare Rekurrenz und lässt ihre Identität durch den
   vorhandenen unabhängigen Modellprüfer kontrollieren. Nur dieser erfolgreiche
   Aufruf kann intern einen `VerifiedPlan` ausstellen.
5. Den Anfangszustand binden und die Indizes aller gespeicherten Werte exakt
   auswerten. Wenn höchstens 100 Werte gespeichert sind, wird zusätzlich Index
   `100` berechnet. Alle Werte an gespeicherten Indizes müssen nochmals zu den
   unabhängig geprüften Daten passen.

Ein vollständiger Beleg enthält Modell und Proof-Objekt. `proof.modelHash`
bindet den Proof an `model.contentHash`. Die Proof-Aussage hat ausdrücklich
`scope=EXPLICIT_AFFINE_MODEL`. `RETAINED_MODEL_TERM` bezeichnet einen zum
gespeicherten Wert passenden Modellwert; `MODEL_PREDICTION` hat keinen
behaupteten Datenvergleich und deshalb `matchesRetained=null`.

| Belegstatus | Bedeutung |
| --- | --- |
| `PROVED_MODEL` | Endliche Daten vollständig bestätigt; Modellgesetz, Anfangszustand und alle angeforderten Modellwerte erfolgreich geprüft. |
| `INCONCLUSIVE` | Replay-Admission oder eine mathematische Phase konnte innerhalb ihrer Grenzen nicht abgeschlossen werden. Ein bereits gebildetes Modell kann sichtbar bleiben; `proof` bleibt null. |
| `UNSUPPORTED` | Kandidatentyp, Modellgröße oder Koeffizientenbreite liegt außerhalb des Adapterfragments; kein Modell-Proof wird ausgeführt. |
| `NOT_EVALUATED_NO_SELECTED_CANDIDATE` | Der reproduzierte Quelllauf enthält keinen ausgewählten Kandidaten; kein Modell wird erfunden. |
| `FINITE_CHECK_NOT_CONFIRMED` | Die unabhängige endliche Prüfung ist nicht vollständig bestätigt. Ihr eigenes Ergebnis bleibt erhalten. |
| `REPLAY_MISMATCH` | Vollständige Quell-Evidence oder ausgewählte Objektbindung stimmt nicht mit dem Replay überein. |
| `TECHNICAL_FAILURE` | Die Ausführung hat technisch nicht abgeschlossen oder das projizierte Modell widerspricht den bereits geprüften Daten. Das ist kein erfundener Gegenbeweis zur Quelle. |

`originalSequenceProofStatus` bleibt in jedem Fall `NOT_ESTABLISHED`.
`formalProofStatus`, `externalNoveltyStatus`, `promotionStatus` und
`publicEvidenceStatus` bleiben `NOT_EVALUATED`. Eine formale
Beweisassistenten-Ausgabe, Neuheitsprüfung oder Freigabe entsteht durch diesen
Consumer nicht. Ebenso wenig folgt aus ihm eine eindeutige unendliche
Fortsetzung oder eine minimale Rekurrenzordnung der ursprünglichen Quelle.

## Grenzen und getrennte Arbeit

Der Standardlauf erlaubt einen Replay-Versuch und höchstens 512 UTF-16-Zeichen
Seed-Payload. Die bestehenden Workspace-Replay-Grenzen gelten zusätzlich.
Der Adapter akzeptiert Dimensionen `1..3` und rationale Rekurrenzkoeffizienten
innerhalb der 128-Bit-Eingabegrenze des mathematischen Backends.

Die endliche Prüfung behält ihre eigenen Limits: standardmäßig 256
Termversuche, 20.000 zugelassene skalare Rechenaufrufe und 1024 Bit für Skalare.
Ihre harten Maxima sind 256, 100.000 und 4096. Jede mathematische Phase
`preparation`, `binding` und `queries` erhält unabhängig bis zu 200.000
logische Backend-Arbeitseinheiten. Java-Aufrufer dürfen die Budgets senken;
der HTTP-Endpunkt verwendet die Standardbudgets. Die Abfragen sind fest
abgeleitet und bleiben im Indexbereich `0..255`.

Die Arbeitszeilen der drei Phasen enthalten `configured`, `executed`, `skipped`
und `remaining`. Hier bezeichnet `executed` die vom vorhandenen Backend
gebuchten logischen Einheiten. Bei einer Budgetüberschreitung kann dessen
Zähler den verbleibenden Budgetrest verbuchen; das ist kein Zähler tatsächlich
ausgeführter Maschinenbefehle oder einzelner rationaler Operationen. Für
beobachtete Phasen gilt
`configured = executed + skipped + remaining`; `skipped` ist `0`.
Wirft eine bereits gestartete Phase vor Rückgabe ihres Arbeitsprotokolls,
bleiben ihre drei Verbrauchswerte `null` und ihr Status `TECHNICAL_FAILURE`.

Quellenarbeit, zusätzliches Replay, endliche Prüfaufrufe und Modellphasen werden
getrennt ausgewiesen. Modellprojektion, Kodierung/Hashing und Gesamtarbeit bleiben
explizit `UNAVAILABLE`. Auch die internen BigInteger- und GCD-Schritte sind kein
vollständiger Teil dieser Messung. Daraus entsteht weder eine Gesamtarbeitszahl
noch ein vergleichender Effizienzbeleg.

## HTTP, Speicherung und erneute Prüfung

`POST /api/discovery-domains/exports/{runDigest}/model-proof` erwartet
`Content-Type: application/json` und genau die zwei Bindungen der geladenen
Quellenansicht:

```json
{"expectedWorkspaceHash":"sha256:<64 Hexziffern>","expectedEvidenceHash":"sha256:<64 Hexziffern>"}
```

Beide Platzhalter müssen durch die tatsächlichen Hashes ersetzt werden. Der
Body ist auf 4096 Bytes begrenzt. Fehlende oder unbekannte Felder und doppelte
JSON-Schlüssel werden abgewiesen. Eine fremde Quellenbindung liefert `409`,
ein unzulässiger Body `400` beziehungsweise bei Übergröße `413`. HTTP `200`
bedeutet, dass ein Beleg vorliegt; für die mathematische Bedeutung ist sein
`status` maßgeblich.

Die Antwort enthält ETag, Quelllauf-ID, Dateinamen und einen `Location`-Link auf
`GET /api/discovery-domains/exports/{runDigest}/model-proof/{receiptDigest}`.
Die Pfad-Digests sind jeweils 64 kleingeschriebene Hexziffern. Belege liegen
getrennt vom unveränderten Originalexport, neben dem Exportverzeichnis unter
`runs-domain-exports-model-proofs/{runDigest}/{receiptDigest}.json` für das
konfigurierte Laufverzeichnis `runs`. Es gelten höchstens 1 MiB pro Beleg und
16 unterschiedliche Belege pro Export. Identische Wiederholungen erzeugen
keinen zusätzlichen Beleg. Eine Dateisperre pro Export schützt diese Grenze
auch bei mehreren schreibenden Java-Prozessen. Die Veröffentlichung ersetzt
keine bestehende Belegdatei.

Beim Laden werden Originalquelle, vollständige Quellenbindung, kanonischer
Beleghash und aufgezeichnete Budgets erneut geprüft. Anschließend reproduziert
`DomainModelProof.requireRetained` den gesamten Consumer mit diesen Budgets.
Nur bei vollständiger Byteübereinstimmung werden die gespeicherten Originalbytes
ausgeliefert. Auch ein neu gehashter falscher Modellwert, eine veränderte
Rekurrenz oder ein angehobener Status genügt nicht. Der Abruf verursacht also
erneute Arbeit; er schreibt diese nicht nachträglich in den historischen Beleg.
Ein fehlender Beleg liefert `404`, ein ungültiger gespeicherter Beleg `500` und
eine unzulässige HTTP-Methode `405`.

Das geschlossene [JSON-Schema](schemas/regelsuche-domain-model-proof-v1.schema.json)
verwendet Draft 2020-12 und bindet die vorhandenen Quellen- und
Finite-Check-Schemata ein. Es prüft Form, Pflichtfelder, zulässige Statuswerte,
Dimensionen und notwendige Proof-/Arbeitsbedingungen. Ein Schema kann nicht
den mathematischen Inhalt oder die Gleichheit zweier Hashziele beweisen;
diese Autorität entsteht erst durch die beschriebene erneute Ausführung.

## Kontrollen und offener Umfang

Die fokussierten Tests verwenden echte öffentliche Sequenzläufe, Exporte und
Replays. Die mathematischen Kontrollen umfassen beide Domänen, asymmetrische
Rekurrenzkoeffizienten, konstante bis kubische Differenzmodelle, fehlende
Kandidaten, nicht unterstützte Dimensionen, Budgetabbrüche und neu gehashte
falsche Belege. Die Offline-Schemakontrollen prüfen echte erfolgreiche und
abgebrochene Belege sowie fehlende Quellenhashes, unzulässige Proof-Behauptungen,
falsche Modell-/Rekurrenzformen und erfundene Arbeit.

Mit Java 25 aus dem Repository ausführen:

```bash
./gradlew :regelsuche-discovery:test --tests de.regelsuche.discovery.domain.DomainModelProofTest --tests de.regelsuche.discovery.domain.DomainModelProofRepositoryTest
./gradlew :regelsuche-release:test --tests de.regelsuche.release.DomainModelProofSchemaTest
```

Der Consumer ergänzt #1020 um diesen begrenzten Modellbeweispfad. Die
ausdrucksspezifische Produktionspipeline, externe Neuheit, Promotion,
Release-Komposition und vergleichende Mehrdomänen-Qualifikation werden dadurch
nicht abgeschlossen. Es werden keine geschützten Korpora, Kampagnen oder
Veröffentlichungen ausgeführt.
