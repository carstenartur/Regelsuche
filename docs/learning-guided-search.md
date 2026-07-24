# Lernend geführte Regelsuche

> **Status:** Architektur- und Forschungskonzept, Stand 24. Juli 2026  
> **Claim-Grenze:** Dieses Dokument beschreibt einen nachgelagerten Forschungsstrang. Es erweitert weder den Funktionsumfang noch die wissenschaftlichen Claims eines bestehenden Releases.

Dieses Konzept entwickelt die bisherige Regelsuche zu einer hierarchisch und lernend geführten Sucharchitektur weiter. Der systematische Kern bleibt erhalten: formal zulässige Umformungen werden aus einem versionierten Regelbestand erzeugt und jede akzeptierte Aussage bleibt durch symbolische Prüfung oder ein überprüfbares Zertifikat abgesichert. Neu ist eine Strategieebene, die Suchbudget auf vielversprechende konkrete Regelanwendungen verteilt und erfolgreiche Suchverläufe zu wiederverwendbaren Makros, Zwischenzielen und gegebenenfalls neuen Regelkandidaten verdichtet.

Verwandte Dokumentation: [Discovery Engine](discovery-engine.md), [Makroregeln](macro-rules.md), [Evolutionäre Suche](evolutionary-search.md), [AST-Regelradar](ast-rule-radar.md) und [Release Readiness](release-readiness.md).

## 1. Motivation

Die bisherigen Experimente sprechen nicht gegen algorithmische mathematische Entdeckung an sich. Sie sprechen aber gegen die starke Annahme, eine weitgehend ungeführte Enumeration syntaktischer Ausdrücke werde durch genügend Rechenaufwand automatisch relevante Mathematik hervorbringen.

Dafür gibt es drei strukturelle Gründe:

1. **Die Repräsentation bestimmt die effektive Schwierigkeit.** Eine kurze mathematische Idee kann in einer ungeeigneten Grammatik eine sehr lange Herleitung benötigen. Schon eine kleine Verlängerung des kürzesten Pfads vervielfacht den Suchraum.
2. **Wahrheit erzeugt häufig keinen brauchbaren Fitnessgradienten.** Viele mathematische Kandidaten sind entweder exakt korrekt oder falsch. Fast richtige Zwischenformen müssen nicht näher an einer Lösung liegen als andere falsche Formen.
3. **Interessantheit ist nicht mit Wahrheit identisch.** Kurze und korrekte Aussagen können tautologisch oder bloße Umschreibungen sein. Wertvolle Aussagen können erst durch eine neue Abstraktion, ein Hilfslemma oder eine Verbindung zwischen Teilgebieten sichtbar werden.

Die Konsequenz ist keine Aufgabe der vollständigen Regelsuche. Der Brute-Force-Anteil erhält eine neue Rolle: Er definiert die formal zulässigen Möglichkeiten und bleibt als Reserve für Vollständigkeit und Exploration erhalten. Eine lernende Strategie entscheidet dagegen, welche Möglichkeiten zuerst und mit welchem Budget untersucht werden.

## 2. Zielbild

Die Zielarchitektur trennt strikt vier Verantwortlichkeiten:

1. **Erzeugung:** Der symbolische Kern bestimmt alle aktuell formal anwendbaren Umformungsinstanzen innerhalb eines endlichen Budgets.
2. **Priorisierung:** Eine Strategie bewertet diese Instanzen im Kontext des gesamten Suchzustands.
3. **Verifikation:** Ein formaler Prüfer entscheidet über Zulässigkeit, Äquivalenz, Beweisstatus und Nebenbedingungen.
4. **Abstraktion:** Wiederkehrende erfolgreiche Suchmuster werden als geprüfte Makro- oder Regelkandidaten vorgeschlagen.

Die KI ist damit nicht Wahrheitsrichter. Sie ist Priorisierer, Wertschätzer, Strategiegenerator, Mustererkenner und Vorschlagsmaschine für Zwischenziele. Jede mathematische Gültigkeitsentscheidung bleibt beim überprüfbaren symbolischen Kern.

## 3. Formales Suchmodell

Ein Suchzustand wird nicht nur als einzelner AST betrachtet. Er umfasst mindestens

\[
s=(E,G,\Gamma,H,B),
\]

wobei

