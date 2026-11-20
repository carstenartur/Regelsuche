# Wann eine Hall-Untergrenze durch neue Beweise wächst

Dokumentstatus **ERGEBNIS**. Stand 20. November 2026.
Die folgenden endlichen Graphensätze sind bewiesen. Sie liefern keine
uniforme Positivität für eine arithmetische Familie von Eingabegraphen.
Ein Neuheitsanspruch gegenüber der Matching-Literatur wird nicht erhoben.

Die [bestehende Matching-Fähigkeit](bipartite-matching.md) berechnet und prüft
die maximale Hall-Defizienz. Hier wird präzisiert, wie dieser Wert auf
bewiesene Änderungen der Anwendung reagiert. Die Sätze benötigen keine neue
CORE- oder SDK-Schnittstelle: Zertifizierte Aufrufe von `search` und `verify`
für den alten und den veränderten Graphen reichen aus. Eine automatische
Auswahl mathematischer Beweisschritte wird dadurch nicht implementiert.

## 1. Feste Grundmenge und sichere Zählung

Sei $U_0$ eine feste endliche Grundmenge und $T\subseteq U_0$ die gesuchte
Teilmenge. Ein Zustand enthält disjunkte Mengen $A,U$ mit

$$A\subseteq T\subseteq A\cup U\subseteq U_0.$$

$A$ enthält bereits bewiesene und gezählte Treffer; $U$ enthält die noch
offenen Kandidaten. Für $\Gamma=(U,Q,E)$ sei begründet, dass sämtliche
Nichttreffer $U\setminus T$ durch ein Matching in $\Gamma$ abgedeckt werden
können. Das setzt vollständige mögliche Kanten und Kapazität eins auf der
rechten Seite voraus. Ihre Herkunft bleibt Sache der Anwendung.

Mit $\nu(\Gamma)$ als größtmöglicher Matchinggröße gilt

$$|T|\ge L:=|A|+\delta(\Gamma),\qquad
\delta(\Gamma)=|U|-\nu(\Gamma).\tag{1}$$

**Beweis.** Höchstens $\nu(\Gamma)$ Kandidaten aus $U$ können Nichttreffer
sein. Die übrigen gehören zu $T$ und sind von den schon gezählten Punkten
aus $A$ verschieden. Der bestehende Zertifikatsbeweis zeigt außerdem
$\delta(\Gamma)=\max_{W\subseteq U}(|W|-|N(W)|)$. Es handelt sich um die
optimale Hall-Untergrenze des Graphen, nicht nur um das Defizit seiner
gesamten linken Seite. $\square$

## 2. Monotonie bei Aufnahmen, Sperrungen und Kantenlöschungen

**Satz.** Ein Beweisschritt bestätigt disjunkte Mengen $F,C\subseteq U$
mit $F\subseteq T$ und $C\cap T=\varnothing$. Setze

$$A'=A\cup F,\qquad U'=U\setminus(F\cup C).$$

Sei $R\subseteq Q$. Der alte Graph enthalte ein Matching aller Punkte
von $C$ in $R$. Der neue Graph $\Gamma'$ sei ein Teilgraph von
$\Gamma[U',Q\setminus R]$ und weiterhin vollständig für die Nichttreffer
aus $U'$. Dann gilt

$$L'-L=\nu(\Gamma)-\nu(\Gamma')-|C|\ge0.\tag{2}$$

**Beweis.** Jedes Matching des neuen Graphen lässt sich um das Matching
$C\to R$ erweitern. Beide verwenden disjunkte linke und rechte Knoten.
Daher ist $\nu(\Gamma)\ge\nu(\Gamma')+|C|$. Ferner gilt

$$L'=|A|+|F|+|U|-|F|-|C|-\nu(\Gamma').$$

Subtraktion von (1) liefert (2). Weitere begründete Kantenlöschungen sind
bereits durch die Teilgraphbedingung erfasst. $\square$

