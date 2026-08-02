# Plugin-Veröffentlichung, Kompatibilität und Governance

Diese Seite definiert den öffentlichen Prozessvertrag für externe Regelsuche-
Erweiterungen. Sie beschreibt, wie Plugins, Regelpakete und Knowledge Packs
gebaut, versioniert, geprüft, kuratiert, veröffentlicht und im Sicherheitsfall
gesperrt werden sollen.

> Der Vertrag ist bereits für lokale, content-addressed Artefakte, signierte
> Indexrevisionen und authentisierte Trust-State-Revisionen anwendbar. Ein
> gehosteter Katalogtransport sowie Download, Installation, Update, Entfernung
> und Rollback durch den Client sind noch nicht vollständig implementiert. Diese
> Seite dokumentiert deshalb keinen bereits verfügbaren öffentlichen
> Marktplatzdienst.

## Status und Claim-Grenze

| Teilfähigkeit | Status |
| --- | --- |
| Lokale Plugin- und Regelpaket-Erkennung | `IMPLEMENTED` |
| Ed25519-Prüfung externer Plugin-JARs vor dem Laden | `IMPLEMENTED` |
| Unveränderlicher lokaler Artifact Index | `IMPLEMENTED` |
| Signierte Indexrevisionen | `IMPLEMENTED` |
| Signierte, replay-sichere Trust-State-Revisionen | `IMPLEMENTED` |
| Gehosteter oder föderierter Katalogtransport | `BLOCKED` |
| Clientseitiger Download- und Installationslebenszyklus | `BLOCKED` |
| Öffentliche End-to-End-Distribution | `BLOCKED` |
| Unabhängig veröffentlichte Community-Beispielprojekte | `NOT_EVALUATED` |

`IMPLEMENTED` bedeutet, dass der jeweilige Software- und Evidence-Vertrag im
Checkout vorhanden und getestet ist. Es bedeutet nicht, dass bereits ein
öffentlicher Dienst betrieben wird.

## Artefaktarten

Der Katalogvertrag unterscheidet mindestens:

- **Java-Plugin:** ausführbares JAR mit
  `META-INF/services/de.regelsuche.plugin.RegelsuchePlugin`;
- **Regelpaket:** deklarative `.regelsuche`- oder `.rules`-Datei;
- **Knowledge Pack:** kuratierter, versionierter Regelbestand mit Provenienz,
  Reviewstatus und Aktivierungsgrenzen.

Artefaktart, Koordinaten, Version, Publisher, Kompatibilität, Capabilities,
Abhängigkeiten, Provenienz und Content-Hash müssen im Artifact Index explizit
gebunden sein. Dateiname oder Download-URL allein sind keine Identität.

## Rollen und Gewaltenteilung

### Publisher

Der Publisher baut und signiert ein Artefakt. Er kontrolliert den privaten
Artefakt-Signaturschlüssel und verantwortet:

- Quell- und Lizenzprovenienz;
- reproduzierbare Release-Eingaben;
- korrekte Versions- und Kompatibilitätsangaben;
- vollständige Abhängigkeitsdeklaration;
- unverzügliche Incident-Meldung.

### Curator

Der Curator prüft eine Einreichung und signiert eine unveränderliche
Indexrevision. Eine Curator-Signatur ersetzt nicht die Publisher-Signatur des
Artefakts. Sie bestätigt nur, dass genau der gebundene Indexinhalt unter der
dokumentierten Kurationspolicy veröffentlicht wurde.

### Trust Authority

Die Trust Authority autorisiert und widerruft Publisher- und Curator-Keys über
signierte Trust-State-Revisionen. Ihre Root-Keys bleiben vom verteilten
Arbeits-Trust-Store getrennt und lokal gepinnt.

### Client-Operator

Der Operator entscheidet, welche Trust Domain, Authority-Roots, Indexquellen und
Installationspolicy für eine konkrete Installation gelten. Ein öffentlicher
Katalog darf lokale Sicherheitsentscheidungen nicht stillschweigend
überschreiben.

Für produktive Umgebungen sollten Publisher, Curator und Trust Authority soweit
praktikabel organisatorisch getrennt sein.

## Veröffentlichungsbundle

Eine kuratierbare Release-Einreichung enthält mindestens:

```text
release/
  <artifact>
  <artifact>.sig.json
  provenance.json oder gleichwertige Attestierung
  sbom.cdx.json
  release-notes.md
  compatibility.md
  LICENSE oder eindeutige Lizenzreferenz
```

Für Java-Plugins kommen hinzu:

