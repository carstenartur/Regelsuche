# Independent Java optimization consumer

Requires Java 25. This project resolves only the published SDK closure, using
the repository supplied through `-PregelsucheRepository=/absolute/repository`.
Run `gradle check run` with `-PregelsucheVersion=<version>` matching that repository.
The published Maven Central coordinate is
`io.github.carstenartur:regelsuche-optimization-sdk:0.5.3`. This Gradle project
still takes the explicit repository and version shown above; the
[Maven quickstart](../../../docs/java-sdk-quickstart.md) explains the released
default route and the separate source SDK examples.

The program independently reverifies Java int wraparound, then demonstrates
that CHECKED_THROW still checks an overflow in an eliminated source operation.
The standalone qualified archive also builds this program using only its
`*-all.jar`, a fresh directory and an empty dependency cache.
