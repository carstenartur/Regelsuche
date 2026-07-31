/**
 * Typed, inspectable rewrite programs for composing Regelsuche transformation
 * engines with ordinary Java language features.
 *
 * <p>The package is the semantic layer between rule/move engines and search
 * strategies. Programs are immutable data, {@link
 * de.regelsuche.search.program.RewriteProgramInterpreter} executes them, and
 * {@link de.regelsuche.search.program.ProgrammedTransformationEngine} exposes
 * their candidates through the existing transformation-engine contract.</p>
 */
package de.regelsuche.search.program;
