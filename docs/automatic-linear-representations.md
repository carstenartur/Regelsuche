# Automatische Darstellungswahl für rationale lineare Systeme

`LinearRepresentationPlanner` verbindet drei vorhandene exakte Verfahren:
skalare Elimination (`DIRECT`), die vollständige Matrix mit RREF (`MATRIX`)
und getrennte Matrizen für unabhängige Komponenten (`BLOCKS`). `AUTO` wählt
Blöcke, wenn die Quelle mehrere unabhängige Komponenten und mindestens acht
Variablen enthält; ansonsten wählt es die skalare Elimination.

Die Vorbereitung betrachtet ausschließlich die Variablenvorkommen in den
ursprünglichen Gleichungen. Sie berechnet noch keine Koeffizientenmatrix,
Ränge oder Lösungen. Algebraische Auslöschung kann dadurch eine weitere
Zerlegung verbergen. Die syntaktische Zerlegung bleibt dennoch korrekt.
Konstante Zeilen werden mitgeführt, insbesondere Widersprüche wie `0=1`.
Nach dem Lösen werden die vollständige partikuläre Lösung und die geordnete
Nullraumbasis wieder zu einer globalen Lösung zusammengesetzt.

Die API akzeptiert 1–16 Gleichungen, bis zu 16 Variablen und ein Gesamtbudget
zwischen 0 und 1.000.000 mechanischen Arbeitseinheiten. Vorbereitung,
ausgeführte Verfahren einschließlich ihrer internen Nachweisarbeit und
Komposition teilen sich dieses Budget. Auch abgebrochene Versuche behalten
ihren Arbeitsverbrauch. Parsing, Speicherverwaltung, Hashing und die
Bitkomplexität rationaler Arithmetik sind nicht Bestandteil dieser Metrik.
Aus ihr folgt keine Aussage über Laufzeit oder asymptotische Komplexität.

`audit` verwendet zusätzlich ein anderes vollständiges Verfahren: Matrix/RREF
für direkte oder blockweise Lösungen und skalare Elimination für die
Matrixroute. Seine Kosten werden getrennt ausgewiesen und müssen in einem
Gesamtvergleich mitgezählt werden. `verify` wiederholt außerdem die komplette
ursprüngliche Berechnung und bindet Auswahl, Quelle, Budget und Teilschritte.
Das ist eine algorithmische Prüfung, kein Lean-Beweis.

Der [Transfervergleich](representation-strategy-transfer.md) prüft, welchen
zusätzlichen Nutzen eine aus Trainingsläufen gelernte Auswahlbedingung hat.
