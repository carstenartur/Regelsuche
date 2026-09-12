# Generic extension runtime Java 25 consumer

This standalone Gradle project verifies the published `regelsuche-extension-runtime` artifact from an isolated Maven repository. It does not use `includeBuild`, project dependencies, the Regelsuche application, Spring, persistence or Hibernate.

`GreetingPlugin` contributes a consumer-owned `Greeting` contract through `RegelsuchePlugin.contribute(ExtensionContext)`. `Main` loads it with the headless runtime, invokes the typed contribution and prints the content-addressed catalog identity.

The JUnit test also reloads the same classpath snapshot and requires the catalog hash to remain identical.
