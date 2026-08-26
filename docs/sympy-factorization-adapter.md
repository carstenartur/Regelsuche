# Eingebettete SymPy-Faktorisierung über GraalPy

**Implementierungsstand: 26. August 2026**

Regelsuche stellt SymPy als optionales, typisiertes
`FactorizationEngine`-Backend bereit. Der normale Pfad läuft mit eingebettetem
GraalPy in derselben JVM; ein neuer CPython-Prozess pro Anfrage bleibt nur als
separater Isolations- und Kompatibilitätsvergleich erhalten.

## Modulgrenze

Die Implementierung liegt in:

```text
:regelsuche-math-sympy
```

Das Modul hängt fachlich nur von `:regelsuche-core` ab. Es kapselt:

- die Maven-/Gradle-verwaltete GraalPy-Runtime;
- das eingebettete Python-Paket SymPy;
- den exakten strukturierten Wire-Vertrag;
- Integer- und Rational-Faktorisierungsengines;
- den CPython-Einmalprozess als Kontrolltransport;
- Runtime-, Repräsentations- und Evidence-Grenzen;
- einen nach Betriebsmodus getrennten Performancevergleich.

SymPy-Typen und `org.graalvm.polyglot.Value` verlassen das Modul nicht. Der
mathematische Kern sieht weiterhin nur `PolynomialRing`, `SparsePolynomial`,
`FactorizationRequest`, `FactorizationEngine` und `FactorizationVerifier`.

Ein Verbraucher wählt das Backend ausdrücklich als Abhängigkeit. Es wird nicht
heimlich zum globalen Standard jeder Suche und nicht als mathematische
Autorität behandelt.

## Verwaltete Runtime und Pakete

Gepinnt sind:

```text
GraalPy / Polyglot  25.1.3
SymPy               1.14.0
mpmath               1.3.0
```

Seit GraalVM 25 sind die früheren `-community`-Sprachartefakte mit den
kanonischen Artefakten identisch und veraltet. Gradle und Maven verwenden daher
beide die kanonische OSS-Koordinate:

```text
org.graalvm.polyglot:python
```

Weitere Maven-Artefakte sind:

```text
org.graalvm.polyglot:polyglot
org.graalvm.python:python-embedding
org.graalvm.python:graalpy-maven-plugin
```

Das GraalPy-Plugin installiert die Python-Pakete in das modulspezifische
virtuelle Dateisystem:

```text
GRAALPY-VFS/de.regelsuche/regelsuche-math-sympy
```

Dadurch benötigt das eingebettete Backend keine systemweite Python- oder
SymPy-Installation und kollidiert nicht mit einem späteren zweiten
GraalPy-Consumer.

### Gemeinsamer Dependency-Lock

Direkte Paketpins allein sichern die transitiven Python-Abhängigkeiten nicht
vollständig. Deshalb konsumieren Gradle und Maven dieselbe eingecheckte Datei:

```text
regelsuche-math-sympy/graalpy.lock
```

Der Lock bindet:

```text
GraalPy-Version       25.1.3
angeforderte Pakete   mpmath==1.3.0, sympy==1.14.0
aufgelöste Pakete     mpmath==1.3.0, sympy==1.14.0
```

Ein Maven-/Gradle-Paritätstest prüft Pfad, GraalPy-Version, deklarierte Eingaben
und vollständige aufgelöste Paketliste. Ein normaler Build verändert den Lock
nicht.

Bei einer bewussten Python-Dependency-Änderung wird er neu erzeugt:

```bash
./gradlew :regelsuche-math-sympy:graalPyLockPackages
```

oder aus dem Modulverzeichnis:

```bash
mvn org.graalvm.python:graalpy-maven-plugin:lock-packages
```

Die Änderung am Lock gehört in denselben Pull Request wie die Paketänderung.

## Exakter Wire-Vertrag

Regelsuche übergibt keine gerenderte mathematische Zeichenkette. Die
Trust-Grenze verwendet weder `parse_expr` noch `sympify` oder Python-`eval`.

Der versionierte Payload enthält ausschließlich:

