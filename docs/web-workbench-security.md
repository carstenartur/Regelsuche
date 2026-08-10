# Web-Workbench – Sicherheits-Optionen

Standardmäßig läuft die eingebettete Web-Workbench (`./gradlew run --args="serve"`) als reines lokales Tool: HTTP, keine Authentifizierung. Für ernsthafte Nutzung können zusätzliche Sicherheitsoptionen aktiviert werden – ohne Spring Boot oder eine zusätzliche Web-Framework-Abhängigkeit.

## HTTP Basic-Authentifizierung

```bash
./gradlew run --args="serve --user admin --password s3cret"
```

Optional kann ein eigener Realm gesetzt werden: `--realm "Mein-Realm"`. Die Zugangsdaten werden mit einer konstantzeitigen Vergleichsroutine geprüft, um triviales Timing-Leak zu vermeiden.

## TLS (HTTPS)

```bash
./gradlew run --args="serve --keystore /pfad/keystore.p12 --keystore-password geheim"
```

Standard-Keystore-Typ ist `PKCS12`. Andere Typen können mit `--keystore-type JKS` angegeben werden. Sobald ein Keystore angegeben ist, läuft die Workbench unter `https://`.

## Body-Limit

Das Limit für JSON-POST-Bodies liegt standardmäßig bei 1 MiB und gilt einheitlich für alle dokumentierten Workbench- und Regelradar-Endpunkte. Ein zu großer gültiger `Content-Length`-Header wird sofort abgewiesen; maßgeblich bleibt jedoch der bytezählende Eingabestream, sodass auch chunked Requests, fehlende Header und falsch zu kleine Längenangaben das Limit nicht umgehen. Der Body wird nicht zunächst vollständig als `byte[]` und anschließend noch einmal als `String` materialisiert. Ein Body mit exakt der konfigurierten Größe ist zulässig; ein weiteres Byte führt zu HTTP 413 und dem stabilen JSON-Vertrag:

```json
{"error":true,"code":"PAYLOAD_TOO_LARGE","message":"request body exceeds configured limit","limitBytes":1048576}
```

Die Antwort verwendet `Content-Type: application/json; charset=utf-8` und `Cache-Control: no-store`. Das Limit lässt sich beispielsweise mit `--max-request-bytes 2097152` anpassen.

Untrusted JSON wird anschließend tokenbasiert und strikt verarbeitet. Doppelte
Objektschlüssel, weitere JSON-Dokumente nach dem ersten Objekt, ungültiges UTF-8,
übermäßige Verschachtelung und falsche skalare Feldtypen führen fail-closed zu
HTTP 400. Route-spezifische Felder werden direkt in typisierte Request-Records
dekodiert; unbekannte Felder werden ohne Aufbau eines vollständigen Objektbaums
übersprungen. Details und reproduzierbare Testgrenzen stehen unter
[Streaming-JSON-Request-Bodies](streaming-json-request-bodies.md).

## Kombination

Auth und TLS lassen sich beliebig kombinieren – für produktionsähnliche Deployments **sollten** beide aktiviert sein. Beispiel:

```bash
./gradlew run --args="serve \
  --host 0.0.0.0 --port 8443 \
  --user admin --password $REGELSUCHE_PASSWORD \
  --keystore /etc/regelsuche/keystore.p12 \
  --keystore-password $REGELSUCHE_KEYSTORE_PASSWORD"
```

## Was bewusst nicht enthalten ist

* Kein eingebauter OAuth2-/OIDC-Stack: für SSO-Integration muss vor die Workbench ein Reverse-Proxy (z. B. nginx, Caddy) gesetzt werden.
* Keine Rate-Limits / Brute-Force-Sperren: dafür ist ebenfalls ein Reverse-Proxy oder ein Web-Application-Firewall vorgesehen.
* Kein eingebauter CSRF-Schutz – die API ist zustandslos und arbeitet ausschließlich mit JSON; Browser-UI lädt zusätzliche Token nicht.
