# Regelsuche Discovery SDK

Dieses Modul ist die kleine, headless nutzbare Java-25-Schnittstelle für eigene
begrenzte mathematische Discovery-Domänen. Es baut auf dem kanonischen
`DiscoveryDomain<State, Candidate, Certificate>`-Vertrag auf, entfernt aber den
größten Teil des Verdrahtungsboilerplates.

## Wichtigste Typen

- `DiscoveryDomainBuilder` — Domäne aus benannten Java-Funktionen aufbauen;
- `RegelsucheDiscovery` — einen synchronen, budgetierten Lauf starten;
- `DiscoveryRun` — Ergebnis, Gegenbeispiele, Arbeitsbilanz und kanonische
  Evidence lesen;
- `DiscoveryDomainProvider` — externe Domänen über `ServiceLoader` anbieten;
- `DiscoveryDomainCatalog` — Provider fehlersicher laden und doppelte
  Domänenrevisionen ablehnen;
- `DiscoveryBudgets` — transparente Startbudgets, keine versteckten Grenzen.

Das SDK vereinfacht keine mathematischen Aussagen: Ein Kandidat wird nur
`CONFIRMED`, wenn der explizit bereitgestellte Evaluator ein Zertifikat liefert.
Ein leerer Gegenbeispielfund ist weiterhin kein Beweis.

## Externer Referenzverbraucher

Das Verzeichnis

```text
examples/external-consumers/geometric-sequence-domain-java25
```

ist ein eigenständiges Gradle-Projekt. Die CI veröffentlicht SDK und
Abhängigkeiten zunächst in ein isoliertes Maven-Repository, kopiert das Beispiel
aus dem Regelsuche-Checkout heraus und baut es dort ohne Projekt-Substitution.
Der Lauf prüft `CONFIRMED`, `REFUTED`, `BUDGET_EXHAUSTED`, ServiceLoader und das
Fehlen von `app`, Spring, Hibernate und Persistenz im Runtime-Classpath.

Die ausführliche Einführung steht in
[`docs/java-discovery-sdk.md`](../docs/java-discovery-sdk.md).
