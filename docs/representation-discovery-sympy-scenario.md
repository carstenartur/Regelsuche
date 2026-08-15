# Reproduzierbarer SymPy-Knowledge-Bridge-Nachweis

`SymPyKnowledgeBridgeScenario` ist ein kleiner, ausführbarer Nachweis für den
R2-Informationsmodus aus Issue #663. Er prüft nicht nur, ob ein Katalogeintrag
gefunden wird, sondern ob sich der tatsächlich ausführbare Capability-Frontier
ändert.

## Festes Szenario

Ausgangsausdruck:

```text
sin(x)^2 + (cos(x)^2 + 0)
```

Die Kandidatenbildung erhält keinen SymPy-Katalog und keinen direkten
trigonometrischen SymPy-Regel-Pack. Der produktive AST-Transformationsmotor
erzeugt mit der Core-Regel `ast_add_zero_right` den kanonisch formatierten
Kandidaten:

```text
sin(x) ^ 2 + cos(x) ^ 2
```

Die Formationspolicy wählt die vorab festgelegte primitive Regelanwendung, nicht
einen erwarteten Zieltext. Der oben angegebene Ausdruck wird anschließend als
Regressionserwartung geprüft.

Dieser Kandidat wird vor der Post-hoc-Klassifikation content-addressed
eingefroren. Für den Pack-aus- und Pack-an-Lauf sind Kandidatenmenge und
Formation-Regelinventar identisch.

## Verglichene Zustände

Der Nachweis hält getrennt fest:

1. **Pack deaktiviert:** kein Treffer der Struktur
   `sympy.trig.pythagorean-pair`, kein freigeschalteter Folgeschritt.
2. **Pack aktiviert, Evidenz unterhalb der Schwelle:** die Struktur wird
   erkannt, aber `rule:sympy.trig.pythagorean` bleibt gesperrt. Die präzise
   Warnung `KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM` wird erhalten.
3. **Pack aktiviert, Schwelle erreicht:** die validierte,
   annahmenfreie Core-Transformation plus zustimmende separate Oracle-Prüfung
   erreicht `SYMBOLICALLY_VERIFIED`. Erst dann wird die Folgefähigkeit
   freigeschaltet.
4. **Produktive Folgeausführung:** derselbe AST-Transformationsmotor erzeugt
   mit der nun sichtbaren Regel den neuen Zustand `1`. Weder der
   Formation-Lauf noch der Pack-aus-Lauf kann diesen Regelschritt ausführen.

Katalog-, Boundary-, Freeze-Receipt- und Regel-Inventaridentitäten sowie
SymPy-Provenienz, Lizenz, Erkennungsmodus, Evidenzschwelle, Warnungen und
Nachfolgezustände stehen in einem kanonischen JSON-Artefakt.

## Reproduktion

Fokussierter Gradle-Aufruf:

```bash
./gradlew --no-daemon \
  :regelsuche-discovery:test \
  --tests de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenarioTest
```

Fokussierter Maven-Aufruf:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl regelsuche-discovery -am \
  -Dtest=SymPyKnowledgeBridgeScenarioTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Beide Wege erzeugen beziehungsweise prüfen:

```text
regelsuche-discovery/build/reports/representation-discovery/
  sympy-knowledge-bridge.json
```

Wiederholte Läufe müssen byte-identische JSON-Ausgabe und denselben
`contentHash` erzeugen. Das Artefakt verwendet unabhängig vom Hostsystem einen
festen LF-Zeilenabschluss; ein nicht zum Inhalt passender Hash wird
fehlersicher abgewiesen.

## Claim-Grenze

Das Szenario belegt eine feste, ein-schrittige targetfreie Kandidatenbildung
und eine reale Post-Freeze-Änderung des Capability-Frontiers unter
kontrollierter Pack-Sichtbarkeit und Evidenzschwelle.

Es belegt nicht:

- breite autonome mathematische Entdeckung;
- externe mathematische Neuheit;
- einen allgemein optimalen Suchpfad;
- Überlegenheit gegenüber CAS-Systemen;
- einen held-out Benchmark-Erfolg.

Diese stärkeren Aussagen benötigen weiterhin den eingefrorenen Korpus und die
Informationsparitätsvergleiche aus #663, #620 und #235.