- \(E\) der aktuelle Ausdruck, E-Graph oder Beweiszustand ist,
- \(G\) das Ziel oder die gewünschte Eigenschaft bezeichnet,
- \(\Gamma\) Typen, Annahmen, verfügbare Lemmata und Domänenverträge enthält,
- \(H\) die bisherige Such- und Transformationsgeschichte bindet und
- \(B\) die verbleibenden Ressourcen beschreibt.

Eine konkrete Aktion ist nicht nur eine Regel, sondern eine instanziierte Anwendung

\[
a=(r,p,\sigma),
\]

mit Regel \(r\), Position \(p\) im AST beziehungsweise E-Graph und Variablenbelegung \(\sigma\). Der symbolische Kern erzeugt

\[
A(s)=\{(r,p,\sigma)\mid r\text{ ist an }p\text{ unter }\sigma\text{ korrekt anwendbar}\}.
\]

Die Strategie lernt anschließend eine Priorität oder Policy

\[
\pi_\theta(a\mid s).
\]

Bei Regeln mit unendlich vielen Parametern, etwa der Einführung eines beliebigen Hilfsterms, reicht Abzählbarkeit nicht. Kandidaten werden deshalb über eine Grammatik nach wachsender Beschreibungslänge in endlichen Präfixen geöffnet:

\[
A_1(s)\subseteq A_2(s)\subseteq A_3(s)\subseteq\dots
\]

Die Strategie entscheidet, wann der Aktionsraum erweitert wird. Dadurch bleibt der Raum effektiv enumerierbar, ohne sofort unendlich viele Aktionen materialisieren zu müssen.

## 4. Weiche Priorisierung statt harter KI-Filter

Ein lernender Filter darf ungewöhnliche, aber korrekte Entdeckungswege nicht dauerhaft ausschließen. Deshalb werden nur formal unzulässige Aktionen hart entfernt. Alle übrigen Aktionen bleiben grundsätzlich erreichbar.

Eine beispielhafte Priorität lautet

\[
P(s,a)=
\alpha\log \pi_\theta(a\mid s)
+\beta V_\theta(T(s,a))
-\lambda C(s,a)
+\eta X(s,a),
\]

mit

- Policy-Schätzung \(\pi_\theta\),
- Wertschätzung des Folgezustands \(V_\theta\),
- erwarteten Ausführungskosten \(C\) und
- Explorationsbonus \(X\).

Die Architektur verwendet mindestens zwei Warteschlangen:

- eine primäre Queue für hoch bewertete Aktionen;
- eine faire Reservequeue für seltene oder schlecht bewertete, aber formal zulässige Aktionen.

Ein festes oder adaptives Explorationsbudget stellt sicher, dass die Policy nicht ausschließlich bereits bekannte Standardlösungen reproduziert.

Hart ausgeschlossen werden ausschließlich Aktionen, die nachprüfbar

- typ- oder domänenfehlerhaft sind,
- eine Nebenbedingung verletzen,
- einen bereits kanonisch identischen Zustand erzeugen,
- einen verbotenen Zyklus ohne Informationsgewinn bilden oder
- ein explizites Ressourcenlimit überschreiten.

## 5. Benötigter Kontext

Die Bewertung darf sich nicht auf den lokalen AST-Knoten beschränken. Die Nützlichkeit einer Umformung kann von Geschwisterzweigen, entfernten Vorkommen, dem Ziel, Annahmen und früheren Schritten abhängen.

Der Strategiezustand soll deshalb mindestens enthalten:

- den lokalen AST-Ausschnitt und den Pfad zur Wurzel;
- relevante Geschwister-, Bindungs- und Vorkommensbeziehungen;
- normalisierte globale Merkmale des Ausdrucks oder E-Graphs;
- Ziel und offene Unterziele;
- Typen, Annahmen und verfügbare Lemmata;
- bereits versuchte Regelinstanzen und erkannte Sackgassen;
- Ressourcenverbrauch und verbleibendes Budget.

Für nicht rein äquationale Beweise ist der Suchraum ein Hypergraph: Eine Aktion kann mehrere Unterziele erzeugen, zusammenführen oder schließen. Der gleiche Scheduler muss deshalb sowohl einfache AST-Transformationen als auch Beweiszustandsübergänge beschreiben können.

## 6. Hierarchische Strategieebenen

### 6.1 Primitive Regelinstanzen

Die unterste Ebene priorisiert konkrete Anwendungen vorhandener, formal geprüfter Regeln. Sie soll sehr schnell sein und kann durch ein kleines Graphmodell, einen Transformer über kanonischen Zustandsmerkmalen oder einen klassischen lernenden Ranker realisiert werden.

