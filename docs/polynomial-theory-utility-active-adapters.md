# Beobachtete Cache- und Spezialprofile

Bezug: #748, [Laufzeitautorität](polynomial-theory-utility-runtime-authority.md),
[Spezial-Pipeline](measured-polynomial-decomposition-pipeline.md) und
[verifierautorisierter Store](verified-polynomial-transition-cache.md).

## Gemeinsame Grenzen

Beide zusätzlichen Adapter akzeptieren nur die exakten eingefrorenen Inputs
und Formationsfälle in ihrer Run-Reihenfolge. Jedes Auftreten erhält eine
terminale Beobachtung. Parse, Positionsprüfung, mathematische Operationen und
Ergebnisbildung teilen dieselbe nicht rücksetzbare `PolynomialTheoryUtilityWorkAuthority`.
Die vorhandene v2-Projektion partitioniert den tatsächlichen kumulativen
Rohvektor. Kein Checkpoint, Profil, Kandidatenindex oder Qualifikationsinhalt
wird aufgrund eines beobachteten Ergebnisses geändert.

## Cache-Ausführung

`PolynomialTheoryUtilityDerivedCacheAdapter` besitzt pro Run einen leeren
`VerifiedPolynomialTransitionCacheStore` mit Kapazität 128 sowie einen
exakten Quelltextindex. Ein Miss verwendet die bestehende native rationale
Engine mit Kandidatenindex null und der gemeinsamen Verifier-/Occurrence-
Pipeline. Ein Hit verwendet den Store-Lookup und dessen aktuell gültige
Eintragsgeneration. Replay führt keine neue Faktorisierung aus und behält
die ursprüngliche siebenstufige primitive Evidenz.

`retainMeasured` ergänzt den vorhandenen Store um eine issuergebundene
Retention-Receipt. Der dynamisch begrenzte Messkontext zählt tatsächlich
verarbeitete UTF-8-Bytes der gerahmten Hash-Eingaben, Hash-Abschlüsse,
Index-/Lineage-Prüfungen, Eintragswrites und FIFO-Entfernungen. Außerhalb des
Aufrufs ist dieser Kontext nicht gebunden; die historische API und ihre
Identitäten bleiben unverändert.

`lookupMeasured` und `replayMeasured` binden denselben Messkontext an die
jeweilige `cache.lookup.`- beziehungsweise `cache.replay.`-Dimension. Ihre
privat ausgestellten Receipts enthalten die ausgeführte Operationsarbeit
und sämtliche Hash-Eingaben der historischen Lookup-/Replay-Nachweise.
Die ursprünglichen Nachweise, Store-Issuer und Generationsprüfungen bleiben
unverändert. Vor dem Aufruf werden die bestehende Operationsobergrenze und
16384 Einheiten für die begrenzten Nachweisfelder reserviert; dieser feste
Zusatz ist keine ausgeführte Arbeit. Auch Fremd-/Stale-Replays behalten die
tatsächlich konstruierte negative Evidenz. Ohne Zulassung laufen weder die
Operation noch deren Hash-Konstruktion.

Der Quelltextindex zählt außerdem seine tatsächlich gehashten Identifier-
und Lookup-Receipt-Bytes. Indexarbeit und vollständiger Receipt werden
gemeinsam vorab zugelassen, damit ein Budgetabbruch keine Cachearbeit ohne
das zugehörige Ereignis hinterlässt. Die primitive Expansion wird nach
Retention beziehungsweise Replay direkt aus der bereits ausgestellten,
unveränderlichen Evidenz gelesen. Weder eine Größenprüfung noch die spätere
Trace-Erzeugung rekonstruiert dafür ein `VerifiedTransition`-Zertifikat.
Die vorhandene Studienzählung für Ergebnis-/Trace-Datensätze und die
historische native Trace-Hilfe behalten ihren Vertrag.

Vor einer Retention gilt weiterhin die historische Untergrenze aus Quelle,
Ersetzung, Provenienz und 1024 Einheiten. Eine zusätzliche konservative
Komponentengrenze deckt die wiederholte Identitätsprüfung des unveränderlichen
Eintragsgraphen ab: 65536 feste Einheiten plus 256 pro gezählter Material-
Codeeinheit. Der Faktor deckt höchstens 64 Identitätstraversierungen mit bis
zu vier UTF-8-Bytes pro Codeeinheit ab. Bestehende Lineages und Reparse-Literale
sind Teil dieser Grenze. Die folgende Ergebnis-/Indexarbeit bleibt separat
reserviert. Diese Reservierungen werden nicht als ausgeführte Arbeit verbucht;
sie verleihen auch keine zusätzliche Studienarbeit.

