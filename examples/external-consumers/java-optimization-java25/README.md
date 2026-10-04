# Independent Java optimization consumer

Requires Java 25. This project resolves only the published SDK closure, using
the repository supplied through `-PregelsucheRepository=/absolute/repository`.
Run `gradle check run` with `-PregelsucheVersion=<version>` matching that repository.
No currently published Maven Central coordinate is promised.

The program independently reverifies Java int wraparound, then demonstrates
that CHECKED_THROW still checks an overflow in an eliminated source operation.
The standalone qualified archive also builds this program using only its
`*-all.jar`, a fresh directory and an empty dependency cache.