### 6.2 Makroregeln und Taktiken

Häufig erfolgreiche Folgen primitiver Schritte werden als parametrisierte Programme zusammengefasst. Eine Makrostrategie enthält

- eine strukturelle Vorbedingung,
- eine geordnete oder teilweise geordnete Folge primitiver Schritte,
- explizite Budgets und Abbruchbedingungen,
- die vollständige Herkunft aus Trainingsspuren und
- einen unveränderten formalen Prüfpfad.

Ein Makro erhält keinen Beweisstatus durch häufige Verwendung. Es ist zunächst lediglich ein effizienter Kandidatengenerator.

### 6.3 Zwischenziele und Checkpoints

Eine höhere Ebene kann eine gewünschte Zwischenform vorschlagen, ohne den vollständigen primitiven Pfad vorzugeben. Beispiele sind:

- gemeinsame Faktoren auf einer Additionsebene sichtbar machen;
- einen Ausdruck in eine kanonische Polynomform bringen;
- alle relevanten Vorkommen einer Variablen in einem Teilbaum sammeln;
- ein Hilfslemma mit einer bestimmten Schnittstelle erzeugen.

Ein symbolischer Sucher oder E-Graph konstruiert und prüft anschließend den Weg zu diesem Checkpoint. Dadurch kann ein generatives Modell strategische Struktur liefern, ohne ungeprüfte elementare Beweisschritte auszugeben.

### 6.4 Neue Regeln, Lemmata und Begriffe

Die äußerste Ebene verändert den zukünftigen Suchraum. Sie schlägt aus wiederkehrender Struktur neue Abstraktionen vor. Solche Kandidaten durchlaufen jedoch dieselben oder strengere Gates wie andere Discovery-Ergebnisse:

1. kanonische Form und Deduplikation;
2. Nebenbedingungen und Domänengrenzen;
3. Gegenbeispielsuche;
4. symbolischer Beweis oder überprüfbares Zertifikat;
5. Holdout-Evaluation ohne Informationsleck;
6. Messung des zusätzlichen Verzweigungsfaktors;
7. explizite Promotion-Entscheidung.

## 7. E-Graphs und allgemeine Beweiszustände

Für Äquivalenzumformungen sind E-Graphs besonders geeignet. Statt früh einen einzelnen AST-Pfad festzulegen, speichern sie viele äquivalente Formen gemeinsam. Die Strategie priorisiert dann beispielsweise

- welche E-Klasse expandiert wird,
- welche Regelfamilie Sättigungsbudget erhält,
- welche Extraktionskosten verwendet werden oder
- welcher Checkpoint als nächste Phase dient.

E-Graphs lösen die Explosion nicht automatisch. Eine aggressive Regelsättigung kann Speicher und Laufzeit ebenfalls unbeherrschbar machen. Der gelernte Scheduler bleibt daher für Regelbudget, Phasenwechsel und Stop-Kriterien verantwortlich.

Induktion, Fallunterscheidung, Quantorenschritte und Hilfslemmata benötigen zusätzlich eine allgemeine Beweiszustandssuche. Beide Welten sollen dieselbe Ereignis- und Evidence-Schnittstelle verwenden, damit Trainingsspuren, Kosten und Replays vergleichbar bleiben.

## 8. Lernquellen

Aus einem Suchlauf entsteht eine Folge

\[
s_0\xrightarrow{a_0}s_1\xrightarrow{a_1}\dots\xrightarrow{a_{n-1}}s_n.
\]

Gespeichert werden nicht nur erfolgreiche Pfade, sondern auch alternative Aktionen, verworfene Zustände, Sackgassen und Ressourcen. Daraus können mehrere Modelle gelernt werden:

- eine Policy für die nächste Aktion;
- ein Value-Modell für verbleibende Suchkosten oder Erfolgswahrscheinlichkeit;
- ein Kostenmodell für erwartetes AST- beziehungsweise E-Graph-Wachstum;
- ein Modell zur Auswahl oder Erzeugung von Zwischenzielen;
- ein Modell zur Erkennung wiederverwendbarer Aktionsfolgen.

Der Lernzyklus ist iterativ:

