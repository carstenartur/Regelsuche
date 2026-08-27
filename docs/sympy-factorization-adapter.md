# Eingebettete SymPy-Faktorisierung über GraalPy

**Implementierungsstand: 27. August 2026**

Regelsuche stellt SymPy als optionales, typisiertes
`FactorizationEngine`-Backend bereit. Der primäre SymPy-Pfad läuft mit
GraalPy in derselben JVM. Ein neuer CPython-Prozess pro Anfrage bleibt als
getrennter Isolations-, Kompatibilitäts- und Cold-Start-Vergleich erhalten.

## Modulgrenze

Die Implementierung liegt vollständig in:

```text
:regelsuche-math-sympy
```

Das Modul hängt fachlich nur von `:regelsuche-core` ab und kapselt:

- die von Maven und Gradle verwaltete GraalPy-Runtime;
- die eingebetteten Python-Pakete SymPy und mpmath;
- den exakten strukturierten Wire-Vertrag;
- Integer- und Rational-Faktorisierungsengines;
- den CPython-Einmalprozess als Kontrolltransport;
- Runtime-, Ressourcen-, Repräsentations- und Evidence-Grenzen;
- den nach Betriebsmodus getrennten Performancevergleich.

SymPy-Typen und `org.graalvm.polyglot.Value` verlassen das Modul nicht. Der
mathematische Kern sieht nur `PolynomialRing`, `SparsePolynomial`,
`FactorizationRequest`, `FactorizationEngine` und `FactorizationVerifier`.
Das Backend wird ausdrücklich als Abhängigkeit ausgewählt; es wird weder
heimlich zum globalen Standard noch zur mathematischen Autorität.

## Gepinnte Runtime und Abhängigkeiten

```text
GraalPy / Polyglot  25.1.3
SymPy               1.14.0
mpmath               1.3.0
```

Maven und Gradle verwenden dieselbe kanonische OSS-Koordinate
`org.graalvm.polyglot:python` sowie dieselbe eingecheckte Lockdatei:

```text
regelsuche-math-sympy/graalpy.lock
```

Die Lockdatei bindet GraalPy-Version, direkte Paketwünsche und die vollständig
aufgelöste Python-Paketliste. Ein Maven-/Gradle-Paritätstest prüft Versionen,
Pfade, Paketpins und Lockinhalt. Ein normaler Build verändert die Lockdatei
nicht.

Bei einer beabsichtigten Paketänderung wird sie mit einem der beiden
checkout-eigenen Wege neu erzeugt und im selben Pull Request eingecheckt:

```bash
./gradlew :regelsuche-math-sympy:graalPyLockPackages
```

```bash
cd regelsuche-math-sympy
mvn org.graalvm.python:graalpy-maven-plugin:lock-packages
```

## Verpackung und private Runtime-Extraktion

Das GraalPy-Plugin verpackt die lock-gebundene Python-Umgebung zunächst unter:

```text
GRAALPY-VFS/de.regelsuche/regelsuche-math-sympy
```

Das ist die reproduzierbare Distributionsform im Klassenpfad. Die eingebettete
Runtime führt native Module jedoch nicht direkt aus diesem virtuellen
Dateisystem aus. Beim Erzeugen eines `GraalPySymPyRuntime` geschieht einmalig:

```text
lock-gebundene Klassenpfadressourcen
  -> GraalPy VirtualFileSystem nur als Quelle
  -> extractVirtualFileSystemResources(...)
  -> privates temporäres Verzeichnis
  -> forExternalDirectory(...)
  -> wiederverwendete GraalPy-Contexts
```

Diese Extraktion ist notwendig, weil GraalPys VirtualFileSystem absichtlich
schreibgeschützt ist. `python.IsolateNativeModules` muss dagegen neben einer
nativen Bibliothek kontextprivate `.dupN`-Kopien anlegen, mit `patchelf`
relokieren und wieder löschen. Mehr Host-I/O am VFS ändert dessen eigene
Schreibschutzsemantik nicht; deshalb verwendet Regelsuche die von GraalPy
dokumentierte External-Directory-Konfiguration.

Das temporäre Ressourcenverzeichnis gehört genau einer Runtime-Instanz. Es wird
nicht vom mathematischen Request adressiert und bei `close()` rekursiv entfernt.
Auch fehlgeschlagene Konstruktion versucht die teilweise extrahierten Dateien
zu bereinigen. Ein Fehler bei Extraktion, Context-Aufbau oder Bereinigung bleibt
ein technischer Fehler und wird nicht als mathematisches Ergebnis ausgegeben.

## Exakter Wire-Vertrag

