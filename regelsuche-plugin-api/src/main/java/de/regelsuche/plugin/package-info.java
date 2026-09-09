/**
 * Public plugin extension contracts; only classes in regelsuche-plugin-api are
 * supported SDK API. The application owns loading, trust and runtime wiring.
 * Registries are mutable and not thread-safe; instances belong to one host.
 * Duplicate IDs fail. Required objects are non-null. Lists returned by registries
 * are snapshots. Registration is expected O(1), enumeration is O(n); mathematical
 * callbacks declare their own work and deterministic behavior.
 */
package de.regelsuche.plugin;