```text
META-INF/services/de.regelsuche.plugin.RegelsuchePlugin
```

Das Detached Signature Manifest bindet mindestens:

- exakten Artefaktnamen;
- SHA-256 der veröffentlichten Bytes;
- Publisher-ID;
- Key-ID;
- Algorithmus `Ed25519`;
- Signatur über den kanonischen, längenpräfixierten Payload.

Private Schlüssel, geheime Recovery-Werte und Trust-Authority-Schlüssel gehören
niemals in Artefakt, Repository, Index oder öffentliche Attestierung.

## Reproduzierbarer Publishing-Ablauf

### 1. Release-Eingaben einfrieren

Vor dem Build werden mindestens festgelegt:

- Quellrevision und sauberer Checkout;
- Plugin-, API- und Mindest-Core-Version;
- Buildwerkzeug und JDK-Version;
- direkte Abhängigkeiten und Repositories;
- Artefaktart, Capabilities und Lizenz;
- Publisher- und Signaturschlüssel-ID.

### 2. Artefakt reproduzierbar bauen

Der dokumentierte Release-Befehl muss aus einem frischen Checkout ausführbar
sein. Zeitstempel, absolute Pfade und zufällige Dateireihenfolgen dürfen die
Artefaktbytes nicht unnötig verändern.

Mindestens zwei isolierte Builds sollen verglichen werden. Sind die Bytes nicht
reproduzierbar, muss die Provenienzattestierung die zulässigen Abweichungen
benennen; der veröffentlichte Content-Hash bleibt dennoch die maßgebliche
Artefaktidentität.

### 3. Inhalt prüfen

Vor der Signatur werden geprüft:

- reguläre, nicht symbolische Artefaktdatei;
- erwartete Service-Provider-Einträge bei JARs;
- deklarierte Lizenz- und Provenienzdateien;
- keine privaten Schlüssel, Tokens oder lokalen Konfigurationen;
- konsistente Versionen in Artefakt, Metadaten und Release Notes;
- SBOM und Abhängigkeitsinventar;
- Checkout-eigene Tests gegen die veröffentlichte Plugin-API.

### 4. Exakte Bytes signieren

Der Publisher berechnet den SHA-256-Wert der finalen Artefaktbytes und erzeugt
das Detached Manifest. Nach der Signatur dürfen diese Bytes nicht mehr verändert
werden.

### 5. Indexeintrag erzeugen

Der Eintrag im `regelsuche.plugin-artifact-index/v1` bindet genau das signierte
Artefakt und seine Metadaten. Koordinate und Version dürfen innerhalb einer
Trust Domain nicht auf andere Bytes umgebogen werden.

### 6. Unabhängig verifizieren

Vor der Kurationsentscheidung werden mindestens wiederholt:

- SHA-256-Prüfung;
- Publisher-Signaturprüfung;
- Publisher-/Key-Trust-Entscheidung;
- Kompatibilitäts- und Dependency-Auflösung;
- Provenienz- und Lizenzprüfung;
- Negativprüfung gegen widerrufene Keys und Artefakte.

### 7. Neue Indexrevision veröffentlichen

Akzeptierte Einträge werden in eine neue, unveränderliche und signierte
Indexrevision aufgenommen. Bestehende Revisionen werden nicht überschrieben.
Korrekturen erzeugen eine neue Revision mit neuer Identität.

## Versions- und Kompatibilitätsrichtlinie

### Plugin-Version

Für externe Erweiterungen wird semantische Versionierung empfohlen:

- **Patch:** kompatible Fehlerbehebung ohne neue erforderliche Capability;
- **Minor:** rückwärtskompatible Funktionserweiterung;
- **Major:** inkompatible Änderung von Verhalten, Datenformat oder öffentlichem
  Pluginvertrag.

Eine veröffentlichte Version ist unveränderlich. Andere Bytes benötigen immer
eine neue Version, auch wenn nur Metadaten oder Paketierung korrigiert wurden.

### API-Version

`apiVersion()` beschreibt den öffentlichen Pluginvertrag. Eine Erweiterung darf
nur geladen werden, wenn der Client diese API-Version unterstützt. Ein Major-
Wechsel der Plugin-API verlangt eine explizite Kompatibilitätsentscheidung;
zufällige Binärkompatibilität genügt nicht.

### Mindest-Core-Version

`minimumCoreVersion()` definiert die älteste unterstützte Regelsuche-Version.
Der Wert darf nicht künstlich abgesenkt werden, wenn das Plugin neuere Klassen,
Schemas, Capabilities oder Semantiken voraussetzt.

