package de.regelsuche.ide;

import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator.LocalCandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import java.util.List;

/** Groups local candidates into logical rule matches at one tree position. */
public interface LogicalMoveGrouper {

    List<LogicalMoveMatch> group(TreePosition position, List<LocalCandidateMove> candidates);
}
