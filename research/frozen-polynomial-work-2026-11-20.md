# Öffentliche Operationsdiagnose für #748

Der bestehende Java-Encoder erzeugt die Eingabe für die originale Faktor-Bridge.
Der externe Prozess prüft SymPy 1.14.0, mpmath 1.3.0, die Python-Integerdomäne,
die Bridge-Datei und 34 tatsächlich benötigte Moduldateien. Der Java-Empfänger
verlangt GraalPy 25.1.3. Beide Seiten binden den Inhalt der Instrumentierungsdateien
an den Beleg. Jeder Prozess beginnt mit einem neuen Zähler. Der Diagnosevergleich
verwendet den festen öffentlichen Startwert 0 und eine geleerte SymPy-Cacheinstanz.

Ein unbekannter ausführbarer Pythonpfad aus den mathematischen Paketen bricht
schon beim Eintritt ab. Die strikte Diagnose lehnt auch unbekannte fremde
Pythoncallbacks und nicht klassifizierte native Aufrufe ab. Nur ein vollständig
durchlaufener Diagnoseaufruf kann die strukturierten Faktoren ausgeben. Jeder
Beleg trägt `studyAuthorized=false`. `DECLARED_OPERATIONS` darf die
nachgewiesene Teilmenge diagnostisch ausführen und verzeichnet die
nicht vollständig erfasste Transportgrenze. `COMPLETE_BACKEND_WORK`
(`--complete-work`) verlangt sämtliche bis zum Abbruch erreichten
Operationen und stoppt gegenwärtig vor dem Eintritt in `json.loads`.
Bei derselben öffentlichen Eingabe meldet der reale Java-Empfänger daher
fünf erreichte Diagnoseoperationen beziehungsweise null Operationen und
`UNMEASURED` an der strengeren Grenze.

## Öffentliche Reproduktion

Mit den gepinnten entpackten Wheels auf dem Python-Pfad und dem aktiven
historischen GraalPy-Interpreter:

```sh
PYTHONPATH=scripts graalpy -m unittest -q scripts/test_frozen_sympy_operation_work.py
SYMPY_GROUND_TYPES=python graalpy scripts/check-frozen-polynomial-operation-work.py --frozen-runtime --strict-native --limit 5
SYMPY_GROUND_TYPES=python graalpy scripts/check-frozen-polynomial-operation-work.py --frozen-runtime --compare-original
```

Der Standardfall ist das öffentliche Polynom (x^4+x+1) über den Rationalen.
Der Vergleich ruft vor der Messung die unveränderte Bridge mit denselben Daten
auf. Die strukturierte Ausgabe muss bei Faktoren und Einheit übereinstimmen.
Eine abweichende Ausgabe beendet den Lauf. Der Diagnoselauf erlaubt das
Auflisten unbekannter nativer Aufrufe. Für eine Studie genügt er nicht.

Der explizit auswählbare Java-Integrationstest `FrozenSymPyOperationProbeIT`
erwartet die Systemeigenschaften `frozen.checkout`, `frozen.graalpy` und
`frozen.packages`. Er startet den tatsächlichen historischen Interpreter mit
isoliertem Suchpfad und einem strukturierten Java-Polynom. Die Tests in
`FrozenSymPyOperationObservationTest` verwerfen unter anderem manipulierte
Versions-, Eingabe-, Budget-, Ergebnis- und Arbeitsstufenbindungen.

Ohne jede dieser drei Eigenschaften wird ausschließlich diese historische Probe
beim regulären Testscan als nicht angefordert übersprungen. Sobald eine Eigenschaft
gesetzt ist, auch mit leerem Wert, verlangt der Test alle drei nicht leer. Eine
unvollständige Konfiguration oder ungültige Pfade führen zu einem sichtbaren Fehler.
Gradle reicht genau diese explizit gesetzten Eigenschaften an seinen Testworker
weiter. Der bestehende historische Aufruf ist damit zum Beispiel:

```sh
./gradlew :regelsuche-math-sympy:test \
  --tests de.regelsuche.math.sympy.FrozenSymPyOperationProbeIT \
  -Dfrozen.checkout=/path/to/checkout \
  -Dfrozen.graalpy=/path/to/graalpy-25.1.3/bin/graalpy \
  -Dfrozen.packages=/path/to/unpacked-pinned-wheels
```