Für die reine Graphenungleichung genügt ein Matching $C\to R$ im alten
Graphen. Eine Anwendung muss zusätzlich die Wahrheit ihrer Aufnahmen und
Entfernungen sowie die Vollständigkeit des neuen Graphen begründen. Bei
einer zeitabhängigen Sperre reicht eine erst später bekannte fehlende
Klasse allein nicht zum Nachweis, dass diese Regel den entfernten Kandidaten
schon zu dessen Zeitpunkt gesperrt hat.

## 3. Drei genaue Kriterien für strikten Gewinn

Die folgenden Aussagen betreffen jeweils **nur** die genannte Änderung.
Zusätzliche gleichzeitige Kantenlöschungen können weiteren Gewinn erzeugen;
dann charakterisieren die Kriterien nicht mehr dessen gesamte Ursache.
Mit einem maximalen Matching ist hier stets ein Matching **größtmöglicher
Kardinalität** gemeint.

| Bewiesene Änderung | Der Wert $L$ wächst genau dann um eins, wenn … |
|---|---|
| Eine mögliche Kante $e$ wird entfernt | jedes alte maximale Matching $e$ verwendet. |
| Eine tatsächliche Nichttreffer-Zuordnung $e=(u,q)$ wird bestätigt; beide Endpunkte werden entfernt | kein altes maximales Matching $e$ verwendet. |
| Ein echter Treffer $u$ wird aus $U$ nach $A$ verschoben | jedes alte maximale Matching $u$ abdeckt. |

In allen übrigen Fällen bleibt $L$ bei der jeweiligen einzelnen Änderung
gleich.

**Kantenentfernung.** Das Löschen von $e$ senkt die Matchingzahl um höchstens
eins. Sie bleibt genau dann gleich, wenn ein altes maximales Matching ohne
$e$ existiert. Da die Zahl linker Kandidaten gleich bleibt, ist ihre
Abnahme genau der Zuwachs von $L$.

**Bestätigte Zuordnung.** Schreibe $\nu'=\nu(\Gamma-\{u,q\})$.
Jedes verbleibende Matching kann um $e$ ergänzt werden. Aus einem alten
maximalen Matching gehen beim Entfernen der beiden Endpunkte höchstens zwei
Kanten verloren. Somit

$$\nu(\Gamma)-2\le\nu'\le\nu(\Gamma)-1.$$

Der obere Wert wird genau dann erreicht, wenn ein altes maximales Matching
$e$ enthält: Man kann es ohne $e$ einschränken beziehungsweise ein
verbleibendes Matching um $e$ ergänzen. Gleichzeitig sinkt $|U|$ um eins.
Der Gewinn ist deshalb $\nu(\Gamma)-\nu'-1$, also genau dann eins, wenn
kein altes maximales Matching $e$ enthält.

**Aufnahme.** Beim Verschieben von $u$ wächst $|A|$ um eins und sinkt $|U|$
um eins. Der Gewinn ist daher
$\nu(\Gamma)-\nu(\Gamma-u)$. Diese Differenz ist null oder eins und ist
genau dann eins, wenn kein altes maximales Matching $u$ frei lässt.
$\square$

**Gemeinsamer Ausschluss mehrerer Kanten.** Für $B\subseteq E$ gilt
$\nu(\Gamma-B)<\nu(\Gamma)$ genau dann, wenn jedes alte maximale
Matching mindestens eine Kante aus $B$ enthält. Denn ein altes maximales
Matching bleibt genau dann erhalten, wenn es $B$ vermeidet. Einzelne Kanten
aus $B$ müssen dafür nicht jeweils in allen maximalen Matchings liegen.
Der Gewinn kann bei einer solchen gemeinsamen Entfernung größer als eins
sein. Zum Nachweis genügen wieder zwei zertifizierte Matchinggrößen.

## 4. Gegenproben zu stärkeren Fortschrittsbehauptungen

### Viele Ausschlüsse können keinen Gewinn liefern

Beginne mit $K_{n,n}$ und behalte ein festes diagonales perfektes Matching.
Alle $n^2-n$ übrigen Kanten dürfen nacheinander verschwinden. Währenddessen
bleiben sämtliche linken Knoten zuordenbar, kein Knoten wird isoliert und
$\delta=0$. Anschließend können die diagonalen Zuordnungen bestätigt und
ihre Endpunkte paarweise entfernt werden; auch dann bleibt $\delta=0$.
Schon das Entfernen einer Kante aus $K_{2,2}$ erhöht die Defizienz nicht.

