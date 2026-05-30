# Regelsuche – visual demo assets

This directory holds the documentation assets that are produced **by the
end-to-end tests themselves**, so they cannot fall out of sync with the
behaviour they describe.

| Subdirectory | Tracked in git? | Refreshed by |
| --- | --- | --- |
| `screenshots/` | yes | `./gradlew test e2eTest -Pregelsuche.recordDocs=true` |
| `videos/`     | no (CI artifact, see `.gitignore`) | same command |
| `gifs/`       | no (optional, requires `ffmpeg`) | same command |

The matching test methods live in
[`app/src/e2eTest/java/de/regelsuche/e2e/BrowserDemoFlowTest.java`](../../app/src/e2eTest/java/de/regelsuche/e2e/BrowserDemoFlowTest.java)
and
[`app/src/test/java/de/regelsuche/discovery/ParametricSophieGermainGalleryTest.java`](../../app/src/test/java/de/regelsuche/discovery/ParametricSophieGermainGalleryTest.java).
See [`docs/demo-gallery.md`](../demo-gallery.md) for a curated view that
links each screenshot to its test method and to the corresponding export
bundle.
