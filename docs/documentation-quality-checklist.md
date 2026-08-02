# Documentation Quality Checklist

Diese Checkliste gilt für README, Handbücher, Architektur- und
Entwicklerseiten, Forschungsberichte, Betriebsdokumentation und generierte
Ergebnisse. Nicht jeder Punkt ist für jeden Seitentyp relevant; nicht
zutreffende Punkte werden bewusst als solche behandelt.

## 1. Rolle und Zielgruppe

- [ ] Die Seite besitzt eine erkennbare primäre Rolle: Einstieg, Handbuch,
      Architektur, Forschung, Betrieb, Referenz oder generierter Bericht.
- [ ] Zielgruppe und erwartetes Vorwissen sind aus Titel und Einleitung klar.
- [ ] Die Seite beantwortet eine konkrete Nutzer- oder Entwicklungsfrage.
- [ ] Detailtiefe und Begriffe passen zur Zielgruppe.
- [ ] Historische Inhalte sind als historisch gekennzeichnet und nicht Teil des
      aktuellen Hauptpfads.

## 2. Quelle der Wahrheit

- [ ] Die Seite dupliziert keinen Vertrag, der an anderer Stelle autoritativ
      gepflegt wird.
- [ ] REST-Methoden, Pfade, Payloads und Statuscodes verweisen auf OpenAPI.
- [ ] Capability- und Claim-Status stammen aus generierter, gebundener Evidence.
- [ ] Der aktuelle Forschungsstand steht auf der datierten Statusseite.
- [ ] Schema-Details verweisen auf versionierte Dateien unter `docs/schemas/`.
- [ ] Test- und CI-Aussagen entsprechen den checkout-eigenen Gradle-Tasks.

## 3. Struktur und Navigation

- [ ] Die Einleitung benennt Zweck und Ergebnis der Seite.
- [ ] Überschriften bilden einen nachvollziehbaren Aufgaben- oder Systemfluss.
- [ ] Lange Linklisten wurden in zielgerichtete Gruppen oder Referenzseiten
      ausgelagert.
- [ ] Die Seite enthält nur wenige relevante nächste Schritte.
- [ ] Relative Links, Anker und Asset-Pfade sind gültig.
- [ ] Der zentrale Dokumentationsindex verweist auf die Seite, wenn sie ein
      unterstützter Einstieg ist.

## 4. Sprache und Verständlichkeit

- [ ] Die Seite verwendet überwiegend eine Sprache; technische IDs bleiben
      unverändert.
- [ ] Sätze sind vollständig, konkret und aktiv formuliert.
- [ ] Interne Klassen- oder Servicenamen erscheinen nur in Entwicklerseiten.
- [ ] Fachbegriffe sind im [Glossar](glossary.md) erklärt oder lokal definiert.
- [ ] Statusbegriffe wie `QUALIFIED`, `BLOCKED` und `NOT_EVALUATED` werden nicht
      vermischt.
- [ ] Marketingaussagen sind entfernt oder durch einen benannten Vertrag
      begrenzt.

## 5. Nutzerhandbücher und UI

- [ ] Der Ablauf beginnt mit Tab, Panel, Schaltfläche oder sichtbarem Einstieg.
- [ ] Voraussetzungen und erforderliche Eingaben sind beschrieben.
- [ ] Das sichtbare Ergebnis und der nächste Schritt sind erklärt.
- [ ] Bereit-, Lade-, Leer-, Erfolgs- und Fehlerzustände sind nachvollziehbar.
- [ ] Die Seite beschreibt den fachlichen Flow, nicht die interne Requestfolge.
- [ ] Eine fehlende GUI wird als Produktlücke behandelt und nicht durch mehr
      REST-Prosa kaschiert.
- [ ] UI-Bezeichnungen stimmen mit der aktuellen Oberfläche überein.
- [ ] Änderungen am Flow sind durch Browser-E2E abgedeckt.

## 6. Mathematische Darstellung

- [ ] Eingabe, Ergebnis und Rechenweg sind getrennt dargestellt.
- [ ] Annahmen und Definitionsbereiche stehen sichtbar bei der Aussage.
- [ ] Unicode-/LaTeX-Anzeige und technische Eingabesyntax werden nicht im selben
      Feld vermischt.
- [ ] `*`/`·`, `^`/Hochstellung und Klammerung werden konsistent verwendet.
- [ ] Ein Suchpfad wird nicht ohne Proof-Evidence als formaler Beweis bezeichnet.
- [ ] Validierung, Refutation und Nichtausführung sind sprachlich getrennt.

## 7. Forschungs- und Statusseiten

- [ ] Die Seite trägt einen Datums- oder Versionsstand.
- [ ] Corpus, Track, Informationsgrenze und Ressourcenbudget sind benannt oder
      verlinkt.
- [ ] Konfigurierte, ausgeführte, übersprungene und verbleibende Arbeit bleibt
      sichtbar.