```json
{
  "protocol": "regelsuche.sympy-factorization/v1",
  "domain": "ZZ",
  "variableCount": 2,
  "terms": [
    {
      "exponents": [4, 0],
      "numerator": "1",
      "denominator": "1"
    },
    {
      "exponents": [0, 4],
      "numerator": "4",
      "denominator": "1"
    }
  ]
}
```

Die Position im Exponentenvektor entspricht der bereits im `PolynomialRing`
gebundenen Variablenreihenfolge. Variablennamen, Renderformat, Quellbereiche
und Monomordnung überschreiten die Python-Grenze nicht.

Der Python-Einstieg konstruiert daraus ein ausdrückliches `sympy.Poly` über
`ZZ` oder `QQ` und ruft `factor_list` auf. Die Ausgabe enthält wieder nur:

- Einheit;
- Faktoren und Multiplizitäten;
- Exponentenvektoren;
- exakte Zähler-/Nennerpaare;
- Runtime- und SymPy-Version;
- diagnostische Zeitanteile.

Java materialisiert die Faktoren anschließend im ursprünglichen
`PolynomialRing`.

## Primärer GraalPy-Betrieb

`GraalPySymPyFactorizationEngine` verwendet:

```text
eine langlebige Polyglot Engine
  -> einen serialisierten Runtime-Worker
  -> einen wiederverwendeten GraalPy Context
  -> einmal importiertes SymPy
  -> einmal geladene factor_payload-Funktion
  -> viele typisierte Anfragen
```

Der Kontext erlaubt keinen Hostzugriff, keine nativen Zugriffe, keine
Gast-Threads und keinen sprachübergreifenden Polyglot-Zugriff. Das virtuelle
Dateisystem bleibt auf die eingebetteten Ressourcen begrenzt.

Ein Context wird nicht gleichzeitig von mehreren Threads benutzt. Die Runtime
serialisiert Aufrufe und ordnet jeden Task einer monotonen Generation zu. Nach
einem Timeout wird der betroffene Context zwangsweise geschlossen und die
Generation ersetzt. Ein verspätet endender Task einer alten Generation darf
einen neu erzeugten Warm-Context weder schließen noch zurücksetzen.

`close()` beendet Worker, Executor und Polyglot Engine. Ein späterer Aufruf
liefert einen expliziten terminalen Fehler statt eines teilweise gültigen
Ergebnisses.

## CPython-Kontrollpfad

`ProcessSymPyFactorizationEngine` startet für jede Anfrage:

```text
python -I -c <gebundenes Adapterprogramm>
```

Eingabe, Ausgabe, Fehlerausgabe und Laufzeit sind begrenzt. Bei Timeout wird der
Prozess beendet. Dieser Pfad ist absichtlich nicht die normale
Regelsuche-Schnittstelle. Er dient als:

- CPython-/GraalPy-Kompatibilitätskontrolle;
- starke Prozessisolationsbaseline;
- echter Einmalprozess-Cold-Start;
- unabhängiger Transport über denselben exakten Wire-Vertrag.

## Trust Flow

Beide Transporte folgen demselben fachlichen Ablauf:

```text
FactorizationRequest
  -> strukturierter exakter Payload
  -> GraalPy oder CPython
  -> strukturierte exakte SymPy-Ausgabe
  -> Policy- und Repräsentationsprüfung
  -> untrusted FactorizationEngine.Proposal
  -> FactorizationVerifier
  -> unabhängige Produktrekonstruktion
  -> verifier-ausgestellte Evidence
```

SymPy darf `COMPLETE_FACTORIZATION` als Backend-Claim ausgeben. Nach einer
korrekten Produktrückprüfung bleibt die Claim-Stärke dennoch zunächst:

```text
BACKEND_CLAIMED_COMPLETE
```

Sie wird nicht allein aufgrund des SymPy-Ergebnisses zu
`INDEPENDENTLY_CERTIFIED_COMPLETE` oder zu einem unabhängigen
Irreduzibilitätsbeweis.

## Ressourcen- und Fehlersemantik

`SymPyFactorizationPolicy` bindet unter anderem:

- erwartete SymPy-Version;
- Timeout;
- maximale Eingabe-, Ausgabe- und Fehlerausgabebytes;
- maximale Faktorzahl;
- maximale Termzahl je Faktor;
- maximale gesamte Ausgabetermzahl;
- maximale Koeffizientenbitlänge.

