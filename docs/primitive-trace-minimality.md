# Kürzeste primitive Verbindungen vor der Regelbildung prüfen

Eine gefundene Herleitung belegt zunächst nur, dass ein Weg existiert. Ihre
Länge darf erst dann als primitive Mindestlänge gespeichert werden, wenn kürzere
Verbindungen ausgeschlossen wurden. `TraceRewriteStrategyLearner` verlangt
diese Prüfung seit Version 2 vor der Bildung gelernter Umformungsfolgen.

| Prüfergebnis | Konsequenz für die gelernte Folge |
| --- | --- |
| Beobachtete Folge ist nachweislich kürzest | Die geprüfte Folge darf übernommen werden. |
| Kürzerer Weg gefunden und als kürzest nachgewiesen | Seine wirklichen primitiven Schritte ersetzen die beobachtete Folge. |
| Verbindung benötigt null oder einen Schritt | Kein neues mehrstufiges Makro. |
| Prüfung erreicht eine Ressourcengrenze | Minimum bleibt ungeklärt; keine Übernahme. |
| Technischer Fehler | Keine Freigabe; der Trainingslauf meldet den Fehler. |

Die ursprüngliche Suchspur bleibt erhalten, auch wenn sie verkürzt oder
abgelehnt wird. Ein Modell kann dadurch beispielsweise einen beobachteten
20-Schritt-Weg und daneben den bewiesenen Zwei-Schritt-Ersatz dokumentieren,
ohne dem Umweg einen künstlichen Wert zuzuweisen.

## Was der Nachweis bedeutet

`PrimitiveTraceMinimalityVerifier` bindet die konkreten Start- und Endausdrücke,
die Richtung, die Definition eines atomaren Schritts und den Inhalt des
Regelinventars. Ein atomarer Schritt ist hier die gerichtete Anwendung eines
kompilierten primitiven Gens an einer AST-Stelle. Bereits zusammengefasste
Programme und Theorieaktionen zählen nicht als einzelne atomare Alternativen.
Die Vergleichsregeln bleiben während der Prüfung unverändert; das neue Makro
wird nicht als seine eigene Abkürzung zugelassen.

Nach vollständigem Replay der beobachteten Folge untersucht der vorhandene
`BoundedReachabilityOracle` alle kürzeren primitiven Verbindungen. Bei einer
20-Schritt-Folge reicht dafür die Suche bis Tiefe 19. Der Oracle ordnet die
Frontier nach primitiver Länge. Ein gefundener Zielzustand liefert deshalb einen
kürzesten Zeugen; eine vollständig ausgeschöpfte kürzere Hülle belegt, dass der
bereits geprüfte 20-Schritt-Weg minimal ist.

Zustände werden anhand formatierter Syntax unterschieden. Eine algebraische
Normalform darf äquivalente, aber verschieden dargestellte Endpunkte nicht zu
einer angeblichen Null-Schritt-Verbindung zusammenziehen. Heuristische
Suchbewertung, Kandidatenauswahl, Wiederholungsverbote, Expansionsbudget und
AST-Wachstumsfilter der ursprünglichen Suche beschneiden diesen Nachweis nicht.

Zustands-, Übergangs-, Kandidaten-, AST-Größen- und Arbeitsgrenzen begrenzen nur
die Ausführung der Prüfung. Werden sie erreicht, entsteht **kein** Beweis für
die Abwesenheit kürzerer Wege. Eine zusätzliche Kandidatenposition erkennt
abgeschnittene Kandidatenmengen ausdrücklich. Die AST-Grenze verwendet die
wirkliche Syntaxgröße vor algebraischer Normalisierung.

## Reichweite und Kosten

Die Bestätigung gilt für die gespeicherten Endpunkte und das festgehaltene
atomare Inventar. Ändert sich dieses Inventar, kann der Nachweis nicht einfach
weiterverwendet werden. Eine spätere spezialisierte Variablenbelegung kann
zusätzliche Kürzungen ermöglichen; aus einer minimalen Trainingsfolge folgt
keine Mindestlänge für jede Anwendung eines generalisierten Programms.

Der Zielausdruck der Prüfung stammt ausschließlich aus der bereits ausgeführten
zielausdrucksfreien TRAIN-Suche. Es werden keine Anwendungsaufgaben oder extern
vorgegebenen Zielbeweise zur Regelbildung herangezogen. Die exakte Prüfung der
mathematischen Identitäten bleibt eine eigene Voraussetzung.

Das Modell speichert den ursprünglichen Pfad, den kürzesten Zeugen, Status,
Grenzen, den vollständig behaltenen Prüfgraphen und gezählte Prüfarbeit. In den
Demopaketen liegen dafür auch einzelne `.minimality.json`-Dateien. Die Kosten
abgelehnter Prüfungen bleiben in der Lernbilanz. Parser-, Compiler- und weitere
CPU-Kosten werden durch diese Ereigniszählung nicht vollständig abgebildet.

Im [festen Entwicklungsvergleich](conditional-strategy-dispatch.md) werden die
drei übernommenen TRAIN-Spuren als kürzeste Verbindungen mit zwei, drei und drei
primitiven Schritten bestätigt. Die zusätzliche Prüfung kostet 84 gezählte
Einheiten. Die Anwendungswerte aller vier Profile bleiben unverändert; die
gesamten Lernkosten steigen von 1.082 auf 1.166 Einheiten. Die
[ursprünglichen Referenzen](generated/strategy-history-v1/trace-strategy-dispatch-reference.md)
bleiben als Version 1 erhalten. Das erneute Ausführen derselben 288 Aufgaben
stellt keinen neuen unabhängigen Leistungsnachweis dar.

Die Tests enthalten einen echten 20-Schritt-Weg mit nachgewiesener
Zwei-Schritt-Abkürzung, einen bestätigten minimalen 20-Schritt-Weg, veränderte
Inventare, ungültige Spuren, verpackte Makros, sämtliche Ressourcengrenzen und die
tatsächliche Ablehnung beziehungsweise Verkürzung durch den Lernprozess.