Kann die Retention nicht zugelassen werden, entstehen keine Einfügung und
kein akzeptierter Cache-Übergang. Der native Versuch und sein verbrauchtes
Ledger bleiben als `BUDGET_INCONCLUSIVE` erhalten. Insbesondere darf die
1024-Grenze nicht entfernt werden, um in kleinen Checkpoints Cachetreffer zu
erzwingen. Nach echter FIFO-Verdrängung ist eine neue native Ableitung nötig.

`PolynomialTheoryUtilityCacheRunHistory` prüft zusätzlich die Messfolge über
Zeilengrenzen: Jeder Hit benötigt eine frühere Einfügung dieses Runs;
erfolgreiche Replays müssen Quell-/Zielsyntax, Backend, Transformations-ID,
Annahmen und primitive Evidenz bewahren. Verdrängung ist nur bei voller
eingefrorener Kapazität und für den ältesten Eintrag zulässig.

## Spezialkontrolle

`PolynomialTheoryUtilitySpecializedAdapter` verwendet die gemessene
`PolynomialDecompositionSynthesisOperator.factorExpression`-Überladung.
Integer-Request, `BinaryQuarticFactorizationEngine`, bestehende Homogenisierung,
gemeinsamer Verifier und ursprünglicher Renderer bleiben die mathematische
Autorität. Erzeugte Ergebnisse tragen die sechs tatsächlich ausgestellten
Evidenzstufen der Spezial-Pipeline. Sie werden nicht durch die allgemeine
exakte Transformations-ID ersetzt.

Die additive `createObserved`-Überladung bindet denselben Attempt-v2-Vertrag
an das private Spezial-Resultat. Backend, Request, ausgewählter Kandidat,
Report, Quelle, Pfad und vollständige Roharbeit stammen aus dieser tatsächlichen
Ausführung. Historische und native Attempt-Bytes behalten ihren Vertrag.

## Bereitschaft und Evidenzgrenze

Die Registry enthält alle fünf eingefrorenen Profile. Vier besitzen einen
beobachteten Adapter. Das externe Profil bleibt mit
`EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE` gesperrt: die gepinnte SymPy-API
belegt ihre Adapteroperationen, aber keine vollständige interne symbolische
Arbeit. Weder eine native Substitution noch erfundene externe Nullarbeitszeilen
füllen diese Lücke. Der vollständige Start scheitert vor dem ersten Run.

Der Preflight vergleicht außerdem die GraalPy-Version und alle aufgelösten
Paketversionen im Checkout-Lock mit der Laufzeitkennung des eingefrorenen
Studienplans. Für diesen Plan sind GraalPy 25.1.3, SymPy 1.14.0 und mpmath
1.3.0 festgelegt. Der aktuelle Lock nennt GraalPy 25.5.5.0.3. Der Preflight
meldet dafür zusätzlich `EXTERNAL_RUNTIME_LOCK_MISMATCH`. Diese Prüfung liest
nur den Lock und startet keine Engine.

### Eingefrorene Laufzeit an einem öffentlichen Entwicklungseingang

