# Mehrere occurrence-gebundene Übergänge pro Studienzeile

Die Wiederverwendungsfälle der Polynomtheorie-Nutzenstudie enthalten bis zu
vier identische Auftreten. Ein Ausführungsresultat muss deshalb eine geordnete
Liste occurrence-gebundener Übergänge bewahren können. Ein einzelnes
`generatedExpression`-/`transformationId`-Paar reicht für Cache-Hit-,
Replay- und Amortisationsmessungen nicht aus.

Dieser Vertrag wird vor der Anbindung des nativen, Cache-, Quartik- und
SymPy-Adapters umgesetzt. Die versiegelte Qualifikation bleibt dabei
geschlossen.
