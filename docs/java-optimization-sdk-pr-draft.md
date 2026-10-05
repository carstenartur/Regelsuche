# Lokaler PR-Entwurf: versioniertes Java-Optimierungs-SDK für Issue 1657

Sandbox braucht einen unabhängig prüfbaren, typisierten Java-Vertrag für
mehrzeilige mathematische Cleanups. Ein unbeschränktes algebraisches Ergebnis
kann Java-Wraparound, Rundung, Exceptionpfade oder ursprüngliche Überläufe
verändern. Die bisherige modulare Verdrahtung in Experiments ist außerdem kein
eigenständig konsumierbarer SDK-Vertrag.

Dieser Branch führt `regelsuche-optimization-sdk` auf den vorhandenen
JointPlanSearch-/Plan-/Prepared-Bausteinen ein. Er bewahrt Quellauswertungsspuren,
trennt Suche/Beweis/Kosten, begrenzt Arbeit und Unterbrechung, prüft Java-
Wortarithmetik/IEEE-Identitäten sowie BigInteger-Algebra mit expliziten
Domänen-/Größenverträgen und liefert erneut prüfbare versionierte Evidence.
Checked/Guarded berücksichtigen Original und Ersatz. Vorbereitete Programme
werden an den verifizierten Plan und den vertrauenswürdigen Backend gebunden.

Modul, BOM, API-Policy, Publication-/Consumer-Closure, JUnit-Packaging-Verträge,
Coverage und Docker-Buildkontext sind integriert. Das commit-gepinnte lokale
Standalone-Paket enthält Quellen, Javadoc, Lizenzen und einen externen Consumer.
Keine öffentliche Maven-Verfügbarkeit oder Remote-Veröffentlichung ist Teil
dieses lokalen Entwurfs. Sandbox bleibt der Quelladapter und benötigt keine
SDK-Laufzeit für den erzeugten Java-Code.

Die rekonstruierte Implementierung wurde neu geprüft; historische Belege des
verlorenen Arbeitsbaums werden nicht übernommen. Aktuelle ausgeführte Gates,
numerische Grenzen, lokale Distributionsentscheidung und verbleibende
Gesamtprüfung stehen in [der Qualifikation](java-optimization-sdk-qualification.md).
Dieser Text ist ein reviewbarer Entwurf, kein erstellter oder gepushter PR.