Die reguläre SymPy-Runtime-Qualifikation benötigt diese historische Provisionierung
nicht. Ihre Auswahl, Laufzeitpins und fachlichen Prüfungen bleiben unverändert.

## Was die 120 externen Formationszeilen tatsächlich erreichen

Die öffentliche Vorprüfung verwendet den regulären Source-Parser,
die gefrorene Reihenfolge der Occurrences, die bestehende nicht
zurücksetzbare Zeilenautorität und die gemeinsame Nested-Pipeline.
Sie stoppt beim ersten tatsächlichen Aufruf von `FactorizationEngine.propose`.
Sie erzeugt weder Kandidat noch Verifier- oder Freeze-Ergebnis. Die dritte Spalte
bezeichnet die größte an diesem ersten Engine-Einstieg noch vorhandene
Anforderungsautorität. Nachfolgende Occurrences einer so angehaltenen Zeile
sind nicht ausgewertet.

| Checkpoint | Zeilen mit erstem Backend-Eintritt (von 20) | Maximale Anforderungsautorität |
| --- | ---: | ---: |
| CP01 | 0 | – |
| CP02 | 0 | – |
| CP03 | 1 | 62 |
| CP04 | 3 | 190 |
| CP05 | 5 | 286 |
| CP06 | 10 | 382 |

Von den verbleibenden 101 Zeilen enden 99 schon im gemeinsamen Pfad am Budget
und zwei mit einem nicht unterstützten Fall. Die 19 erreichten Erstaufrufe
haben Restgrenzen zwischen 20 und 382. Ein künftiger Kandidaten-Freeze darf
einen Budgetabbruch nur verwenden, wenn jede bis zum echten Abbruch ausgeführte
Operation unter der unveränderten v2-Projektion abgedeckt ist. Spätere
unerreichbare Faktorisierungsschritte beeinflussen diesen Präfix nicht.

## Nativer Zufallspfad des historischen GraalPy