### Abhängigkeiten

Jede erforderliche Plugin-Abhängigkeit enthält:

- stabile Plugin-ID;
- Versionsconstraint;
- Kennzeichnung als erforderlich oder optional.

Fehlende, zyklische, mehrdeutige oder inkompatible erforderliche Abhängigkeiten
blockieren die Auflösung. Ein Curator darf Konflikte nicht durch eine
nicht dokumentierte Reihenfolgeentscheidung verdecken.

### Capabilities

Capabilities sind maschinenlesbare Voraussetzungen und Angebote. Neue
sicherheits- oder semantikrelevante Capabilities dürfen nicht als reine
Metadatenänderung in einer bestehenden Artefaktversion erscheinen.

## Kurationsprozess

### Einreichung

Eine Einreichung benennt:

- Artefaktart und Koordinate;
- Publisher und Kontaktweg für Sicherheitsmeldungen;
- Quellrepository und Release-Revision;
- Lizenz und Drittanbieterbestandteile;
- Build- und Testanweisung;
- Signaturmanifest, Provenienz und SBOM;
- Kompatibilitätsbereich und Abhängigkeiten;
- gewünschte Capabilities und Kurzbeschreibung.

### Technische Vorprüfung

Die Vorprüfung ist fehlersicher sperrend. Sie weist insbesondere zurück:

- fehlende oder ungültige Signatur;
- Hash- oder Identitätsabweichung;
- unbekannten oder widerrufenen Publisher-Key;
- veränderliche Downloadziele ohne gebundene Bytes;
- fehlende Lizenz- oder Provenienzangaben;
- inkompatible API-/Core-Version;
- unauflösbare oder zyklische Dependencies;
- nicht reproduzierbaren Build ohne dokumentierte Erklärung;
- versteckte Netzwerk-, Dateisystem- oder Prozessanforderungen;
- irreführende Claims zu Proof, Neuheit oder Sicherheit.

### Fachliche Review

Die fachliche Review beurteilt unter anderem:

- klaren Anwendungszweck;
- Qualität und Nachvollziehbarkeit der Regeln oder Erweiterungen;
- explizite Annahmen und Grenzen;
- Konflikte mit bestehenden IDs oder Semantiken;
- Tests für positive, negative und Randfälle;
- verständliche Nutzer- und Entwicklerdokumentation.

Fachliche Akzeptanz autorisiert keine mathematische Neuheit und keinen formalen
Beweis. Solche Claims benötigen ihre eigenen Evidence-Gates.

### Entscheidung

Mögliche Kurationsentscheidungen sind:

- `ACCEPTED`: Aufnahme in eine neue Indexrevision;
- `CHANGES_REQUIRED`: konkret benannte Mängel vor erneuter Einreichung;
- `REJECTED`: begründete Ablehnung ohne Veröffentlichung;
- `SUSPENDED`: vorläufige Sperre während einer Incident-Untersuchung;
- `REVOKED`: dauerhafte Sperre eines Keys oder konkreten Artefakthashs.

Diese Begriffe beschreiben die Governance. Runtime- und Evidence-Status bleiben
die in den jeweiligen Schemas definierten Werte.

Ablehnungen sollen den betroffenen Vertrag und eine überprüfbare Begründung
nennen. Eine Ablehnung aufgrund fehlender Evidence ist keine Aussage über die
mathematische Qualität einer Idee.

## Sicherheits- und Incident-Prozess

### Meldekanal und Triage

Eine Veröffentlichung benötigt einen dokumentierten privaten Meldekanal. Nach
einer Meldung werden mindestens erfasst:

- betroffene Koordinate, Version und Artefakthash;
- Publisher-, Curator- und Key-Identitäten;
- Art und mögliche Auswirkung des Vorfalls;
- bekannte betroffene Index- und Trust-State-Revisionen;
- Zeitpunkt der ersten Kenntnis;
- vorläufige Eindämmungsentscheidung.

Öffentliche Issue-Kommentare sind für noch nicht offengelegte
Sicherheitsdetails ungeeignet.

### Eindämmung

Abhängig vom Vorfall kommen getrennt infrage:

- Sperre eines konkreten Artefakthashs;
- Widerruf eines Publisher-Keys;
- Widerruf eines Curator- oder Authority-Keys;
- neue Trust-State-Revision;
- neue Indexrevision ohne den betroffenen Eintrag;
- Empfehlung zur Deaktivierung oder Entfernung;
- Rotation auf einen explizit gebundenen Nachfolgeschlüssel.

