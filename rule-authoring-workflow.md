# Rule Authoring Workflow

Dieses Dokument beschreibt den ersten interaktiven End-to-End-Workflow für Rule Authoring in der Workbench.

## Ziel-Loop

Expression  
→ Position  
→ Match  
→ Rewrite  
→ New Expression

## Ablauf im UI (Tab „Rule-IDE“)

1. **Expression eingeben**  
   Trage einen Ausdruck im Feld `Ausdruck` ein, z. B. `sin(x^2+6*x+5)`, und starte die Bauminspektion.

2. **Position auswählen**  
   Die Inspektion zeigt alle gefundenen Baumpositionen.  
   Wähle die relevante Position (z. B. den quadratischen Teilbaum) aus.  
   Falls ein bestimmter Teilbaum serverseitig als aktiv markiert werden soll, kann die API optional `selectedPathKey` entgegennehmen.

3. **Match inspizieren**  
   Für die ausgewählte Position werden alle Regelmatches angezeigt inklusive:
   - `matchId`
   - `kind` / `enumeratorId`
   - `bindings`
   - `subtreeBefore`
   - `subtreeAfter`
   - `expressionAfter`

4. **Rewrite anwenden (Apply)**  
   Wähle ein anwendbares Match und klicke `Apply`.  
   Der Rewrite wird serverseitig über die stabile `matchId` angewendet und der neue Ausdruck ohne Seiten-Reload als neuer Working Expression gesetzt.

5. **Mit neuem Ausdruck weiterarbeiten**  
   Nach dem Apply wird die Inspektion auf dem neuen Ausdruck erneut geladen, sodass direkt weiter iteriert werden kann.

## Beispiel

- Start: `sin(x^2+6*x+5)`
- Position: quadratischer Teilbaum
- Match: `COMPLETE_SQUARE`
- Ergebnis nach Apply: `sin((x + 3) ^ 2 - 4)`