1. Die aktuelle Strategie bearbeitet einen eingefrorenen TRAIN-Aufgabenstrom.
2. Sämtliche Entscheidungen und Gegenfakten werden kanonisch protokolliert.
3. Modelle werden nur aus dem erlaubten Trainingsmaterial aktualisiert.
4. VALIDATION bestimmt Konfiguration und Stop-Kriterien.
5. FINAL TEST bleibt bis zur festgelegten Auswertung verborgen.
6. Die neue Strategie wird gegen ungeführte und handgeschriebene Baselines verglichen.

Menschliche Beweise liefern einen sinnvollen Startprior. Eigene Suchspuren sind danach wichtiger, weil sie genau die Zustandsverteilung abbilden, die das System selbst erzeugt.

## 9. Automatische Makrobildung

Wiederkehrende Aktionsfolgen werden zunächst in konkreten Spuren erkannt. Durch Anti-Unifikation oder Programmsynthese entsteht ein verallgemeinertes Makro

\[
\operatorname{guard}(s)\Longrightarrow[r_1(\cdot),r_2(\cdot),\dots,r_k(\cdot)].
\]

Ein Makro wird nur übernommen, wenn sein Nutzen auf zurückgehaltenen Aufgaben die zusätzlichen Suchkosten übersteigt. Eine mögliche Bewertungsgröße ist

\[
U(m)=
\text{eingesparte Sucharbeit}
-\lambda\,\text{Makrokomplexität}
-\mu\,\text{zusätzliche Verzweigung}.
\]

Zu messen sind mindestens:

- neu gelöste Aufgaben;
- Veränderung von Pfadlänge, expandierten Zuständen und erzeugten Kandidaten;
- tatsächliche Makronutzung statt bloßer Anwendbarkeit;
- Regressionen und Aufgaben, auf denen das Makro ungenutzt bleibt;
- Bildungskosten, Validierungskosten und Break-even über einen eingefrorenen Aufgabenstrom.

Damit wird ein häufiges Muster nicht allein durch Häufigkeit zur Strategie. Es muss außerhalb seiner Entstehungsspuren reproduzierbar Sucharbeit einsparen.

## 10. Suche nach neuen elementaren Regeln

Ein langsamer äußerer Prozess kann neue Regelkandidaten erzeugen:

1. Terme werden bis zu einer klaren Komplexitätsgrenze enumeriert.
2. Semantisch äquivalente Terme werden gruppiert.
3. Konkrete Termpaare werden zu allgemeineren Mustern anti-unifiziert.
4. Notwendige Typ- und Nebenbedingungen werden synthetisiert.
5. Ein unabhängiger Prüfer beweist oder widerlegt den Kandidaten.
6. Redundanz gegenüber vorhandenen Regeln wird kanonisch geprüft.
7. Der Kandidat wird auf einem vorab eingefrorenen Aufgabenstrom bewertet.

Die KI darf Regeln erzeugen und ordnen, aber niemals die eigene Ausgabe als Beweis akzeptieren. Ein nicht bewiesener Kandidat bleibt Hypothese und darf nicht als äquivalenzerhaltende Produktionsregel kompiliert werden.

## 11. Interessantheit und Fruchtbarkeit

Bei zielgerichteten Beweisen ist Erfolg klar definiert. Bei offener Discovery reicht Wahrheit nicht aus. Die Bewertung eines neuen Lemmas oder einer Regel soll deshalb mehrere getrennte Dimensionen behalten:

- formale Gültigkeit;
- Beschreibungslänge und Kompression;
- Projekt- und externe Neuheit;
- Zahl neu lösbarer Aufgaben;
- eingesparte Sucharbeit in späteren Beweisen;
- Übertragbarkeit auf größere oder strukturell andere Ausdrücke;
- zusätzlicher Verzweigungsfaktor;
- menschlich oder unabhängig bewertete mathematische Bedeutung.

Eine besonders nützliche operationale Größe ist **Fruchtbarkeit**: Wie stark verkürzt die Aufnahme eines Kandidaten spätere Herleitungen, und welche Resultate werden dadurch erstmals erreichbar? Diese Messung ersetzt keine externe mathematische Bewertung, liefert aber einen reproduzierbaren internen Nutzenbegriff.

## 12. Komponenten und Schnittstellen

Ein erster Prototyp benötigt folgende klar getrennte Bausteine:

