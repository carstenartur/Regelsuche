# Frozen evaluator and model

The first public commit containing this file fixes the solver, the four-arm evaluator and the seed for the prospective Horner study. Use that commit's full SHA as `horner.freeze`. The [protocol](https://github.com/carstenartur/Regelsuche/commit/9724a48b0b8c4c1d02a91f8bd81f9512ed0c8119) was published first.

| Input | SHA-256 of the file bytes |
|---|---|
| [Protocol](protocol.md) | `f2c2f07b6709b2bab033721259a39f9d2b3a8bb289dbcb4bfe245f83e3208dda` |
| [Training and validation receipt](../prospective-distributive-coverage/freeze.json) | `6863b403ed13292dd4aefde8603ba83b1f7ff221ee5dab091a7f019108947930` |
| [Canonical model](../prospective-distributive-coverage/snapshot.json) | `9e56f3640b85520b8b4090f64aa96c14bb703d3d218ab6fd60c2cdb47e74e223` |

A fresh Java 25 Maven run reproduced the receipt and snapshot byte for byte before the freeze. The seven schemata cost 9,130 logical work units to acquire, including the five training and validation inputs. The three model arms each debit that cost on their own clock. The implementation, model and receipt were fixed before the FINAL generator was run.