Ein Artefaktwiderruf gewinnt gegen eine ansonsten gültige Signatur. Ein neuer
Index darf einen alten Trust-State nicht wieder freigeben.

### Korrektur

Korrigierte Artefakte erhalten neue Bytes, neue Version, neue Signatur und neuen
Indexeintrag. Bereits veröffentlichte Artefakte oder Revisionen werden nicht
überschrieben.

### Veröffentlichung und Nachbereitung

Nach angemessener Koordination werden mindestens veröffentlicht:

- betroffene unveränderliche Identitäten;
- Art und Umfang der Sperre;
- sichere Ersatzversion, sofern vorhanden;
- erforderliche Operator-Maßnahmen;
- verbleibende Unsicherheiten;
- Zeitlinie ohne unnötige personenbezogene oder ausnutzbare Details.

Die Nachbereitung prüft, ob Build-, Review-, Schlüsselverwaltungs- oder
Monitoring-Verträge angepasst werden müssen.

## Key-Rotation und Verlust

Geplante Rotation verwendet einen neuen Key mit eigener ID und dokumentiert den
Vorgänger/Nachfolger. Der alte Key kann `RETIRED` bleiben, damit historische
Artefakte prüfbar bleiben.

Bei vermutetem Schlüsselverlust oder -kompromiss wird der Key `REVOKED` und
kann keine neue oder alte Codeausführung mehr autorisieren. Eine bloße
Veröffentlichung eines neuen Keys ohne signierte Trust-State-Revision reicht
nicht aus.

Trust-State-Checkpoints müssen rollback-geschützt persistiert werden. Wer einen
alten lokalen Checkpoint wiederherstellen kann, kann sonst auch eine alte,
formal gültige Trust-Kette erneut vorspiegeln.

## Anforderungen an Community-Beispiele

Ein unabhängiges Beispielprojekt muss:

- separat klon- und baubar sein;
- gegen veröffentlichte Plugin-API-Artefakte statt interne App-Pakete bauen;
- eine klare Lizenz und Provenienz besitzen;
- eine reproduzierbare Test- und Paketierungsanweisung enthalten;
- ein vollständiges Signatur- und Indexbeispiel liefern;
- ohne geheime Schlüssel im Repository auskommen;
- positive, negative und Kompatibilitätstests enthalten;
- die fehlende öffentliche Client-Installation nicht durch manuelles Kopieren
  als bereits implementierte Distribution ausgeben.

Für Issue #104 werden mindestens zwei Java-Beispiele mit unterschiedlichen
Capabilities sowie ein reines Regel-/Knowledge-Pack-Beispiel benötigt.

## Lokale End-to-End-Prüfung

Bis ein gehosteter Transport und atomarer Installationslebenszyklus verfügbar
sind, muss eine lokale Testumgebung mindestens folgende Kette nachweisen:

```text
reproduzierbarer Build
  -> Artefakt-Hash
  -> Publisher-Signatur
  -> signierter Artifact Index
  -> signierte Trust-State-Kette
  -> deterministische Resolution
  -> TrustedPluginRuntime-Gate
  -> Aktivierung oder fail-closed Blockierung
```

Diese lokale Kette ist wertvolle Integrations-Evidence, aber kein Ersatz für
Transport-, Download-, Installations-, Update- und Rollbacktests.

## Pflegecheckliste

Vor jeder neuen öffentlichen Index- oder Trust-State-Revision:

- [ ] unveränderliche Artefaktbytes und Version geprüft;
- [ ] Publisher-Signatur und Trust-Entscheidung erfolgreich;
- [ ] Provenienz, Lizenz und SBOM vorhanden;
- [ ] API-, Core-, Capability- und Dependency-Kompatibilität geprüft;
- [ ] reproduzierbarer Build oder begründete Abweichung dokumentiert;
- [ ] technische und fachliche Reviewentscheidung retained;
- [ ] keine widerrufene Identität erneut autorisiert;
- [ ] Indexrevision signiert und unveränderlich;
- [ ] Trust-State-Sequenz, Vorgänger und Checkpoint geprüft;
- [ ] Incident-Kontakt und Rückfallanweisung aktuell;
- [ ] Claims bleiben innerhalb der autorisierten Capability-Grenzen.

## Verwandte Verträge

- [Plugin-API](plugin-api.md)
- [Plugin Artifact Index](plugin-artifact-index.md)
- [Kryptografische Plugin-Artefaktprüfung](plugin-artifact-trust.md)
- [Authentisierte Plugin-Trust-State-Revisionen](plugin-trust-store-revisions.md)
- [Erweiterungssystem](extension-system.md)
