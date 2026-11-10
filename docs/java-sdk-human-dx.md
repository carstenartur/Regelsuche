# Human-DX-Protokoll für Issue #904

Status: **nicht durchgeführt**. CI-Erfolg ersetzt keine Teilnehmerdaten.

## Vorbereitete Links und Aufgaben

- [English entry](start-here.md): Forschungsziel, drei Arbeitsabläufe und Claim-Grenzen.
- [Aufzeichnung ohne Installation](demos/recorded-discovery.html): eine HTML-Datei
  herunterladen und lokal öffnen. GitHub zeigt bei einem Blob-Link HTML-Quelltext;
  es wird keine bereits gehostete neue Live-Demo behauptet.
- [Release v0.5.2](https://github.com/carstenartur/Regelsuche/releases/tag/v0.5.2),
  [Maven-Quickstart](java-sdk-quickstart.md) und
  [vollständiges Consumer-Verzeichnis](../examples/external-consumers/hello-rule-java25/).

Die neuen Dokumentationslinks sind nach Integration dieses Commits über die
Repository-Navigation teilbar; vor Integration kann das HTML als Datei geprüft
werden. Es wurden keine Nachrichten versandt und keine Teilnehmer gewonnen.
Für einen späteren kurzen Erstkontakt genügt ein konkreter Link plus eine
Aufgabe aus der folgenden Tabelle; Antworten müssen mit Version und Umgebung
aufgezeichnet werden.

| Aufgabe | Konkreter Arbeitsauftrag | Erfolg und Beobachtung |
| --- | --- | --- |
| 1: Evidenz lesen, 5 Minuten Zielzeit | Die Aufzeichnung ohne Hilfe öffnen, zur letzten primitiven Umformung navigieren und die tatsächlich ausgeführte Regel nennen. | Richtiger Regelname; kann die Person `oracleStatus=UNAVAILABLE` von einem formalen Beweis unterscheiden? Tastatur-/Downloadprobleme und Hilfen erfassen. |
| 2: Release konsumieren, 15 Minuten Zielzeit | SDK 0.5.2 und vollständiges hello-rule-Verzeichnis beziehen; Prüfsummen prüfen, Maven-Paket bauen und CLI ausführen. | Zwei JUnit-Tests und erwartete CLI-Ausgabe; Installations-/Downloadzeit getrennt erfassen. DNS-/Repositoryfehler zählen als Abbruch mit konkreter Meldung. |
| 3: Regelgrenze prüfen, 10 Minuten Zielzeit | Die Eingabe auf `x + 1` ändern oder den vorhandenen negativen Test erklären; begründen, was `replay=VERIFIED` aussagt. | Keine Umformung mit der Add-zero-Regel; kein pauschaler Beweisclaim für Plugin-Code. Fehlversuche und benötigte externe Hinweise erfassen. |

Zielzeiten sind Hypothesen für die Planung, keine gemessenen Erfolgsquoten.
Das vorhandene tiefere Protokoll bleibt für Domain-Autoren relevant:

## Vertiefte Domänenerprobung

Zwei bis drei Personen mit Mathematik- und gewöhnlichen Java-Grundkenntnissen
bearbeiten ohne mündliche Hilfe dieselbe veröffentlichte SDK-Version. Sie
benutzen ausschließlich deren Anleitungen und einen frischen Arbeitsplatz mit
JDK 25. Notiere getrennt Installation/Downloads und eigentliche Aufgabenzeit.

| Aufgabe | Ziel | Beobachtungen |
|---|---|---|
| A: eigene Regel | positiver und negativer Test, CLI-Replay | Zeit bis erstem Lauf, Fehlversuche, externe Hilfen |
| B: geprüfter Operator | falschen Solvervorschlag zurückweisen | Prüfer unabhängig? Zertifikatsgrenze richtig erklärt? |
| C: eigene Domäne | TRAIN/HOLDOUT und kanonische Evidence | Zeit, Hilfen, korrekt interpretierte Status |

Pro Teilnehmer pseudonyme ID, Java-Erfahrung, SDK-Hash, Betriebssystem,
Aufgabenstart/-ende, Fehlversuche, Hilfen und Abbruchgrund festhalten. Niemand
muss die Zielzeiten erreichen. Frage anschließend nach dem Unterschied zwischen
`CONFIRMED`, `REFUTED`, `INCONCLUSIVE` und `BUDGET_EXHAUSTED` und dokumentiere die
Antwort ohne nachträgliche Korrektur. Berichte auch Nullresultate. Leite daraus
konkrete Tutorialänderungen ab und wiederhole nur die betroffenen Aufgaben.

| Teilnehmer | Aufgabe | Dauer | Hilfen | Fehlversuche | Status korrekt? |
|---|---|---|---|---|---|
| ausstehend | — | — | — | — | — |
