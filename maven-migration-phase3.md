# Maven migration phase 3: complete product reactor

This tranche completes the ordinary Java application reactor without changing the existing dependency versions or mathematical behavior.

The default `product-reactor` profile is active automatically and adds `regelsuche-persistence-hibernate`, `regelsuche-autopilot`, `regelsuche-release`, `regelsuche-benchmarks` and `app`.

Together with the earlier phases, a plain checkout compiles the complete production application and runs every standard JUnit test with:

```bash
mvn --batch-mode --no-transfer-progress test
```

The application package is produced without Gradle:

```bash
mvn --batch-mode --no-transfer-progress -DreleaseVersion=0.2.0 package
```

This creates the application JAR plus checkout-owned ZIP and TAR distributions under `app/target/`. The protected transitional CI still runs the previous Gradle contract first, then executes the complete Maven product reactor. The next tranche moves browser, database, solver and reproduction tests to Maven Failsafe/Testcontainers and switches the authoritative workflow from Gradle to Maven.