Dies ist ein abstraktes Graphenbeispiel. Es konstruiert weder eine
arithmetische Sperrfamilie noch ein Gegenbeispiel zu einer Greedy-Vermutung.
Es widerlegt einen Schluss allein aus der Monotonie oder aus einer großen
Zahl begründeter Ausschlüsse.

### Eine Aufnahme kann eine schon bekannte Existenz konkretisieren

Zwei linke Knoten mit einem gemeinsamen rechten Nachbarn haben Defizienz
eins. Wird einer als echter Treffer bestätigt, wechselt
$(|A|,\delta)$ von $(0,1)$ zu $(1,0)$. Die Gesamtuntergrenze bleibt eins.
Die Aufnahme ist neue Information über die Identität des Treffers, aber
keine zusätzliche Zählgarantie.

### Eine Nichttreffer-Löschung allein bewahrt den neuen Graphwert nicht

Im selben Zweierstern sei stattdessen der erste linke Knoten als Nichttreffer
bewiesen. Entfernt man ihn, behält aber die überapproximierende Kante des
anderen Knotens bei, fällt die neu berechnete Defizienz von eins auf null.
Der alte Beweis bleibt gültig und erzwingt zusammen mit der Nichttreffer-
Information den zweiten Punkt. Der neue Graph allein bewahrt diese gesamte
logische Information nicht. Ohne das Matching $C\to R$ aus Abschnitt 2
muss man eine ältere zertifizierte Untergrenze deshalb separat behalten.

## 5. Verwendung der bestehenden CORE- und SDK-Aufrufe

Für eine Diagnose werden der ursprüngliche Graph und sein veränderter
Graph jeweils als eigene `BipartiteMatching.Request` angelegt. Bei einer
Knotenentfernung muss die Anwendung die verbleibenden Knoten bijektiv neu
indizieren und diese Zuordnung behalten. Das bloße Löschen inzidenter
Kanten bei unveränderter linker Seitengröße würde einen isolierten Knoten
erzeugen und eine andere Defizienz messen.

Für beide Quellen werden `search` und anschließend `verify` aufgerufen.
Nur zwei vollständig verifizierte Zertifikate erlauben den Vergleich der
Matchinggrößen. `UNAVAILABLE`, Abbruch oder eine erschöpfte Prüfgrenze
beweisen keine der Aussagen „in jedem“ beziehungsweise „in keinem maximalen
Matching“. Dieselbe Regel gilt beim Vergleich ganzer Gruppen von
Kantenlöschungen.

Dies ist eine dokumentierte Zusammensetzung der vorhandenen Fähigkeiten.
Eine besondere Sensitivitäts-API, ein inkrementeller Matchingalgorithmus
oder ein Laufzeitvorteil werden hier nicht behauptet. Der mathematische
Nutzen liegt im präzisen Suchziel: Für einen Zählgewinn müssen die neuen
Beweise die bisher optimalen Zuordnungen so einschränken, dass die passende
Matchingzahl tatsächlich fällt.

## 6. Grenze für uniforme Beweisaufgaben

Ein endliches Matchingzertifikat beweist eine Aussage über seinen exakt
gebundenen Graphen. Die Monotonie beweist, dass eine korrekt verbuchte
Verfeinerung einen schon erzielten Wert erhält. Beides liefert noch keinen
positiven Wert für jede Instanz einer unendlichen arithmetischen Familie.
Hierfür benötigt die Anwendung einen zusätzlichen Satz, der die nötigen
wirksamen Änderungen aus ihren mathematischen Voraussetzungen erzwingt.

Die Greedy-Anwendung und ein direkter alternativer Beweisansatz sind in der
[Fortsetzung im Primachsenraum](https://github.com/carstenartur/primachsenraum/blob/codex/hall-progress-proof/docs/research/greedy-proof-frontier.md)
festgehalten. Ihre uniforme Sättigungsfrist bleibt offen.
