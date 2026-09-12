package example;

import de.regelsuche.extension.ExtensionApi;
import de.regelsuche.extension.ExtensionContext;
import de.regelsuche.extension.ExtensionDescriptor;
import de.regelsuche.extension.ExtensionPoint;
import de.regelsuche.extension.PluginDescriptor;
import de.regelsuche.extension.RegelsuchePlugin;
import java.util.List;
import java.util.Set;

/** Small external classpath plugin proving generic typed contribution. */
public final class GreetingPlugin implements RegelsuchePlugin {
    public static final ExtensionPoint<Greeting> POINT =
        ExtensionPoint.of("example.greeting", Greeting.class);

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
            "greeting-plugin",
            "Greeting plugin",
            "1",
            ExtensionApi.VERSION,
            ExtensionApi.CORE_COMPATIBILITY_VERSION,
            Set.of("greeting"),
            List.of(),
            "external-consumer"
        );
    }

    @Override
    public void contribute(ExtensionContext context) {
        context.contribute(
            POINT,
            ExtensionDescriptor.named("hello"),
            name -> "Hello " + name
        );
    }
}