Die Release-Datei der untersuchten Linux-amd64-Distribution bindet den
GraalPython-Quellstand `a88321e8b4440a08abaa7b163a85e038a39ec9a0`.
`RandomBuiltins.random` delegiert an `PRandom.nextDouble`. Der Messpfad
rekonstruiert dessen zwei 32-Bit-Ausgaben und gegebenenfalls die Aktualisierung
aller 624 Zustandswörter. Vor Rückgabe vergleicht er den echten nativen
Float64-Wert und jedes Zustandswort einschließlich des Index. Ein Budgetabbruch
während der Rekonstruktion liefert keine Ausgabe und führt den noch nicht
zugelassenen nativen Aufruf nicht aus. Die zusätzliche Gegenrechnung und
Zustandskopie dienen ausschließlich der Prüfung. [Originaler Generator](https://github.com/oracle/graalpython/blob/a88321e8b4440a08abaa7b163a85e038a39ec9a0/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/builtins/objects/random/PRandom.java) · [Nativer Einstieg](https://github.com/oracle/graalpython/blob/a88321e8b4440a08abaa7b163a85e038a39ec9a0/graalpython/com.oracle.graal.python/src/com/oracle/graal/python/builtins/objects/random/RandomBuiltins.java)

`test_frozen_graalpy_random_work.py` prüft den Zustandswechsel beim ersten
Wert, einen Wechsel zwischen den beiden Wörtern desselben Doubles sowie alle
41 Abbruchpräfixe einer Ziehung ohne Zustandswechsel. Die CPython-Referenz
kann dieselben Werte gegenprüfen. Ihr C-Generator gehört nicht zum
quellengebundenen GraalPy-Messpfad.

## Reichweite

Die gesamte Messung unterscheidet den konservativen Abbruch, die Erschöpfung
des zugeteilten Diagnosebudgets und eine bis zum Ende beobachtete Ausführung.
Der Operationszähler unterscheidet gemessene Skalare und einzeln besuchte
logische Einträge. Die Python-Aufrufanzahl ist separat ausgewiesen. Der
GraalPy-Disassembler und CPythons eigene Befehlsdarstellung zeigen beispielsweise,
dass beide `-5` als Konstante laden. Nur GraalPy führt bei `2*3` hier eine
Multiplikation aus. Diese tatsächliche Interpreterdifferenz bleibt erhalten.

Die vorhandenen Kontrollen grenzen den JSON-Transport, native Sammeloperationen
wie Sortieren, die Regex-Namensverarbeitung und fremde Mathematikcallbacks ein.
Sie belegen keine vollständige v2-konforme Bilanz. Die Registry, die Checkpoints
und der geschützte Qualifikationszugang bleiben an ihre bisherigen Bedingungen
gebunden. Es gibt keinen neuen 600-Zeilen-Freeze.

## Lizenzhinweise zum abgeleiteten Generator

```text
/* Copyright (c) 2020, 2021, Oracle and/or its affiliates.
 * Copyright (C) 1996-2020 Python Software Foundation
 *
 * Licensed under the PYTHON SOFTWARE FOUNDATION LICENSE VERSION 2
 */
/*
   The Mersenne Twister implementation was based on:
      http://www.math.sci.hiroshima-u.ac.jp/~m-mat/MT/MT2002/emt19937ar.html
   which included these comments:

   A C-program for MT19937, with initialization improved 2002/1/26.
   Coded by Takuji Nishimura and Makoto Matsumoto.

   Before using, initialize the state by using init_genrand(seed)
   or init_by_array(init_key, key_length).

   Copyright (C) 1997 - 2002, Makoto Matsumoto and Takuji Nishimura,
   All rights reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions
   are met:

     1. Redistributions of source code must retain the above copyright
        notice, this list of conditions and the following disclaimer.

     2. Redistributions in binary form must reproduce the above copyright
        notice, this list of conditions and the following disclaimer in the
        documentation and/or other materials provided with the distribution.

     3. The names of its contributors may not be used to endorse or promote
        products derived from this software without specific prior written
        permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
   CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


   Any feedback is very welcome.
   http://www.math.sci.hiroshima-u.ac.jp/~m-mat/MT/emt.html
   email: m-mat @ math.sci.hiroshima-u.ac.jp (remove space)
*/
```

## Unabhängige Kontrolle der Reihenfolge

Der folgende Befehl erwartet die oben verlinkte originale `PRandom.java`.
Er prüft die Git-Blob-Identität, kompiliert die unveränderte Methode mit
Java 25 und lädt deren tatsächlich ausgeführte Bytecodes mit einem separaten
Zähler. Die beiden Stub-Klassen betreffen nur die unbenutzte Objekt-Basisklasse
und Shape. Der Generator einschließlich seiner Felder und aller Zustandsübergänge
bleibt im kontrollierten Quellstück unverändert.

```sh
python scripts/verify-frozen-graalpy-random-bytecode.py /path/to/PRandom.java --jdk /path/to/jdk-25
```

Für drei öffentliche Zustände stimmen Float64-Ausgabe, alle 624 Wörter,
Fortsetzungsindex und die vollständige geordnete Folge der Arbeitsschritte
überein. Der Zustandswechsel beim ersten Wort und der Übergang zwischen den
zwei Wörtern desselben Doubles ergeben jeweils 11.271 Schritte in
unterschiedlicher Reihenfolge. Die warme Ziehung ergibt 41. Für eine
Exponentialapproximation oder eine flache Einmalgebühr wäre diese Kontrolle
rot. Sie erstreckt sich auf `PRandom.nextInt/nextDouble`.

## JSON-Eintritt im eingebetteten historischen JVM-Prozess

Der öffentliche Kontrolllauf lädt alle 15 durch SHA-256 gebundenen
Runtime-JARs aus der Maven-Provisionierung des GraalPy 25.1.3. Die
ausgepackten Python-Quellen müssen mit den beiden gepinnten Wheels
übereinstimmen. Er verwendet JDK 25.0.5+8, die originale Faktor-Bridge und
einen wiederverwendeten GraalPy-Kontext. Die Python-Seite instrumentiert die
ausgewerteten Einzelbedingungen des originalen `json.loads` aus den
GraalPy-Ressourcen. Die native Seite beobachtet die Originalklassen von
GraalPython und Truffle über die JDK-ClassFile-API.

Für die öffentliche ASCII-Eingabe erreicht der Aufruf zwölf deklarierte
Operationen beim BOM-Test und acht Python-Prüfungen. Die Java-Scope-Grenze
beginnt erst innerhalb des echten `StartsWithNode`. Dadurch werden interne
Stringvergleiche, die beim Übergeben der Beobachtungsdaten auftreten,
ausgeschlossen. Für alle 21 Grenzen von 0 bis 20 prüft ein zweiter Lauf im
gleichen Kontext die genaue Reihenfolge und die erste verweigerte Operation.
Bei 20 endet die Erfassung vor dem regulären Ausdruck für führenden
Whitespace. Die Belege deklarieren die beobachteten Operationen. Eine
vollständige v2-Arbeitsbelegung der Formationszeilen erfordern auch die
nachfolgenden tatsächlich erreichbaren Pfade sowie die Prüfung der Einheiten.

```sh
python scripts/verify-frozen-graalpy-json-admission.py --jdk /path/to/jdk-25 --provision /path/to/unpacked --input /path/to/public-input.json
```

Mit `--mode cold` und `--mode warm` verfolgt derselbe Empfänger zusätzlich
den ersten echten Aufruf von `WHITESPACE.match` im originalen JSONDecoder.
Die warme Entwicklungskontrolle führt einmal die vollständige öffentliche
Faktorisierung vor der Messung aus. Die kalte Kontrolle ruft vor dem ersten
Messversuch nur `runtime_info` auf, wie beim Start des normalen
GraalPy-Workers. Der volle öffentliche Kontrollvergleich beobachtete bei
zwei Aufrufen im gleichen Kontext genau eine Regexp-Kompilation und einen
NFA-Aufbau im ersten und keinerlei Kompilation oder Aufbau im zweiten.
Beide Aufrufe führten den NFA aus. Für die budgetierte Teilmessung stoppt die
kalte Variante vor dem ersten Append der Regex-Optionen, die warme vor dem
Aufruf des Regex-Executors. Sie prüfen alle 27 beziehungsweise 33 einzelnen
Präfixgrenzen. Ein Abbruch hält die bereits erreichte Cache-Instanz am Leben.

### JDK-StringBuilder beim Aufbau der Regex-Optionen

`--mode bulk` bindet zusätzlich das ursprüngliche JDK-Modulimage und dessen
Quellarchiv über SHA-256. Ein Bootstrap-Empfänger erfasst Operationen der
tatsächlich durchlaufenen JDK-Methoden. Bei einer Kopie verschiedener
Byte-Arrays wird jeder einzelne Quellenzugriff und jede Zieländerung vor der
Ausführung zugelassen. Eine unabhängige Kontrolle vergleicht die Ausgabe
und alle sieben Unterbrechungspunkte einer Dreibytekopie mit
`System.arraycopy`. Überlappende Arrays werden vor der ersten Änderung
abgewiesen. Erweiterungen des Backing-Arrays sind in der Zählung enthalten.
Die messende Callback-Ausführung bleibt außerhalb des Arbeitssignals.

Die kalte JDK-Kontrolle `--mode bulk` hält vor dem Aufruf von
`String.length()I` bei Bytecode-Offset 26 in
`AbstractStringBuilder.append(String)` an. Der Aufruf wäre das 31. deklarierte
Ereignis. Der Empfänger meldet `UNMEASURED` nach 30 Ereignissen und vor dem
ersten gelesenen oder geschriebenen Kopierbyte. Der öffentliche Lauf prüft
alle 31 Ereignisgrenzen von 0 bis 30 im selben GraalPy-Kontext.
Die Folge enthält höherstufige Beobachtungen, darunter Python-`isinstance`
und Truffle-`CodePointLengthNode.execute`. Die 30 belegten Ereignisse gelten
für diese instrumentierte Kontrolle. Die vollständige Arbeitsprüfung hält
weiter beim Eintritt in `json.loads` an.

`--mode bulk-survey` behandelt die Aufrufstellen der undurchsichtigen
JDK-Hilfsmethoden als diagnostische Ereignisse und passiert die spätere
Konvertierung des Methodennamens. Die Kontrolle prüft 374 Grenzen über
373 deklarierte Ereignisse, einschließlich je 96 Byte-Lese- und
Schreibereignissen, einem Feldzugriff im originalen
`PythonMethod.getTRegexOption` und einer Kontrolle des fertigen Optionstexts
vor dessen Materialisierung. Die unabhängige Dreibytekopie bleibt ein
separater Kontrollversuch.

Der vorherige `bulk`-Lauf zeichnete 223 Ereignisse auf und prüfte
224 Grenzen. Er passierte die JDK-Lücke und erreichte je 44 Kopierereignisse
sowie den Methodennamen-Feldzugriff. Seine Rohdaten und die frühere
373-Ereignis-Erkundung bleiben mit dem Original-Quellstand, den Laufzeitpins
und der öffentlichen Eingabe gebunden. [Historischer Beleg](frozen-polynomial-string-admission-v1/earlier-receipt.json)
