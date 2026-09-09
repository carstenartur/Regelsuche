package de.regelsuche.api;

/** Experimental contract; changes require migration notes but may be incompatible. */
@java.lang.annotation.Documented
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
@java.lang.annotation.Target({java.lang.annotation.ElementType.PACKAGE, java.lang.annotation.ElementType.TYPE,
    java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.CONSTRUCTOR})
public @interface IncubatingApi {
    /** API revision in which this lifecycle designation started. */
    String since();
}