Regelsuche übergibt keine gerenderte mathematische Zeichenkette. Die
Trust-Grenze verwendet weder `parse_expr`, `sympify` noch Python-`eval`.
Ein Payload enthält ausschließlich:

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

Der eingecheckte Python-Einstieg konstruiert ein ausdrückliches `sympy.Poly`
über `ZZ` oder `QQ`, ruft `factor_list` auf und gibt wieder nur strukturierte
exakte Daten aus:

- Einheit;
- Faktoren und Multiplizitäten;
- Exponentenvektoren;
- Zähler-/Nennerpaare;
- Runtime- und SymPy-Version;
- nichtkanonische Zeitanteile.

Java materialisiert die Faktoren anschließend im ursprünglichen
`PolynomialRing`.

## Primärer GraalPy-Betrieb

```text
private extrahierte Python-Umgebung
  -> langlebige Polyglot Engine
  -> serialisierter Java-Platform-Thread
  -> wiederverwendeter GraalPy Context
  -> einmal importiertes SymPy
  -> viele typisierte Anfragen
```

GraalPy native Extensions können nicht auf einem Java-Virtual-Thread ausgeführt
werden. Deshalb verwendet die Runtime einen dedizierten Daemon-Platform-Thread
und lässt nie zwei Anfragen gleichzeitig denselben Context verwenden.

Jeder Context setzt:

```text
python.PosixModuleBackend=java
python.IsolateNativeModules=true
python.DontWriteBytecodeFlag=true
```

`python.IsolateNativeModules` ist für Context-Ersatz nach einem Timeout und für
Cold-Start-Messungen erforderlich, nachdem `_ctypes` bereits in derselben JVM
geladen wurde. Alle GraalPy-Contexts dieses Prozesses verwenden dieselbe Option.
Der Multi-Context-Betrieb mit nativen Erweiterungen ist eine besonders zu
prüfende Linux-Runtime-Grenze; ein unmöglicher Neuaufbau fällt geschlossen mit
einem technischen Status aus.

## Autoritäten und Sicherheitsgrenze

Der Context verweigert:

- Java-Host-Interop;
- sprachübergreifenden Polyglot-Zugriff;
- die ungefilterte Übernahme der Hostprozessumgebung.

Er erlaubt bewusst:

- nativen Zugriff für `_ctypes` und weitere gepinnte native Module;
- GraalPy-eigene Gast-Threads, unter anderem für Background-GC;
- Hostprozessstart für GraalPys eigenen `patchelf`-Aufruf;
- Dateizugriff auf das private extrahierte Ressourcenverzeichnis.

Polyglot erhält explizit nur `PATH`, damit GraalPy das provisionierte
`patchelf` finden kann. Tokens, Home-Verzeichnisse und sonstige CI- oder
Anwendungsvariablen werden nicht pauschal geerbt. Unter Ubuntu 22.04 verwenden
CI und Release den distributionsgebundenen Pin:

```text
patchelf=0.14.3-1
```

Der strukturierte Faktor-Payload enthält weder Python-Code noch Kommandos oder
Hostpfade. Trotzdem ist der eingebettete Pfad ausdrücklich **keine
Sicherheitssandbox**: nativer Code und der von GraalPy gestartete Hostprozess
laufen mit den Betriebssystemrechten des JVM-Prozesses. Nur der eingecheckte
Adapter und die lock-gebundenen Pakete dürfen in diesem Pfad ausgeführt werden.
Für eine stärkere Ausführungsgrenze ist der separate Prozesspfad zusätzlich mit
Betriebssystem- oder Containerisolation zu verwenden.

## Timeout, Generationen und Lebenszyklus

Jede Anforderung ist an eine monotone Runtime-Generation gebunden. Bei Timeout:

1. wird der laufende Task abgebrochen;
2. der betroffene Context zwangsweise geschlossen;
3. die Generation erhöht;
4. ein neuer Platform-Thread-Executor und später ein neuer Context erzeugt.

Ein verspätet endender Task einer alten Generation darf den wiederhergestellten
Warm-Context nicht schließen oder zurücksetzen. `close()` beendet Worker,
Executor und Polyglot Engine und entfernt danach das private temporäre
Ressourcenverzeichnis. Ein späterer Aufruf liefert einen expliziten terminalen
Fehler.

## CPython-Kontrollpfad

`ProcessSymPyFactorizationEngine` startet je Anfrage:

```text
python -I -c <gebundenes Adapterprogramm>
```

Eingabe, Ausgabe, Fehlerausgabe und Laufzeit sind begrenzt. Bei Timeout wird der
Prozess beendet. Dieser Pfad dient als:

- CPython-/GraalPy-Kompatibilitätskontrolle;
- Prozessisolationsbaseline;
- echter Einmalprozess-Cold-Start;
- unabhängiger Transport über denselben exakten Wire-Vertrag.

