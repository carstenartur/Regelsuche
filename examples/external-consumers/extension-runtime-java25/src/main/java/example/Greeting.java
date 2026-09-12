package example;

/** Example extension contract owned entirely by the external consumer. */
@FunctionalInterface
public interface Greeting {
    String greet(String name);
}
