# Sichtbare, nachvollziehbare Ergebnisse

## Auftrag und Ziel

Carsten hat nach der Prüfung der veralteten Discovery-Galerie die Überarbeitung von README, Dokumentation und den dafür nötigen GUI-Stellen beauftragt. Neue Besucher sollen an aktuellen, reproduzierbaren Beispielen Aufgabe, Ergebnis, Herleitung und Nutzen erkennen können.

## Umfang

- Die vorhandene Paarsummen-Oberfläche zeigt ihren tatsächlich geprüften Plan und den Arbeitsvergleich klar, auch bei 390 Pixel Breite. Eingaben, konkrete Beispielauswertung und hochgerechnete Paaranzahl bleiben unterscheidbar. Geordnete Tupel und Fenster bleiben bedienbar.
- Der Einstieg führt zu einem aussagekräftigen Optimierungsbeispiel und zu den vorhandenen aufgezeichneten Lernvergleichen. Der alte Makro-Lauf darf ohne tatsächliche Wiederverwendung keinen Erfolg suggerieren.
- Die öffentliche Galerie zeigt echte Browseraufnahmen von Paarsummen, geprüftem Regelvergleich und einem realen Replay. Ältere Algebra-Evidence bleibt als nachvollziehbares Detailmaterial erreichbar. Aktuelle Links verwenden shared-core-v3.
- Der Galerie-Generator darf redaktionelle Erläuterungen und Browseraufnahmen nicht mehr überschreiben. Historische Evidence-Dateien werden nicht umgeschrieben, um aktuelle Aussagen zu erzeugen.
- Aufnahme-Tests prüfen das dargestellte Ergebnis vor dem Schreiben. Aufnahmezeit, Quellrevision, Szenario und Dateihash werden nachvollziehbar festgehalten. Normale Tests schreiben keine Dokumentationsdateien.

## Entscheidungen

Die Umsetzung erweitert die bestehenden Java-/HTML-/CSS-/JavaScript-Ansichten und Browsertests. Ein eigenständiges Marketing-Frontend würde den vorhandenen Bedienweg nicht verbessern. Eine bloße Markdown-Änderung wäre wegen des Generators und der unklaren Ergebnisansichten unvollständig. Gewählt ist deshalb eine fokussierte gemeinsame Änderung von Bedienweg, Aufnahmen und Dokumentation.

Neue mathematische Algorithmen, zusätzliche Frameworks und allgemeine Laufzeitbehauptungen sind nicht Teil dieses Auftrags. Forschungszahlen stammen aus ihren bestehenden Versuchsberichten und werden als logische Arbeit auf den jeweiligen Entwicklungssätzen bezeichnet. Status-Badges bleiben oben im README.

## Abnahme

Der Standard-Paarsummenfall zeigt 4.999.950.000 Paare für 100.000 Zeilen und die exakte Beispielauswertung 54 für die drei Werte 2, -1, 5. Ein geänderter oder abgelehnter Ausdruck lässt keinen alten Erfolg sichtbar. Der Lernvergleich zeigt die gespeicherten 26 zu 3 Zustände samt Ziel und unterscheidet ihn von einem neuen Suchlauf. Replay-Aufnahmen stammen aus ausgeführten nativen Suchschritten. Desktop- und schmale Ansichten werden visuell geprüft. Die Galerie lässt sich regenerieren, ohne ihre redaktionellen Abschnitte zu verlieren.
