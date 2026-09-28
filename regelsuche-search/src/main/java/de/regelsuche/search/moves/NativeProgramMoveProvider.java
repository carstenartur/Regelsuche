package de.regelsuche.search.moves;

import de.regelsuche.search.program.CompiledAstRewriteProgram;

/** Registered program object transport; independent replay uses the existing interpreter. */
public record NativeProgramMoveProvider(MoveProvider.Descriptor descriptor,CompiledAstRewriteProgram program) implements NativeMoveProvider {}