1. **Action Enumerator** erzeugt alle formal zulässigen Regelinstanzen eines Budgetpräfixes in stabiler Reihenfolge.
2. **Canonical State Store** normalisiert Zustände, erkennt Duplikate und verwaltet gegebenenfalls E-Klassen.
3. **Feature Extractor** erzeugt versionierte lokale, globale, Ziel-, Historien- und Kostenmerkmale.
4. **Policy Ranker** bewertet konkrete Aktionen.
5. **Value Estimator** schätzt Erfolg und verbleibende Kosten eines Folgezustands.
6. **Fair Scheduler** verbindet Priorität, Exploration, progressive Erweiterung und harte Ressourcenlimits.
7. **Formal Executor** wendet Regeln ausschließlich nach Typ-, Pattern- und Nebenbedingungsprüfung an.
8. **Trace and Evidence Store** hält alle Entscheidungen, Modelle, Hashes, Seeds und Budgets replaybar fest.
9. **Macro Miner** abstrahiert wiederkehrende erfolgreiche Teilprogramme.
10. **Promotion Gate** trennt Vorschlag, experimentelle Nutzung und produktive Freigabe.

Alle lernenden Artefakte benötigen versionierte Identitäten für Modell, Feature-Schema, Trainingspartition, Regelbestand und Laufzeitkonfiguration. Ein Score ohne diese Bindungen ist nicht reproduzierbar und darf keine Promotion autorisieren.

## 13. Referenzexperiment

Der erste belastbare Versuch soll bewusst klein bleiben, beispielsweise in einer äquationalen Mikrowelt kommutativer Halbringe.

Verglichen werden:

| Variante | Suchsteuerung |
|---|---|
| A | faire ungeführte Enumeration |
| B | handgeschriebene Strategieregeln |
| C | gelernter Ranker mit ausschließlich lokalen AST-Merkmalen |
| D | gelernter Ranker mit globalem AST- und Zielkontext |
| E | D plus Value-Modell und Kostenmodell |
| F | E plus automatisch gelernte Makrostrategien |

Der Aufgabenbestand wird vor der Kandidatenbewertung in TRAIN, VALIDATION und FINAL TEST partitioniert. Zusätzlich werden größere ASTs und zurückgehaltene Strukturcluster verwendet, damit reine Musterwiederholung sichtbar wird.

Gemessen werden mindestens:

- Lösungsquote unter identischen Budgets;
- expandierte Zustände und erzeugte Kandidaten;
- Laufzeit und Peak-Speicher;
- E-Graph-Größe, falls verwendet;
- Länge und Replaybarkeit der Herleitung;
- Anteil der Lösungen aus Reserveexploration;
- Kalibrierung von Policy- und Value-Schätzungen;
- Bildung, Validierung und Break-even neuer Makros;
- Korrektheits-, Reichweiten- und Performance-Regressionen.

Eine zentrale Ablation vergleicht harten Filter mit weicher Priorisierung plus fairer Exploration. Eine zweite entfernt den globalen Kontext. Eine dritte misst, ob Makros wirklich neue Aufgaben lösen oder lediglich bekannte Pfade verkürzen.

## 14. Erfolgskriterien

Der Prototyp gilt nicht schon dann als Erfolg, wenn er einzelne bekannte Formeln findet. Er muss gegenüber den Baselines zeigen:

1. reproduzierbar geringere Sucharbeit auf ungesehenen Aufgaben;
2. keine Verschlechterung der formalen Korrektheit;
3. keine Ausnutzung von TEST-Information bei Strategie- oder Kandidatenbildung;
4. kontrolliertes Wachstum von Aktionsraum und Modellkosten;
5. nachvollziehbare negative Ergebnisse und nicht nur ausgewählte Erfolge;
6. einen dokumentierten Bereich, in dem die gelernte Strategie generalisiert;
7. einen klaren Punkt, an dem der Ansatz weiterhin exponentiell oder unbrauchbar skaliert.

Ein negatives Ergebnis ist wissenschaftlich verwertbar, wenn es Repräsentation, Strategy Learning, Verifikation und Skalierung getrennt diagnostiziert.

## 15. Risiken

### Policy-Kollaps

Das Modell bevorzugt wenige bekannte Regelklassen und verliert ungewöhnliche Pfade. Gegenmaßnahmen sind faire Reserveexploration, Entropie- oder Diversitätsziele und zurückgehaltene Strukturcluster.

### Reward Hacking

Ein Modell optimiert eine Proxy-Metrik, ohne mathematischen Nutzen zu erhöhen. Harte Korrektheitsgates, getrennte Metriken und unveränderliche Roh-Evidence verhindern, dass ein einzelner Score die Bewertung ersetzt.

### Datenleck

