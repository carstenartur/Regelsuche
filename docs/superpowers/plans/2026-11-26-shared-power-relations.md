# Gemeinsame Potenzen unter expliziten Ausgabeverträgen

## Ziel und Vertrag

Der mathematische Kern soll aus mehreren Potenzanforderungen zur gleichen
Gruppenbasis einen gemeinsamen Rechenplan erzeugen. Eingabe ist die Annahme
`x^n = 1` sowie pro Ausgabe ein Exponent `e` und ein Teiler `k` von `n`.
Die freigegebene Beobachtung ist ausdrücklich `y^k = (x^e)^k`.
`k=1` fordert Gleichheit; `k=2` erlaubt in einem Körper ungerader
Charakteristik die beiden Vorzeichen. Eine spätere Normalisierung ist eine
zusätzliche Pflicht des Consumers, keine stillschweigende Folgerung.

Die Fähigkeit kennt weder BLS noch eine bestimmte Primzahl. Der ausführbare
modulare Adapter prüft die konkrete Gruppenannahme vor jeder Auswertung.

## Schritte

1. Tests für gemeinsame exakte Potenzen, Quadrat- und Kubikäquivalenz,
   Quellenbindung, falsche Gruppenannahmen und Ressourcenabbruch vorlegen.
2. Unveränderliche, begrenzte Quell- und Planwerte im mathematischen Kern
   ergänzen. Separater Prüfer rekonstruiert Exponenten modulo `n` und prüft
   `n | k(v-e)` für jede Ausgabe, ohne die Suche aufzurufen.
3. Quellengetriebenes endliches Portfolio: direkte Potenzen, Wiederverwendung
   von Potenzen/Kehrwerten/kleinen Verschiebungen, zusätzlich reduzierte
   Vertreter modulo `n/k`. Keine Optimalitätsbehauptung.
4. Einmalige Such- und Prüfarbeit gemeinsam begrenzen und getrennt ausweisen.
   Operationskosten sind ein explizites Binärkettenmodell; Laufzeitgewinne
   müssen der konkrete Consumer messen.
5. SDK ausschließlich delegierend ergänzen. Mathematische Beispiele,
   unabhängige Auswertung und Negativtests ausführen; Strategien-Index pflegen.
6. Geänderte Haupt- und Testquellen mit `-Xlint:all -Werror` prüfen,
   unabhängiges Review einarbeiten und kleinen separaten PR veröffentlichen.

## Grenzen

Der Plan erhält einzelne Potenzbeobachtungen. Er beweist keine Korrelation
zwischen mehreren erlaubten Vorzeichen und keine allgemeine Identität eines
größeren Programms. Für eine Fp²-Wurzel sind die Normannahme, die Rotation im
negativen Zweig und die kanonische Vorzeichenwahl separat zu begründen.
Der endliche Suchraum liefert weder globale minimale Additionsketten noch
eine allgemeine algebraische Entdeckungsgarantie.

## Ausgeführte Prüfung

- Die neuen Kerntests scheiterten zunächst an der fehlenden API. Nach der
  Implementierung bestehen 14 Kerntests und der SDK-Delegationstest.
- Der abschließende Lauf der mathematischen Algorithmen und der
  Optimierungs-SDK-Tests meldet 892 beziehungsweise 361 Tests, keine Fehler
  und einen bestehenden optionalen Skip im externen Rekurrenzvergleich.
- Die zusätzlichen regulären Maven-Ausführungen übersetzen die neuen
  Haupt- und Testquellen mit `-Xlint:all -Werror`. Ein anfängliches separates
  Ausgabeziel änderte den Maven-Artefaktpfad und wurde korrigiert; die
  erfolgreich geprüften Ausführungen verwenden die regulären Ausgabeziele.
- Ein zusätzlicher zunächst fehlgeschlagener SDK-Test verlangte die
  Beachtung von `maximumCandidates`. Der Kern begrenzt nun das Portfolio
  explizit; das SDK reicht diese Grenze weiter.
- Ein unabhängiges Review prüfte Mathematik, gemischte Beobachtungen,
  Quellenbindung, Ressourcenabbrüche und die stärkere exakte Vergleichsrechnung.
  Die Studie enthält sechs deterministisch reproduzierbare Quellfälle;
  Codeverweise und `git diff --check` wurden geprüft.
