package example;

import de.regelsuche.extension.runtime.ExtensionRuntime;
import de.regelsuche.extension.runtime.ExtensionRuntimeConfig;

/** Runs the extension API without the Regelsuche application module. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        var config = ExtensionRuntimeConfig.classpath(Main.class.getClassLoader());
        try (var runtime = ExtensionRuntime.open(config)) {
            var registration = runtime.catalog()
                .find(GreetingPlugin.POINT, "hello")
                .orElseThrow();
            System.out.println("extension=" + registration.descriptor().id());
            System.out.println("origin=" + registration.origin().sourceId());
            System.out.println("greeting=" + registration.implementation().greet("Ada"));
            System.out.println("catalog=" + runtime.catalog().contentHash());
        }
    }
}
