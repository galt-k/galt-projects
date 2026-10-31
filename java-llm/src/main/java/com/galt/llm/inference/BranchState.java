package com.galt.llm.inference;

import java.util.ArrayList;
import java.util.List;

/** Mutable state for one continuation, managed by the inference scheduler. */
public final class BranchState {
    // Selected output tokens; the newest token may not yet be in the KV cache.
    final List<Integer> outputTokenIds = new ArrayList<>();
    int currentGrammarState;
    // Absolute position where the next selected token will be fed to decodeStep.
    int nextDecodePosition;
    final int[] blockTable;
    double cumulativeLogProbability = 0.0;
    int scoredTokenCount = 0;

    /**
     * Copies the block table so this branch can change its own block mappings.
     * Physical blocks are not copied, retained, or allocated by this constructor;
     * the scheduler must manage their ownership separately.
     */
    public BranchState(int currentGrammarState, int nextDecodePosition, int[] blockTable) {
        if (currentGrammarState < 0 || nextDecodePosition < 0) {
            throw new IllegalArgumentException("Grammar state and decode position must be nonnegative");
        }
        this.currentGrammarState = currentGrammarState;
        this.nextDecodePosition = nextDecodePosition;
        this.blockTable = blockTable.clone();
    }
}
