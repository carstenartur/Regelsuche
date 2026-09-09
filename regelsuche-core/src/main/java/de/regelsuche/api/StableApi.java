package de.regelsuche.api;

/** Public contract governed by the documented API revision and deprecation policy. */
@java.lang.annotation.Documented
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
@java.lang.annotation.Target({java.lang.annotation.ElementType.PACKAGE, java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.CONSTRUCTOR})
public @interface StableApi {
    /** API revision in which this lifecycle designation started. */
    String since();
}
