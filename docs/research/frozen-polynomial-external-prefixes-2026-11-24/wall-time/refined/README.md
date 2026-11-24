# Verfeinerte Faktoren unter derselben Kaltstartfrist

Der zusätzliche Arm verwendete dieselben 19 Quellvorkommen, zwei
Wiederholungen, die 10-Sekunden-Frist, den JSON-Transport und die
exakte Produktprüfung aus dem [ursprünglichen Kaltstartvergleich](../README.md).
Im unveränderten Java-Worker wurde ausschließlich die Engine-Fabrik durch
`NativeUnivariateFactorizationEngine.rationalsWithRefinement(policy)`
ersetzt. [Versuchsvertrag mit Quell- und Datenbindungen](protocol.json)

Regelsuche schloss **38 von 38 Anfragen** ab und lieferte 30 nichttriviale
exakte Produkte. In allen 38 Anfragen stimmen die Faktorgrade
einschließlich Vielfachheiten mit der [Original-Bridge](../../raw.jsonl)
überein oder beide Verfahren besitzen keine nichttriviale Ausgabe.
Für `x^10-1` sind es nun die Grade vier, vier, eins und eins. Für
`x^8-17*x^4+16` sind es zwei, zwei, eins, eins, eins und eins.

Der Median betrug 1,027 s und die Summe aller gemessenen Anfragezeiten
38,22 s. Dieser Arm lief separat auf derselben logischen CPU. Seine Zeiten
sind eine ergänzende Beobachtung. Die frühere gepaarte Messung bleibt
mit ihren ursprünglichen Ergebnissen erhalten. [Beleg](receipt.json) ·
[38 Einzelbeobachtungen](observations.jsonl)

## Exakte Verfeinerung

Der Modus untersucht zusammengesetzte Faktoren und verwendet denselben
rationalen Faktorpfad für strikt kleinere Grade. Jeder neue Teilvorschlag
durchläuft den regulären exakten Produktprüfer. Der Modus überträgt die
Vielfachheiten und den jeweiligen Vorfaktor auf das gesamte Produkt. Er
behält einen vorhandenen Faktor bei, wenn kein geprüfter kleinerer
Teilschritt gelingt. Auch ein knapper Arbeitsrahmen lässt den bereits
erreichbaren Teilschritt bestehen.

Alle Versuche und Prüfungen werden gemeinsam begrenzt. Der Modus reserviert
die abschließende Prüfung am Originalpolynom. Die Summe der
Vielfachheiten kann den ursprünglichen Grad nicht überschreiten. Die
Potenzierung eines Faktors beansprucht im vorhandenen Prüfer höchstens eine
Multiplikation je Vielfachheit. Einschließlich Zusammenfügen und Vergleich
genügt daher die Reserve aus zweimal dem Ursprungsgrad plus eins.
Die ausgegebenen Faktoren werden als exakt geprüftes Produkt ausgewiesen.
Die Verfeinerung erhebt keinen Irreduzibilitätsanspruch.

## Reproduktion

Aus dem [vorherigen Versuch](../README.md#reproduktion) wird derselbe
Classpath mit Java 25 verwendet. Der folgende Aufruf kompiliert daraus
den gebundenen Worker mit der neuen Engine-Fabrik und misst einen neuen
Kaltstartlauf.

```sh
area=docs/research/frozen-polynomial-external-prefixes-2026-11-24/wall-time/refined
output=$(mktemp -d)
taskset -c 2 python3 "$area/run.py" --java "$(command -v java)" \
  --classpath "$classpath" --output "$output"
```

Die fokussierten Maven-Tests prüfen die Vielfachheiten, den Vorfaktor,
die Erhaltung bei knappem Budget und die bytegleiche Reproduktion des
ursprünglichen öffentlichen Faktorisierungsvergleichs. Die externe
strenge Arbeitsrechnung bleibt weiter offen.
