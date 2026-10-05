# Issue 1657: frische SDK-Rekonstruktion und Qualifikation

Diese Arbeit basiert auf `da1f33195339efe08380b47c36e83455c1e3815f` und dem
Branch `codex/1657-optimization-sdk`. Ein Wartungsereignis entfernte den ersten
Arbeitsbaum einschließlich lokaler Git-Objekte. Der rekonstruierte Stand
verwendet 26 erhaltene Maven-Quellen, klar gekennzeichnete historische
Decompiler-Referenzen und neu geschriebene Implementierung/Regressionen.
Der frühere Pin `6ba237b44dd99ec89a40e8ec73080e9dbcb494d3` sowie dessen
JAR-/ZIP- und Testqualifikation werden nicht auf diese Quellen übertragen.

Quellcheckpoints: `ea4dea173ea9198cda1e2f20fa56d05d66523c1d` stellt die API
wieder her; `4fc49eaf9c4ebd700027c1005dcdb69127d4a316` rekonstruiert die
unabhängigen numerischen Prüfungen. Der nachfolgende Build-Checkpoint bindet
zusätzlich exakt ausgewertete primitive Literal-Casts und die reguläre
Build-/Distributionsintegration. Der Quellbranch ist über
[PR #1069](https://github.com/carstenartur/Regelsuche/pull/1069) veröffentlicht;
`0515e8550c456319efed65267096dbf3e64b4390` ist der veröffentlichte Stand vor
der unten beschriebenen CI-Nachqualifikation. Die endgültige Artefaktrevision
steht im erzeugten Distributionsmanifest, nicht in einem erfundenen
Release-Verweis.

## Frisch ausgeführte Prüfungen

* 43 SDK-JUnit-Tests: Java-25-Maven und Gradle erfolgreich. Rot/Grün-Belege
  erfassen Prepared-Tamper, BigInteger-Grenzen, Kosten, Fold-/Sampling-Grenzen
  und exakt typisierte BYTE/SHORT/CHAR-Literal-Casts.
* `verifySdkApiCompatibility`: erfolgreich mit dem originalen Japicmp-Baseline-
  Vergleich sowie vollständiger neuer Optimizer-Typliste und Modulzuordnung.
* SDK-JaCoCo aus Gradle: 862/1011 Zeilen (85,2621 %) und 832/1238 Zweige
  (67,2052 %) nach dem Methodenrefactoring. Die SDK-Floors 84/64 bleiben
  unverändert; bestehende Modul-/Aggregat-Floors wurden nicht abgesenkt. Das ist keine Aussage über
  die noch ausstehende vollständige Aggregatqualifikation.
* 59 fokussierte Maven-JUnit-Tests: 43 SDK, 8 Search-Plan/DAG, 4 Math-Modular-
  und 4 Experiment-Wiring-Tests, keine Fehler oder ausgelassenen Tests.
* 111 Maven-JUnit-Buildverträge erfolgreich. Der erste vollständige Lauf
  scheiterte am fehlenden `mvn` im PATH eines verschachtelten Assembly-Forks;
  mit dem regulären Maven-Wrapper im PATH besteht derselbe Testvertrag.
  Packaging-Assertions laufen als Java/JUnit; es gibt keine neue parallele
  Python-Testautorität. 48 bestehende Orchestrierungsfixtures bestehen mit
  aktualisierten Eingabelisten für den zusätzlichen Java-Consumer.
* Gradle-Publikationsclosure und externer Java-25-Consumer erfolgreich. Der
  Consumer lief in einem neuen `/tmp`-Projekt mit leerem Gradle-Abhängigkeitscache
  nur gegen das isolierte publizierte Repository. Er prüft die genaue
  6-Modul-Laufzeitclosure, unabhängige Reverification und den ursprünglichen
  Checked-Überlauf.

Ein Inkrementallauf verwendete nachweislich alte SDK-Klassendateien; er bleibt
als fehlgeschlagener Beleg erhalten. Die abschließende SDK-Gradle-Qualifikation
verwendete `:regelsuche-optimization-sdk:clean --no-build-cache`. Quellhash vor
und nach dem Lauf ist gleich; der neue Helper wurde zusätzlich in den erzeugten
Klassen bestätigt. Dieser gezielte Clean entfernt nur die SDK-Buildprodukte.

Die genaue Run-Liste, komprimierte Rohlogs und SHA-256-Liste werden nach den
verbleibenden Consumer-/Paketprüfungen ergänzt. Volles `ciCheck` ist noch nicht
erfolgreich erneut ausgeführt; hierfür wird ein eigener Speicherslot mit den nativen
Sandbox-Läufen abgestimmt. Frühere Docker-/Browser-Fehler sind historische
Befunde und kein Nachweis des rekonstruierten aktuellen Laufs.

## Gezielte SDK-CI-Nachqualifikation vom 4. Oktober 2026

Der reale GitHub-Lauf `37230037423`, Job `111517604884`, scheiterte am
unveränderten AI-Knowledge-Gate: maximale kognitive Methodenkomplexität 70
bei Limit 65 und maximale zyklomatische Methodenkomplexität 48 bei Limit 35.
Der erzeugte Methodenreport identifiziert drei betroffene SDK-Methoden:

| Methode | Zyklomatisch vorher → nachher | Kognitiv vorher → nachher |
| --- | ---: | ---: |
| `JavaCandidateGenerator.simplify` | 48 → 8 | 70 → 7 |
| `SemanticChecker.validate` | 44 → 18 | 62 → 18 |
| `JavaNumericBackend.operation` | 38 → 24 | 43 → 27 |

Die Refactorings extrahieren additive, multiplikative/bitweise und konstante
Vorschläge, Quelltrace-/Annahmenvalidierung sowie die Bestimmung der
Argumenttypen. Der anschließende vollständige lokale Gate-Lauf identifizierte
zusätzlich `SemanticChecker.check` als neuen Hotspot; die vorhandenen
Kongruenz- und Integralbeweise wurden ebenfalls als private Helfer extrahiert
(zyklomatisch 31 → 16, kognitiv 63 → 27). Entscheidungs- und Beweisreihenfolge,
Work-/Cancellation-Aufrufe, Fehlergrenzen und numerische Verträge bleiben
unverändert. Es gibt keine Unterdrückung, Baselineänderung oder Gateabsenkung.

Der vollständige Root-Task `aiKnowledgeCheck` besteht einschließlich
Artefaktprüfung, acht Capability-Coverage-Kontrollen und Hotspot-Gate.
Repository-Maxima: kognitiv 64 und zyklomatisch 33. Der Lauf verwendet die
unveränderte offizielle Extractor-Quelle des Tags `v0.1.10`, Commit
`b409bed957c31d63ce7b6ef37205890f0f0ebd9a`, im expliziten lokalen Pluginmodus.
Ein externes Init-Skript kompiliert sie mit JDK 25 bei unverändertem
Java-17-Releaseziel; die Umgebung enthält keinen JDK-17-Compiler.

43 SDK-Tests bestehen erneut mit Maven und mit gezieltem Gradle-Clean ohne
Buildcache. `verifySdkApiCompatibility` besteht frisch gegen die originale
Japicmp-Baseline. Die oben angegebenen SDK-Coveragewerte stammen aus diesem
Clean-Lauf und liegen über den unveränderten Floors 84/64. JVMs bleiben
begrenzt, Gradle verwendet einen Worker. Eine neue vollständige
Aggregatqualifikation oder Maven-Veröffentlichung wird nicht behauptet;
der endgültige Distributionspin bleibt der im erzeugten Manifest.

## Semantik und Adaptergrenzen

API 1, numerische Semantik `java25-numeric/v1` und Evidence-Schema
`regelsuche.optimization-evidence/v1` bleiben erhalten. Unabhängiger Checker
und Vorschlagsgenerator verwenden Revision v3; ein alter Evidence-Hash oder
eine alte Checkerrevision autorisiert den neuen Kandidaten nicht.

Die BigInteger-Grenzprüfung beweist alle ursprünglichen und neuen
Zwischenwerte innerhalb des konservativen Größenfragments. Laufzeithelfer
prüfen den absoluten Bitlängenvertrag und skalare 0..Obergrenze-Verträge.
Counterexample-Diagnostik und Constant Folding führen keine schweren
BigInteger-Potenzen oder Shifts aus. Checked-Kosten enthalten sämtliche
ursprünglichen Operationsvorkommen einschließlich Duplikaten.

Die strikte Emissionsprüfung erkennt `MOD_MULTIPLY(a,b,n)` und die exakt gleiche
Java-Komposition `a.multiply(b).mod(n)`; sie entfernt keine Normalisierung.
Primitive Literal-Cast-Ketten dürfen nur vollständig geschlossen und gemäß
der konkreten Java-Konversion ausgewertet werden. Variable Casts bleiben
typisiert; der negative 256→byte→int-Fall bleibt widerlegt. Der Sandbox-Emitter
rendert CHAR separat als echtes CharacterLiteral; BYTE/SHORT benötigen häufig
die hier geprüften Literal-Casts. IEEE-Rundungs- und NaN-Verträge bleiben
unverändert.

## Architektur und lokaler Distributionsvertrag

Search und Math behalten ihre vorhandenen Abhängigkeitsgrenzen. Die öffentliche
Verdrahtung `de.regelsuche.math.algorithms.modular.ModularJointPlans` liegt
physisch im SDK; Experiments konsumiert sie. Kein Math→Search-Rückweg, keine
Eclipse-/App-/Experiments-Laufzeitabhängigkeit, keine neue Suchmaschine oder
konkurrierende mathematische AST. Docker-Copy-Listen, Reactor-Modulliste,
Coverage, BOM, API-Gate und normale SDK-Publikationsclosure enthalten das SDK.

Der qualifizierte Distributionsweg ist ein lokal reproduzierbares,
commit-gepinntes `all`-JAR/ZIP mit vollständiger Closure, Lizenzen, Quellen,
Javadoc und sauberem Consumer. Der Quellbranch ist über PR #1069 veröffentlicht; es wird keine öffentliche
Maven-Verfügbarkeit behauptet. Der vollständige normale
Release-/CI-Vertrag bleibt bestehen; ein lokales fokussiertes Ergebnis ersetzt
keinen noch offenen Gesamtgate. Diese Auslieferungsentscheidung ist die
begründete lokale Distributionsausnahme für den veröffentlichten Quellbranch,
nicht eine Ausnahme von numerischen Beweisen oder Build-/Coverage-Gates.

Für die Clean-Reproduktion wird `project.build.outputTimestamp` auf den
Unix-Commitzeitpunkt des eingefrorenen Codepins gesetzt. Das ist notwendig,
weil das Manifest die rohen Eingabe-JAR-Hashes enthält und nicht nur
normalisierte Klassenbytes. Der genaue Befehl steht in der SDK-README;
unterschiedliche Maven-Test-/Profil-/Zeitstempeloptionen gelten nicht als
identischer reproduzierbarer Build.