Die ursprünglichen Laufzeitkomponenten sind verfügbar. Maven löst die
Koordinaten `org.graalvm.polyglot:polyglot:25.1.3`,
`org.graalvm.polyglot:python:25.1.3:pom` und
`org.graalvm.python:python-embedding:25.1.3` einschließlich der transitiven
GraalPy-Sprachimplementierung auf. Die Python-Pakete sind als universelle
Wheels veröffentlicht. Der ursprüngliche, vom Plugin generierte Lock liegt
in [Commit #772](https://github.com/carstenartur/Regelsuche/blob/6d999272d6ab0ff885571c8a5f34f33114c6c856/regelsuche-math-sympy/graalpy.lock).
[Oracle-Release](https://github.com/oracle/graalpython/releases/tag/graal-25.1.3) ·
[Maven-Laufzeit-POM](https://repo.maven.apache.org/maven2/org/graalvm/polyglot/python/25.1.3/python-25.1.3.pom) ·
[SymPy 1.14.0](https://pypi.org/project/sympy/1.14.0/) ·
[mpmath 1.3.0](https://pypi.org/project/mpmath/1.3.0/)

Der separate Entwicklungsaufruf gleicht die tatsächlich geladenen Versionen
mit der Laufzeitkennung im bytegeprüften Ausführungsplan ab. Er lädt danach
dieselben Bridge-Bytes, deren SHA-256 im Ergebnis steht, faktorisiert
`x² − 1` über `ZZ` und prüft Einheit, Koeffizienten, Monome und
Multiplizitäten. Die reproduzierbaren Ausgabefelder enthalten keine Laufzeiten.
Die lokale Installation kann in einem Arbeitsverzeichnis außerhalb des
Checkouts erfolgen. Unter Linux/amd64 genügen folgende Schritte, beginnend
im Repository:

```sh
repo=$(pwd)
./gradlew freezePolynomialTheoryUtilityExecutionPlan
plan="$repo/build/reports/polynomial-theory-utility-execution-plan/polynomial-theory-utility-execution-plan-v1.json"
work=$(mktemp -d)
cd "$work"
curl -fLO https://github.com/oracle/graalpython/releases/download/graal-25.1.3/graalpy3.12-25.1.3-linux-amd64.tar.gz
echo '53d46f3ad78229699eb00677f4cb5e3cfe451b58c46970e4d4272a719b53ce82  graalpy3.12-25.1.3-linux-amd64.tar.gz' | sha256sum -c -
mkdir graalpy packages
tar -xzf graalpy3.12-25.1.3-linux-amd64.tar.gz --strip-components=1 -C graalpy
python3 -m pip download --no-deps --only-binary=:all: sympy==1.14.0 mpmath==1.3.0
echo 'e091cc3e99d2141a0ba2847328f5479b05d94a6635cb96148ccb3f34671bd8f5  sympy-1.14.0-py3-none-any.whl' | sha256sum -c -
echo 'a0b2b9fe80bbcd81a6647ff13108738cfb482d481d826cc0e02f5b35e5c88d2c  mpmath-1.3.0-py3-none-any.whl' | sha256sum -c -
python3 -m zipfile -e sympy-1.14.0-py3-none-any.whl packages
python3 -m zipfile -e mpmath-1.3.0-py3-none-any.whl packages
PYTHONPATH="$work/packages" graalpy/bin/graalpy "$repo/scripts/probe-frozen-polynomial-runtime.py" "$plan" > observed.json
cmp observed.json "$repo/docs/research/polynomial-theory-runtime/public-probe.json"
```

Die geprüfte Sprachversion ist Python 3.12.8 und die Implementierungsversion
ist GraalPy 25.1.3. Die fünf Profiladapter benötigen weiterhin die vollständige
kanonische Arbeitsmessung des externen Backends vor jeder Studienausführung.

Die Adapterledger beobachtet unter anderem Quellterme, Aufrufzahl und
dekodierte Faktor-/Termzahlen. Das Backend ruft `sympy.factor_list` auf und
liefert Faktoren, Multiplizitäten und Nanosekunden. Für interne Operationen
wie modulare Faktorisierung, Hensel-Liftung und Rekombination fehlen
ausführungsgebundene Zähler und ein vor der jeweiligen Operation wirksamer
Budgetabbruch mit erhaltenem Arbeitspräfix. Auch ein exakt rekonstruiertes
Produkt oder der passende historische Lock allein liefert diese Belege nicht.

Eine lokale Diagnose mit dem unveränderten Bridge-Skript, SymPy 1.14.0
unter CPython und `QQ` zeigt den Unterschied an zwei Entwicklungsbeispielen.
Für `x^4+x-1` und `x^4+x+1` werden je drei Eingabeterme und ein Faktor
mit drei Ausgabetermen beobachtet. Beim ersten Aufruf wurde die interne
Funktion `dup_zz_hensel_step` nullmal aufgerufen, beim zweiten zweimal.
Diese Funktionsaufrufe sind eine Diagnose für die unsichtbare Arbeit und
keine kanonischen Studieneinheiten. Der eingefrorene GraalPy-Run steht aus.

Die Kontrollen umfassen je 20 öffentliche CP06-Formationsfälle für Cache und
Spezialprofil, echte Cache-Insert-/Replay-/FIFO-Folgen unter ausdrücklich
größeren Komponentenautoritäten, Ablehnung eines außerhalb des Runs
vorbereiteten Replay-Eintrags und manipulierte Spezial-Requests. Größere
Komponentenautoritäten können keine Studienresultate ausstellen. Historische
Receipt-/Attempt-Bytepins bleiben Teil der Regression.
Ein delegierender SHA-256-Testprovider prüft zusätzlich die tatsächlich
verarbeiteten Bytes und Hash-Abschlüsse gegen die Cache-Dimensionen der
Ledger, einschließlich Retention-Abbruch sowie fremder und verdrängter
Lookup-Generationen. Die Hash-Berechnung selbst bleibt die JDK-SUN-Implementierung.

Diese Arbeit führt weder die 600-Zeilen-Studie noch eine Candidate-Freeze aus.
Die Qualifikation bleibt versiegelt. Cache-Nutzen, externe Parität und eine
Produktentscheidung sind weiterhin nicht nachgewiesen.