- [ ] Negative Ergebnisse und Nullresultate werden vollständig berichtet.
- [ ] Projekt-Neuheit ist von externer mathematischer Neuheit getrennt.
- [ ] Search Improvement, Validation, Proof, Utility, Interestingness,
      Promotion und Public Evidence werden nicht zu einem Erfolgsflag
      zusammengezogen.
- [ ] Messwerte besitzen Einheit, Vergleichsbasis und Reproduktionsverweis.
- [ ] Wandzeitangaben nennen Umgebung und ersetzen keine kanonische
      Work-Bilanz.
- [ ] Schlussfolgerungen überschreiten den autorisierten Claim nicht.

## 8. Architektur- und Entwicklerseiten

- [ ] Verantwortungen und Abhängigkeitsrichtungen sind klar.
- [ ] Diagramme stimmen mit `settings.gradle`, Modulstruktur und
      Dependency-Regeln überein.
- [ ] Technologieabhängigkeiten sind an äußeren Adaptern verortet oder
      begründet.
- [ ] Trust- und Informationsgrenzen sind explizit.
- [ ] Änderungskriterien und erforderliche Tests sind genannt.
- [ ] Beispiele beschreiben unterstützte Patterns und keine zufälligen
      Implementierungsdetails.

## 9. Betrieb und Sicherheit

- [ ] Demo-, Entwicklungs- und Produktionsannahmen sind getrennt.
- [ ] Bind-Adressen, Authentifizierung, TLS und Zugangsdaten werden nicht
      missverständlich dargestellt.
- [ ] Persistenz-, Backup-, Migrations- und Diagnosegrenzen sind benannt, soweit
      relevant.
- [ ] Unsichere Fallbacks sind als lokale Demo-Defaults gekennzeichnet.
- [ ] Fehler- und Recovery-Verhalten ist beschrieben.

## 10. Bilder, Diagramme und Medien

- [ ] Bilder besitzen aussagekräftigen Alt-Text.
- [ ] Der umgebende Text erklärt die relevante Aussage des Bildes.
- [ ] Screenshots werden aus dem zugehörigen Browser-E2E-Flow erzeugt.
- [ ] Diagramme sind auch ohne Farbinformation verständlich.
- [ ] Bilder ersetzen keine notwendigen Zahlen, Grenzen oder Textaussagen.
- [ ] Exportformate werden nach ihrem Nutzen erklärt, nicht nur aufgelistet.

## 11. Generierte Dokumentation

- [ ] Generierte Dateien oder markierte Abschnitte wurden nicht manuell editiert.
- [ ] Quelle, Generator und Verifier sind auffindbar.
- [ ] Marker sind eindeutig und vollständig.
- [ ] Manueller Text widerspricht dem generierten Status nicht.
- [ ] Ein Regenerationslauf ist deterministisch oder dokumentiert diagnostische
      Abweichungen ausdrücklich.
- [ ] Source-Tree-Dateien werden durch einen normalen Build nicht als
      Nebeneffekt verändert.

## 12. Reproduktion und Review

- [ ] Ausführbare Befehle verwenden den Gradle Wrapper oder dokumentierte
      Checkout-Skripte.
- [ ] Voraussetzungen sind vollständig.
- [ ] Erwartete Outputs oder Artefaktpfade sind beschrieben.
- [ ] Der kleinste passende Verifikationstask wurde ausgeführt.
- [ ] Link- und Mathematikprüfung ist grün.
- [ ] Bei Änderungen an Claims oder Evidence ist der zugehörige unabhängige
      Verifier grün.
- [ ] Der Checkout bleibt nach normaler Verifikation sauber.

## Mindestkriterien nach Seitentyp

| Seitentyp | Unverzichtbar |
| --- | --- |
| README / Einstieg | klare Projektbeschreibung, Schnellstart, Statusgrenze, zielgruppenbezogene Navigation |
| Nutzerhandbuch | sichtbarer Flow, Zustände, Ergebnis, Browser-E2E |
| Architektur | Verantwortung, Schichten, Abhängigkeiten, Trust-Grenzen |
| Forschung | Datum, Track, Evidence, negatives Accounting, Claim und Nicht-Claims |
| Betrieb | Voraussetzungen, Sicherheit, Persistenz und Diagnose |
| Referenz | stabile Begriffe, Links zur autoritativen Maschine-Quelle |
| Generierter Bericht | Quelle, Hashbindung, Generator, Verifier und Nicht-Editierbarkeit |

## Akzeptanzkriterium

Eine Seite gilt als professionell gepflegt, wenn sie:

1. eine eindeutige Rolle besitzt;
2. keine konkurrierende Quelle der Wahrheit erzeugt;
3. für ihre Zielgruppe handlungsfähig und verständlich ist;
4. Claims, Status und Grenzen präzise wiedergibt;
5. reproduzierbar geprüft werden kann;
6. in die zentrale Navigation eingeordnet ist;
7. durch normale Verifikation keinen unbeabsichtigten Source-Tree-Change
   erzeugt.
