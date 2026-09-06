# ADR: Architektur konsolidieren, beginnend mit exakter Zahlensemantik

Datum: 2026-09-06

Status: laufende Konsolidierung; erster und zweiter begrenzter Schnitt

Ausgangsrevision: `4a52e933594c4eca9adb10f6f601e325bc624f2e`

Eigentuemer der Zahlensemantik: [#661](https://github.com/carstenartur/Regelsuche/issues/661)

## Entscheidung

Regelsuche wird als modulare Anwendung konsolidiert, nicht neu geschrieben und
nicht ohne konkreten Bedarf in verteilte Dienste zerlegt. Aktuelle interne APIs,
Konstruktoren und Paketgrenzen duerfen brechen. Historische Beweis- und
Experimentartefakte behalten dagegen ihre gebundene Bedeutung und Revision.

Die Reihenfolge ist:

1. Eine exakte Zahlensemantik auf Basis des vorhandenen `ExactRational` durch
   Parser, AST, Pattern, Wertidentitaet, Transformation und Replay ziehen.
2. Wiederverwendbare Fachlogik und Erweiterungsvertraege aus `app` loesen und
   durch externe Consumerprojekte charakterisieren; dabei keine gleichartigen
   neuen APIs neben den bestehenden einfuehren.
3. Gemeinsame Anwendungsfaelle und Laufkontexte fuer Web und CLI verwenden;
   mathematischen Zustand, Suchsteuerung und Pfad/Evidence trennen.
4. Den begonnenen Maven-Vertrag vollstaendig qualifizieren und danach die
   ueberholte Build-Autoritaet entfernen, nicht vorher.

Die bestehenden Issues bleiben Eigentuemer: #661 fuer Werte und Suchidentitaet,
#904 fuer den SDK-Einstieg, #900 fuer typisierte Ausfuehrungsherkunft, #669 fuer
Lauf/Workbench-Korrelation und #749 fuer die Maven-Konsolidierung. Dieser ADR
legt keine zweite, konkurrierende Roadmap oder zusaetzliche Modulpflicht fest.

## Reproduzierte Ausgangsfehler

Der gewoehnliche Parser und `NumberExpr(double)` verlieren Informationen, die
`parseExactTerm` noch in node-gebundener Literalprovenienz bewahrt. So werden
`9007199254740992` und `9007199254740993` ohne den exakten Begleiter ununterscheidbar.
Ein exakter Verifier darf diese gerundete AST-Identitaet nicht als Zahlenbeweis
verwenden.

Der gewoehnliche Formatter verengt grosse ganzzahlige Double-Werte auf `long`:
`100000000000000000000` erscheint dadurch als `9223372036854775807`. Bei kleinen
Werten erzeugt er ausserdem Exponentialnotation, die der Parser nicht akzeptiert.

Direktes Literal-Matching verwendet historisch eine numerische Toleranz: Das
Pattern `A + 0` konnte deshalb `1 + 0.0000000001` akzeptieren. Die fruehere
algebraische Monomial-Inferenz nutzte dieselbe Art von Toleranz sowie
Gleitkomma-Wurzeln und konnte dadurch beispielsweise eine gerundete `sqrt(2)`-
Naeherung als exakte Bindung behandeln. Symbolische Division konnte ausserdem
`x/x` ohne Nichtnull-Annahme wie die Konstante `1` erscheinen lassen.

## Erster Sicherheits-Schnitt: bewusst nicht die exakte AST-Migration

Der erste Schnitt aendert nur bestehende numerische Randbedingungen:

- `ExpressionParser`: Im gewoehnlichen Pfad muss ein Literal unter der
  bestehenden kuerzesten Dezimaldarstellung des Double-Werts wertgleich
  zurueckgeschrieben werden koennen. Andernfalls wird es mit Quellposition
  abgewiesen. Der parsergebundene exakte Pfad bleibt unveraendert.
- `ExpressionFormatter`: Keine unkontrollierte `long`-Verengung und keine
  Exponentialnotation; nichtendliche numerische Blaetter werden abgewiesen.
- `EquivalenceAwarePatternMatcher`: Direkte Literalbedingungen verwenden keine
  Toleranz. Integer-Potenzinferenz verlangt einen tatsaechlich ganzzahligen,
  positiven Wert innerhalb des `int`-Bereichs, bevor die Verengung erfolgt.

Die Dezimal-Roundtrippruefung verwendet `BigDecimal.valueOf(double)`, **nicht**
`new BigDecimal(double)`. Damit bleibt die bisherige Dezimalkonvention, etwa fuer
`0.1`, erhalten. Dies ist keine Behauptung exakter binaerer Darstellbarkeit oder
exakter Double-Arithmetik. Schreibweisen mit fuehrenden bzw. nachgestellten
Nullen duerfen dieselbe Zahl bezeichnen. Das bestehende Normalisieren von
negativem Nullwert wird nicht zu einer neuen mathematischen Unterscheidung.

Das Ablehnen nicht roundtrippender Eingaben ist nur die sofortige
Sicherheitskorrektur. Im Zielmodell muss der normale exakte Pfad grosse Zahlen
innerhalb ausdruecklicher Ressourcenlimits korrekt verarbeiten, nicht dauerhaft
abweisen. Dann wird diese temporaere Zulassungsschranke ersetzt.

## Zweiter Schnitt: exakte, begrenzte Monomial-Inferenz

Die algebraische Inferenz des `EquivalenceAwarePatternMatcher` verwendet fuer
Monomialkoeffizienten den vorhandenen `ExactRational`-Vertrag. Der
package-private Helfer `BoundedExactMonomial` besitzt nur die eng begrenzte
Verantwortung fuer Monomialprojektion, exakte Koeffizientenrechnung und
ueberpruefte Potenzbindungen. Es gibt keinen zweiten Parser, Suchalgorithmus,
oeffentlichen Zahlentyp oder konkurrierenden Matcher.

Produkte, konstante Divisionen und positive ganzzahlige Potenzen werden rational
exakt ausgewertet. Symbolische Nenner bleiben ohne explizite Nichtnull-Annahme
ausserhalb dieser Inferenz; `x/x` wird daher nicht stillschweigend zu `1`.
Strukturelles Matching von `A/A` bleibt davon getrennt und kann nur durch eine
Regel mit eigener Annahmenpruefung mathematische Kuerzung autorisieren.

Wurzeln autorisieren Bindungen nur, wenn Zaehler und Nenner perfekte
ganzzahlige Potenzen sind und alle Variablenexponenten teilbar sind. Eine
endliche binaere Ganzzahlsuche mit exaktem Potenzvergleich ersetzt
Gleitkomma-Wurzelvorschlaege. Die erzeugte Bindung wird danach erneut gegen den
Quellausdruck geprueft. Ein exakter nichtterminierender Bruch bleibt Bruchsyntax;
eine endliche Dezimaldarstellung wird nur ausgegeben, wenn der exakte
Rueckvergleich denselben rationalen Wert bestaetigt. So bleibt `3/2` im
syntaxgerichteten Suchpfad als `1.5` darstellbar, waehrend `2/3` Bruchsyntax
behaelt.

Alle algebraischen Vorfilter und Inferenzversuche eines `matchDetailed`-Aufrufs
teilen ein Budget von 10.000 Besuchen/Operationen, eine Projektionstiefe von 128
und konservative Koeffizientengrenzen von 4.096 Bit. Exponenten werden vor der
Verengung geprueft. Ueberschreitungen oder im Legacy-AST nicht exakt
darstellbare Bindungen liefern `INCONCLUSIVE` mit typisiertem Grund und
unveraenderten Caller-Bindings; Negation darf diesen Zustand nicht zu einem
Treffer machen. Diese Grenzen sind kein vollstaendiges CPU-/Sucharbeits-Ledger.

## Noch offene mathematische Grenzen

`NumberExpr`, `PatternExpr.LiteralNumber` und die allgemeine Wertprojektion
bleiben Double-basiert. Der zweite Schnitt macht nur die deklarierte
Monomial-Koeffizientenrechnung und Wurzelinferenz rational exakt; bereits vor
dieser Grenze verlorene Quelltextpraezision kann er nicht rekonstruieren.
Numerisches Falten, allgemeine Kanonisierung, Suchidentitaet, E-Graph,
Serialisierung und weitere Adapter sind damit noch nicht insgesamt exakt.

Die Migration muss den vorhandenen exakten Werttyp weiterverwenden. Sie darf
keinen weiteren Zahlen-Sidecar und keine dauerhafte `value(): double`-Fassade
als vermeintlich exakte Autoritaet einfuehren. Syntaxposition und Originaltext
bleiben fuer Darstellung und Herkunft erhalten, aber der Wert darf nicht nur
ueber diesen Begleiter exakt sein. Naeherungswerte brauchen einen ausdruecklich
anderen Vertrag und duerfen keine exakte Gleichheit autorisieren.

Ein geaendertes Format kann Ausdrucksbytes und daraus abgeleitete Identitaeten
veraendern. Historische Ergebnisdateien, versiegelte Qualifikationen und
Schwellen werden nicht automatisch aktualisiert, um die neue Implementierung
bestehen zu lassen. Betroffene Studien benoetigen eine ausdrueckliche
Versionsentscheidung oder ihre archivierte Implementierung.

## Abnahme und Regressionen

`NumericBoundaryRegressionTest` charakterisiert beide Parsereingaenge, grosse
und kleine Zahlen, nichtendliche Werte, negative Potenzbasen, deterministische
endliche Double-Bitmuster, direkte Literalbedingungen in allen drei Profilen,
eine reale `PatternRewriteRule` mit Replay/Wertprojektion, fraktionale und
uebergrosse Integerexponenten sowie begrenztes AC-Matching mit unveraenderten
Caller-Bindings und explizitem `INCONCLUSIVE`.

`ExactMonomialInferenceTest` prueft irrationale Wurzeln, rationale Bindungen,
exakte Dezimalprodukte, symbolische Nenner, Grenzfaelle, Replay und die skalierte
quadratische Ergaenzung. Eine separate endliche Referenz prueft 3.078
ganzzahlige und 1.300 rationale Wurzelfaelle durch wiederholte Multiplikation.
Die bestehenden Ableitungs- und Benchmarktests bleiben unveraendert und muessen
dieselben Suchziele und Budgets weiterhin erreichen.

Die abschliessende exakte Migration braucht darueber hinaus gemeinsame
Regressionen fuer Erzeugung neuer Zahlen durch Umformungen, exakte Brueche,
Patterninstanziierung, Canonicalizer, Suchcache, E-Graph, Serialisierung,
Persistenz, Solveradapter und Replay. Verschiedene exakte Werte duerfen in
keinem dieser exakten Verarbeitungspfade zusammenfallen.

```sh
mvn -pl regelsuche-core -Dtest=NumericBoundaryRegressionTest,ExactMonomialInferenceTest,EquivalenceAwarePatternMatcherTest test
./gradlew :regelsuche-core:test --tests '*NumericBoundaryRegressionTest' --tests '*ExactMonomialInferenceTest' --tests '*EquivalenceAwarePatternMatcherTest'
./gradlew --no-configuration-cache ciCheck
```

Lokale Teilpruefungen ersetzen nicht die vollstaendige Java-25-/Maven-/Gradle-
und Containerqualifikation des konkreten Commits. Kein Quality-Gate, Limit,
Negativtest oder unabhaengiger Verifier wird zum Abschluss abgeschwaecht.

## Nutzer- und Entwicklerzugang

Die Konsolidierung dient einem gemeinsamen Faehigkeitskern mit schrittweise
sichtbaren Details: startbare Beispiele fuer Lernende, Annahmen und Replay fuer
mathematische Nutzer, kleine Java-/Python-Einstiege fuer Entwickler und volle
Trace-/Budget-/Verifiervertraege fuer Spezialisten. Ein einfaches Beispiel
muss keine Forschungsstudie konfigurieren. Ein Expertenmodus darf umgekehrt
keine schwaechere mathematische Pruefung erhalten.