Ziel- oder Testinformationen gelangen in Kandidatenbildung oder Feature-Berechnung. Partitionen, Zugriffspolitiken und Artefakthashes müssen deshalb Teil der Typ- und Evidence-Verträge sein.

### Makroinflation

Zu viele Makros vergrößern den Aktionsraum stärker, als sie Pfade verkürzen. Promotion erfordert daher gemessenen Netto-Nutzen und kann veraltete Makros wieder deaktivieren, ohne ihre Historie zu löschen.

### Unverifizierte KI-Ausgabe

Generierte Regeln oder Zwischenziele werden fälschlich als Wahrheit behandelt. Die Architektur muss technisch verhindern, dass ein Modell Beweisstatus setzen oder formale Gates umgehen kann.

### Unvergleichbare Experimente

Änderungen an Regeln, Aufgaben, Features oder Budgets machen Ergebnisse scheinbar besser. Jeder Lauf bindet deshalb sämtliche Inputs und Konfigurationen über kanonische Hashes.

## 16. Umsetzung in Phasen

### Phase 0: Release-Stabilisierung

Vor Beginn der neuen Sucharchitektur wird der aktuelle Softwarestand eingefroren, vollständig mit `ciCheck` geprüft und als nachvollziehbare Referenz veröffentlicht. Die Forschungsarbeit darf diesen Release Candidate nicht destabilisieren.

### Phase 1: Instrumentierung

Der bestehende Suchkern erhält eine vollständige Ereignisschnittstelle für Zustände, konkrete Regelinstanzen, Kosten, Queue-Entscheidungen und Endgründe. Noch wird nichts gelernt.

### Phase 2: Handgeschriebener Scheduler

Ein expliziter Strategievertrag trennt Action Enumeration von Priorisierung. Ungeführte Enumeration bleibt als Referenz und Reserve erhalten.

### Phase 3: Lernender lokaler Ranker

Ein kleines Modell bewertet vorhandene Regelinstanzen. Es darf weder neue Regeln erzeugen noch Aktionen hart entfernen.

### Phase 4: Globaler Kontext und Value-Modell

Ziel, gesamte Ausdrucksstruktur, Historie und Kosten werden integriert. Der Nutzen gegenüber der lokalen Variante wird isoliert gemessen.

### Phase 5: Checkpoints und Makros

Erfolgreiche Spuren werden zu Kandidaten abstrahiert. Bildung, Validierung, Holdout-Nutzung und Amortisierung bleiben getrennte Evidence-Schichten.

### Phase 6: Regel- und Lemmakandidaten

Erst nach stabiler Suchsteuerung wird der äußere Entdeckungsprozess für neue Regeln, Lemmata und Begriffe geöffnet.

## 17. Entscheidungsregel für die weitere Forschung

Die Architektur wird nur erweitert, wenn jede zusätzliche Ebene auf eingefrorenen Aufgaben einen nachweisbaren Nutzen liefert. Insbesondere gilt:

- Schlägt bereits lokale Erreichbarkeit fehl, liegt das Problem bei Regeln, Mutation oder Verifikation.
- Funktionieren lokale Aufgaben, aber nicht größere Strukturen, liegt das Problem wahrscheinlich bei Repräsentation oder globalem Kontext.
- Verbessert ein Ranker nur bekannte Trainingsmuster, aber keine zurückgehaltenen Cluster, ist keine belastbare Strategie gelernt.
- Verkürzen Makros Pfade, amortisieren aber ihre Bildungs- und Validierungskosten nicht, bleiben sie experimentell.
- Verlangt jede neue Domäne so viel manuelle Struktur, dass der wesentliche Entdeckungsschritt bereits vorgegeben ist, muss der Anspruch eines allgemeinen Systems eingeschränkt werden.

## 18. Kernaussage

Die tragfähige Weiterentwicklung lautet nicht „mehr Brute Force“, sondern:

> **formal vollständige und überprüfbare Enumeration im Hintergrund, lernende hierarchische Verteilung des Suchbudgets im Vordergrund.**

Die innere Schleife erzeugt und priorisiert konkrete formal zulässige Schritte. Die äußere Schleife komprimiert erfolgreiche Verläufe zu Strategien, Makros und geprüften Regelkandidaten. Dadurch bleibt die mathematische Korrektheit symbolisch kontrolliert, während KI dort eingesetzt wird, wo sie ihren größten Nutzen verspricht: bei Auswahl, Planung, Abstraktion und Exploration.