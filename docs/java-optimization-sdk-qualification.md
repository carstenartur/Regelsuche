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
Build-/Distributionsintegration. Seine endgültige Artefaktrevision steht im
erzeugten Distributionsmanifest, nicht in einem erfundenen Release-Verweis.

## Frisch ausgeführte Prüfungen

* 42 SDK-JUnit-Tests: Java-25-Maven und Gradle erfolgreich. Rot/Grün-Belege
  erfassen Prepared-Tamper, BigInteger-Grenzen, Kosten, Fold-/Sampling-Grenzen
  und exakt typisierte BYTE/SHORT/CHAR-Literal-Casts.
* `verifySdkApiCompatibility`: erfolgreich mit dem originalen Japicmp-Baseline-
  Vergleich sowie vollständiger neuer Optimizer-Typliste und Modulzuordnung.
* SDK-JaCoCo aus Gradle: 846/991 Zeilen (85,3683 %) und 824/1230 Zweige
  (66,9919 %). Die neuen Floors 84/64 bleiben unverändert; bestehende
  Modul-/Aggregat-Floors wurden nicht abgesenkt. Das ist keine Aussage über
  die noch ausstehende vollständige Aggregatqualifikation.
* Maven-JUnit-Buildverträge für Packaging, Reactor und Dependency Management
  erfolgreich. Packaging-Assertions laufen als Java/JUnit; es gibt keine neue
  parallele Python-Testautorität.

Die genaue Run-Liste, komprimierte Rohlogs und SHA-256-Liste werden nach den
verbleibenden Consumer-/Paketprüfungen ergänzt. Volles `ciCheck` ist noch nicht
erneut ausgeführt; hierfür wird ein eigener Speicherslot mit den nativen
Sandbox-Läufen abgestimmt. Frühere Docker-/Browser-Fehler sind historische
Befunde und kein Nachweis des rekonstruierten aktuellen Laufs.

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
Javadoc und sauberem Consumer. Es wird keine öffentliche Maven-Verfügbarkeit
behauptet und nichts remote veröffentlicht. Der vollständige normale
Release-/CI-Vertrag bleibt bestehen; ein lokales fokussiertes Ergebnis ersetzt
keinen noch offenen Gesamtgate. Diese Auslieferungsentscheidung ist die
begründete lokale Ausnahme für den noch unveröffentlichten Companion-Branch,
nicht eine Ausnahme von numerischen Beweisen oder Build-/Coverage-Gates.
