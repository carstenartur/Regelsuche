# Eingebettete SymPy-Faktorisierung über GraalPy

**Implementierungsstand: 26. August 2026**

Regelsuche integriert SymPy als optionales externes Faktorisierungsbackend,
ohne einen neuen Betriebssystemprozess für jede normale Anfrage zu starten.
GraalPy, SymPy und ihre Python-Abhängigkeiten werden durch denselben Gradle- und
Maven-Checkout verwaltet wie der Java-Code. Ein separater CPython-Einmalprozess
bleibt ausschließlich als Isolations-, Kompatibilitäts- und Cold-Start-Kontrolle
erhalten.

## Modul und Abhängigkeitsgrenze

Die Implementierung liegt in:

```text
:regelsuche-math-sympy
```

Das Modul hängt fachlich nur von `:regelsuche-core` ab. Es enthält:

- den `FactorizationEngine`-Adapter für `Z[x_1, ..., x_n]` und
  `Q[x_1, ..., x_n]`;
- die GraalPy-Einbettung und das modulspezifische virtuelle Dateisystem;
- den versionierten Python-Einstieg;
- den CPython-Einmalprozess als Kontrolltransport;
- Ressourcen-, Wire- und Evidence-Grenzen;
- Tests und den getrennten Performancevergleich.

SymPy-Typen und `org.graalvm.polyglot.Value` verlassen dieses Modul nicht. Der
mathematische Kern verwendet weiterhin ausschließlich
`CoefficientDomain`, `PolynomialRing`, `SparsePolynomial`,
`FactorizationRequest` und `FactorizationVerifier`.

## Maven- und Gradle-verwaltete Runtime

Gepinnt sind:

```text
GraalPy / Polyglot  25.1.3
SymPy               1.14.0
mpmath               1.3.0
```

Gradle verwendet das Plugin `org.graalvm.python`. Das Plugin besitzt die
Runtime-Classpath-Autorität und injiziert genau eine ausgewählte GraalPy-Edition.
Regelsuche wählt ausdrücklich die Community-Edition und bindet die
`polyglotVersion` an `25.1.3`.

Maven bindet dieselbe Versionsfamilie über:

```text
org.graalvm.polyglot:polyglot
org.graalvm.polyglot:python
org.graalvm.python:python-embedding
org.graalvm.python:graalpy-maven-plugin
```

Das Plugin installiert die Python-Pakete in das modulspezifische virtuelle
Dateisystem:

```text
GRAALPY-VFS/de.regelsuche/regelsuche-math-sympy
```

Dadurch kollidiert die Einbettung nicht mit einem späteren zweiten
GraalPy-Consumer im selben Produkt.

### Gemeinsamer Python-Dependency-Lock

Direkte Versionsangaben allein reichen für reproduzierbare Python-Builds nicht
aus, weil Python-Pakete ihre transitiven Abhängigkeiten typischerweise als
Versionsbereiche deklarieren. Das Modul enthält deshalb den eingecheckten Lock:

```text
regelsuche-math-sympy/graalpy.lock
```

Gradle und Maven verweisen ausdrücklich auf dieselbe Datei. Der Lock bindet:

```text
GraalPy-Version       25.1.3
angeforderte Pakete   mpmath==1.3.0, sympy==1.14.0
aufgelöste Pakete     mpmath==1.3.0, sympy==1.14.0
```

Eine Änderung der Pakete oder ihrer Constraints ohne passenden Lock scheitert
am GraalPy-Paketvertrag. Der Maven-/Gradle-Paritätstest prüft zusätzlich
Dateipfad, GraalPy-Version, deklarierte Eingaben und die vollständige
aufgelöste Paketliste.

Der Lock wird nur bei einer bewussten Dependency-Änderung neu erzeugt:

```bash
./gradlew :regelsuche-math-sympy:graalPyLockPackages
```

oder aus dem Modulverzeichnis über den entsprechenden Maven-Goal:

```bash
mvn org.graalvm.python:graalpy-maven-plugin:lock-packages
```

