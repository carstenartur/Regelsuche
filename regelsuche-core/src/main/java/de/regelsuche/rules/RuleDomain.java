package de.regelsuche.rules;

import de.regelsuche.transform.RewriteRule;
import java.util.List;

/**
 * Marks a package as a curated rewrite-rule domain (core, polynomial,
 * rational, ...).
 *
 * <p>Domain packages do not invent magic high-level rules; they curate
 * atomic rewrite rules from {@link de.regelsuche.transform.AstRewriteTransformationEngine}
 * into a domain-scoped bundle. This makes it possible to instantiate a
 * transformation engine that only operates on the rules of a specific area
 * (e.g. only polynomial rules) without losing the "all rules are atomic"
 * contract.</p>
 */
public interface RuleDomain {
    /** @return short name of the domain (e.g. {@code "polynomial"}). */
    String name();

    /** @return human-readable description in German. */
    String description();

    /** @return the atomic rewrite rules curated for this domain. */
    List<RewriteRule> rules();
}