Zusätzlich gelten Struktur-, Kandidaten- und Work-Grenzen des ursprünglichen
`FactorizationRequest`.

Der kanonische Work-Ledger zählt nur beobachtbare Adapterarbeit:

```text
sympy.encode.source-terms
sympy.invoke.calls
sympy.decode.factors
sympy.decode.factor-terms
sympy.issue.proposals
```

Regelsuche erfindet keine SymPy-internen Work Units. Timeout, fehlende Runtime,
I/O-Fehler und Repräsentationsgrenzen bleiben von mathematischer
Nichtzerlegbarkeit getrennt.

## Evidence und Diagnostik

Kanonische Evidence bindet:

- den vollständigen `FactorizationRequest`;
- die vollständige SymPy-Policy;
- Hash des festen Python-Adapters;
- exakten Inputhash;
- Runtime- und SymPy-Version;
- kanonischen semantischen Faktoroutput;
- Engine- und Verifierzertifikate.

Nichtkanonische `SymPyExecutionMetrics` bewahren getrennt:

- Initialisierungszeit;
- Aufruf- und Rücktransportzeit;
- von Python gemessene `factor_list`-Zeit;
- gesamte Python-Funktionszeit;
- Raw-Input-, Raw-Output- und Skripthashes;
- Cold-/Warm-Kennzeichen.

Wandzeiten und Raw-Outputhash verändern den mathematischen Zertifikatshash
nicht. Inhaltlich gleiche Cold- und Warm-Läufe erzeugen daher dieselbe
Regelsuche-Evidence.

## Performancevergleich

JMH verwendet dieselbe kanonische Quartik-Anfrage und denselben Verifier für
alle vergleichbaren Spuren:

| Spur | Runtimezustand | Verifier |
| --- | --- | --- |
| `nativeBackendWarm` | JVM und native Engine warm | nein |
| `nativeEndToEndWarm` | JVM und native Engine warm | ja |
| `graalPyBackendWarm` | GraalPy und SymPy warm | nein |
| `graalPyEndToEndWarm` | GraalPy und SymPy warm | ja |
| `graalPyEndToEndCold` | Engine, Context und Import je Operation | ja |
| `cpythonOneShotEndToEnd` | Prozess, Interpreter und Import je Operation | ja |

Damit werden getrennt beantwortet:

1. Wie teuer ist die Backendausführung bei vorhandener Runtime?
2. Wie teuer ist eine vollständige Anfrage einschließlich gemeinsamer
   Abschlussprüfung?
3. Wie hoch sind eingebetteter und externer Cold-Start?

Der Bericht prüft vollständige Track-Matrix und `ms/op`-Einheiten, besitzt aber
bewusst kein relatives Winner-Gate:

```text
DIAGNOSTIC_TRACKS_NO_RELATIVE_WINNER_GATE
```

Laufzeit bleibt umgebungsbezogene Engineering-Diagnostik und kein
mathematischer Qualitätsnachweis oder universeller CAS-Ranglistenwert.

## Reproduktion

Fokussierte Tests:

```bash
./gradlew :regelsuche-math-sympy:test
```

Getrennter Vergleich:

```bash
./gradlew :regelsuche-math-sympy:verifySymPyFactorizationBenchmark
```

Validierte Berichte:

```text
public/dev/bench/sympy-factorization.json
public/dev/bench/sympy-factorization.html
```

Vollständiger Checkout-Vertrag:

```bash
./gradlew --no-configuration-cache ciCheck
mvn --batch-mode --no-transfer-progress -Pfull verify
```

## Bewusste Grenzen

Nicht implementiert oder nicht behauptet sind:

- automatische Auswahl von SymPy für jede Suchanfrage;
- unabhängige Vollständigkeit allein aus einer SymPy-Ausgabe;
- SymPy-Objekte als öffentliche Regelsuche-Datentypen;
- paralleler Pool mehrerer GraalPy-Kontexte;
- langlebiger externer CPython-Worker;
- universelle Performanceaussage jenseits des gemeinsamen Benchmarkfragments;
- Gleichsetzung von Wandzeit und kanonischen Work Units.