Die resultierende Datei ist als Teil derselben Dependency-Änderung zu prüfen
und einzuchecken. Ein normaler Build aktualisiert sie nicht selbsttätig.

## Strukturierter exakter Wire-Vertrag

Der Adapter übergibt keine gerenderte mathematische Zeichenkette. Insbesondere
verwendet die Trust-Grenze weder `parse_expr`, `sympify` noch eine aus
Quellsyntax erzeugte Python-Auswertung.

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

Variablennamen, Monomordnung, Renderformat und Source Occurrences überschreiten
die Python-Grenze nicht. Die Position im Exponentenvektor entspricht der
bereits gebundenen Variablenreihenfolge des `PolynomialRing`.

Der Python-Einstieg konstruiert daraus unmittelbar ein explizites
`sympy.Poly` über `ZZ` oder `QQ` und ruft `factor_list` auf. Die Ausgabe enthält
wieder ausschließlich Einheit, Faktoren, Multiplizitäten, Exponentenvektoren
und exakte Zähler-/Nennerpaare.

## Primärer GraalPy-Betrieb

`GraalPySymPyFactorizationEngine` verwendet:

```text
eine langlebige Polyglot Engine
  -> einen serialisierten Worker
  -> einen wiederverwendeten GraalPy Context
  -> einmal geladenes SymPy
  -> einmal ausgewertete factor_payload-Funktion
  -> beliebig viele typisierte Anfragen
```

Der Kontext erlaubt keinen Hostzugriff, keine nativen Zugriffe, keine
Gast-Threads und keinen sprachübergreifenden Polyglot-Zugriff. Der virtuelle
Dateisystemzugriff bleibt auf die eingebetteten Ressourcen begrenzt.

Ein `Context` wird nicht gleichzeitig aus mehreren Threads aufgerufen. Ein
Worker serialisiert die Anfragen. Überschreitet eine Anfrage das Zeitlimit,
wird der aktive Kontext zwangsweise geschlossen; vor der nächsten Anfrage wird
ein neuer Worker erzeugt. Ein Timeout darf keinen möglicherweise beschädigten
oder weiterrechnenden Kontext in den Pool zurückgeben.

## CPython-Kontrollpfad

`ProcessSymPyFactorizationEngine` startet für jede Anfrage einen isolierten
CPython-Prozess:

```text
python -I -c <gebundenes Adapterprogramm>
```

Eingabe, Ausgabe, Fehlerausgabe und Laufzeit sind begrenzt. Bei Timeout wird der
Prozess zerstört. Dieser Pfad ist absichtlich nicht die primäre
Regelsuche-Schnittstelle. Er bleibt wichtig für:

- Vergleich von GraalPy und CPython auf demselben Wire-Vertrag;
- starke Prozessisolation;
- unabhängige Kontrolle des eingebetteten Laufzeitverhaltens;
- Messung des echten Einmalprozess-Cold-Starts.

## Trust Flow

Beide Transporte verwenden denselben fachlichen Ablauf:

```text
FactorizationRequest
  -> exakter strukturierter Payload
  -> GraalPy oder CPython
  -> strukturierte exakte SymPy-Ausgabe
  -> Policy- und Repräsentationsprüfung
  -> untrusted FactorizationEngine.Proposal
  -> FactorizationVerifier
  -> unabhängige exakte Produktrekonstruktion
  -> verifier-ausgestellte Evidence
```

SymPy darf `COMPLETE_FACTORIZATION` als Backend-Claim ausgeben. Nach erfolgreicher
Produktrückprüfung lautet die Claim-Stärke trotzdem zunächst
`BACKEND_CLAIMED_COMPLETE`. Das Ergebnis wird nicht automatisch zu
`INDEPENDENTLY_CERTIFIED_COMPLETE` oder zu einem Irreduzibilitätsbeweis.

## Ressourcen- und Fehlersemantik

`SymPyFactorizationPolicy` bindet unter anderem:

- erwartete SymPy-Version;
- Timeout;
- maximale Ein- und Ausgabebytes;
- maximale Faktorzahl;
- maximale Termzahl je Faktor;
- maximale gesamte Ausgabetermzahl;
- maximale Koeffizientenbitlänge.

