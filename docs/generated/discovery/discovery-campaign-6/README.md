# Discovery Campaign 6 architecture note

Aktuelle Move-Familien bleiben built-in (Cancellation, Complete-Square, Repeated/Common-Subexpression), weil Move-Modell, Enumeratoren und Realizer noch eng mit AST/Parser/Canonicalizer gekoppelt sind.

Spätere Kandidaten für nachladbare Module:

- zusätzliche mathematische Rule Packs
- domänenspezifische Move-Familien
- erweiterte Enumeratoren/Realizer mit klaren Paketgrenzen

Für diese spätere Modularisierung sollte die API stabil bleiben bei:

- `ParameterEnumerator`
- `MoveRealizer`
- `MoveSearchEngine`

Es gibt bewusst keine globale statische Registry; Engine-Komposition erfolgt per Konstruktor-Injection.