Er ist nicht der normale In-Process-Pfad und wird nicht still als Fallback
verwendet.

## Trust Flow

Beide Transporte folgen derselben fachlichen Grenze:

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

SymPy darf `COMPLETE_FACTORIZATION` als Backend-Claim ausgeben. Nach korrekter
Produktrückprüfung bleibt die Claim-Stärke dennoch:

```text
BACKEND_CLAIMED_COMPLETE
```

Sie wird nicht allein durch die SymPy-Ausgabe zu
`INDEPENDENTLY_CERTIFIED_COMPLETE` oder zu einem unabhängigen
Irreduzibilitätsbeweis.

## Ressourcen- und Fehlersemantik

`SymPyFactorizationPolicy` begrenzt unter anderem:

- erwartete SymPy-Version und Timeout;
- Eingabe-, Ausgabe- und Fehlerausgabebytes;
- Faktorzahl und Termzahl je Faktor;
- gesamte Ausgabetermzahl;
- Koeffiziententext und Koeffizientenbitlänge.

Zusätzlich gelten Struktur-, Kandidaten- und Work-Grenzen des ursprünglichen
`FactorizationRequest`. Der Work-Ledger zählt nur beobachtbare Adapterarbeit:

```text
sympy.encode.source-terms
sympy.invoke.calls
sympy.decode.factors
sympy.decode.factor-terms
sympy.issue.proposals
```

Regelsuche erfindet keine SymPy-internen Work Units. Timeout, fehlende Runtime,
I/O-, Extraktions- und Repräsentationsfehler bleiben von mathematischer
Nichtzerlegbarkeit getrennt.

## Evidence und Diagnostik

Kanonische Evidence bindet:

- vollständigen `FactorizationRequest` und Policy;
- Hash des tatsächlich ausgeführten Adapterprogramms;
- exakten Inputhash;
- Runtime- und SymPy-Version;
- kanonischen semantischen Faktoroutput;
- Engine- und Verifierzertifikate.

Nichtkanonische `SymPyExecutionMetrics` bewahren getrennt:

- Initialisierungszeit;
- Aufruf- und Rücktransportzeit;
- von Python gemessene `factor_list`-Zeit;
- gesamte Python-Funktionszeit;
- Raw-Input-, Raw-Output- und Programmhashes;
- Cold-/Warm-Kennzeichen.

Bootstrap-, Extraktions- und Transportfehler behalten zusätzlich eine
whitespace-normalisierte, begrenzte Ursachenfolge. Diese Diagnose verändert
keine mathematische Evidence. Wandzeiten und Raw-Outputhash verändern den
Zertifikatshash ebenfalls nicht; semantisch gleiche Cold- und Warm-Läufe
besitzen daher dieselbe Evidence.

## Performancevergleich

JMH verwendet dieselbe kanonische Quartik-Anfrage und denselben Verifier:

| Spur | Runtimezustand | Verifier |
| --- | --- | --- |
| `nativeBackendWarm` | JVM und native Engine warm | nein |
| `nativeEndToEndWarm` | JVM und native Engine warm | ja |
| `graalPyBackendWarm` | GraalPy und SymPy warm | nein |
| `graalPyEndToEndWarm` | GraalPy und SymPy warm | ja |
| `graalPyEndToEndCold` | Extraktion, Engine, Context und Import je Operation | ja |
| `cpythonOneShotEndToEnd` | Prozess, Interpreter und Import je Operation | ja |

Die Cold-Spur enthält bewusst die Runtime-Konstruktion einschließlich privater
Ressourcenextraktion. Der Bericht validiert die vollständige Track-Matrix und
`ms/op`, besitzt aber kein relatives Winner-Gate:

```text
DIAGNOSTIC_TRACKS_NO_RELATIVE_WINNER_GATE
```

Wandzeit bleibt umgebungsbezogene Engineering-Diagnostik und kein
mathematischer Qualitätsnachweis.

## Reproduktion

Unter Ubuntu 22.04:

```bash
sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  patchelf=0.14.3-1 \
  python3-venv \
  z3=4.8.12-1
```

Fokussierte Tests:

```bash
./gradlew :regelsuche-math-sympy:test
```

Benchmark und validierter Bericht:

```bash
./gradlew :regelsuche-math-sympy:verifySymPyFactorizationBenchmark
```

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
- paralleler Pool mehrerer GraalPy-Contexts;
- plattformunabhängiger nativer Multi-Context-Betrieb;
- langlebiger externer CPython-Worker;
- eine universelle Performanceaussage jenseits des gemeinsamen Fragments;
- Gleichsetzung von Wandzeit und kanonischen Work Units.