Zusätzlich gelten die Struktur-, Kandidaten- und Work-Grenzen des ursprünglichen
`FactorizationRequest`.

Der Work-Ledger zählt nur beobachtbare Adapterarbeit:

```text
sympy.encode.source-terms
sympy.invoke.calls
sympy.decode.factors
sympy.decode.factor-terms
sympy.issue.proposals
```

Regelsuche erfindet keine vermeintlichen SymPy-internen Rechenschritte. Timeout,
I/O- und Repräsentationsgrenzen bleiben von mathematischer Nichtzerlegbarkeit
getrennt.

## Evidence und Laufzeitdiagnostik

Kanonische Evidence bindet:

- vollständigen `FactorizationRequest`;
- vollständige SymPy-Policy;
- Hash des versionierten Python-Adapters;
- exakten Inputhash;
- Runtime- und SymPy-Version;
- kanonischen semantischen Faktoroutput;
- Engine- und Verifierzertifikate.

Nichtkanonische `SymPyExecutionMetrics` bewahren getrennt:

- Initialisierungszeit;
- Java-zu-Python- und Rücktransportzeit einschließlich Aufruf;
- von Python gemessene `factor_list`-Zeit;
- gesamte Python-Funktionszeit;
- Raw-Input-, Raw-Output- und Skripthashes;
- Cold-/Warm-Kennzeichen.

Zeitwerte und der Raw-Outputhash dürfen den mathematischen Zertifikatshash nicht
verändern. Zwei inhaltlich gleiche Warm- und Cold-Läufe erzeugen deshalb dieselbe
Regelsuche-Evidence, obwohl ihre Diagnostik verschieden ist.

## Performancevergleich

Der JMH-Vergleich verwendet dieselbe kanonische binäre Quartik-Anfrage für alle
Spuren:

| Spur | Initialisierung | Verifier |
| --- | --- | --- |
| `nativeBackendWarm` | JVM und native Engine warm | nein |
| `nativeEndToEndWarm` | JVM und native Engine warm | ja |
| `graalPyBackendWarm` | GraalPy-Kontext und SymPy warm | nein |
| `graalPyEndToEndWarm` | GraalPy-Kontext und SymPy warm | ja |
| `graalPyEndToEndCold` | Engine, Kontext und SymPy-Import je Operation | ja |
| `cpythonOneShotEndToEnd` | Prozess, Interpreter und Import je Operation | ja |

Damit werden zwei verschiedene Fragen getrennt:

1. Wie teuer ist die Backendausführung bei bereits vorhandener Runtime?
2. Wie teuer ist eine vollständige produktionsnahe Anfrage einschließlich
   Initialisierung beziehungsweise Prozessisolation und unabhängiger Prüfung?

Der Report berechnet track-spezifische Verhältnisse, enthält aber bewusst kein
relatives Winner-Gate:

```text
DIAGNOSTIC_TRACKS_NO_RELATIVE_WINNER_GATE
```

Eine Laufzeit ist umgebungsbezogene Engineering-Diagnostik. Sie ist kein
mathematischer Qualitätsnachweis und keine allgemeine CAS-Rangliste.

## Reproduktion

Fokussierte Tests:

```bash
./gradlew :regelsuche-math-sympy:test
```

Getrennter Performancevergleich:

```bash
./gradlew :regelsuche-math-sympy:verifySymPyFactorizationBenchmark
```

Der validierte Bericht wird erzeugt unter:

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

- automatische Auswahl von SymPy als Standardengine für jede Suchanfrage;
- unabhängige Vollständigkeits- oder Irreduzibilitätsevidence allein aufgrund
  einer SymPy-Ausgabe;
- SymPy-Objekte als öffentliche Regelsuche-Datentypen;
- ein paralleler Pool mehrerer GraalPy-Kontexte;
- ein langlebiger externer CPython-Worker;
- eine universelle Performanceaussage jenseits des explizit gemeinsamen
  Benchmarkfragments;
- Gleichsetzung von Wandzeit und kanonischen Work Units.
