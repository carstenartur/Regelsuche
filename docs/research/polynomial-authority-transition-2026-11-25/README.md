# Ablösung der doppelten Polynomläufe am 25. November 2026

Im CI-Lauf 45548488424 verglich `PolynomialParityQualificationTest` vier unabhängig erzeugte Archive. Host und Maven untersuchten denselben Commit `37b5d4d55bdff4352b9385d8e6c1f65df5bcd6c3` mit dem Git-Baum `783d2a93aa4ca89699dea3585b5bba1c1bf2f0ad`. Die Prüfung umfasste die vollständigen Rohdaten beider Studien, die registrierte semantische Projektion und vier Gegenproben mit neu berechneten äußeren Prüfsummen. [PR 1388](https://github.com/carstenartur/Regelsuche/pull/1388) · [CI-Lauf](https://github.com/carstenartur/Regelsuche/actions/runs/45548488424)

| Studie | Geprüfte Ergebnisse | Eingefrorene Modelle | SHA-256 der identischen semantischen Projektion |
| --- | ---: | ---: | --- |
| Typed | 729 Zeilen | 5 | `2fa74d011bb40ac79c71266d3b7f5240eccff4a8af8657cd499d177787390186` |
| Native | 675 Zeilen in 9 Sequenzen | 3 | `d977a124522a1572b8cae5e6fa471eb226f288a5f77409cf43d58b5da35aa228` |

Die Prüfer wiesen ein verändertes Typed-Modell, ein gekipptes Typed-Urteil, ein verändertes Native-Modell und veränderte Runtime-Bytes zurück. Die beigefügte `qualification.json` ist die unveränderte Datei aus Artefakt 14994962876. Ihr SHA-256 lautet `4728bb40e64fb4a07c80021621d59b9d6c650d99c87602840f6d37afaf5072db`. Laufzeiten und Prozessmesswerte bleiben umgebungsabhängig. [Originalbeleg](qualification.json) · [CI-Artefakt](https://github.com/carstenartur/Regelsuche/actions/runs/45548488424/artifacts/14994962876)

## Laufende Prüfung

Die Maven-Studien erzeugen bei jedem regulären Lauf neue Archive. Der bestehende Check **Typed source-only learning comparison** übernimmt beide Artefakte aus demselben Workflow-Lauf am auslösenden Commit. Er prüft die SHA-256-Werte der übertragenen Tar-Archive und den Abstammungsbeleg des Typed-Protokolls. Ein Netzwerk-isolierter Python-Prozess prüft die Rohdaten beider Archive einschließlich der Typed-Ableitungen und der Native-Runtime sowie aller vier Gegenproben. Die Quellkennung, der Git-Baum, die Archiv-, Manifest- und Modellprüfsummen sowie die geprüften Ergebniszahlen stehen im Artefakt `polynomial-archive-qualification-<commit>`. Der bisherige vollständige Regressionstest der Python-Controller einschließlich des Gradle-Classpath-Versuchs läuft weiterhin.

Für eine erneute Gegenüberstellung der unabhängigen Host- und Maven-Archive bleibt `external_polynomial_comparison.qualify_parity` verfügbar. Die feste Paritätsaussage oben gilt für den angegebenen Commit.
