package com.galt.llm.inference;

public class FSMlogitMask {
    private final long[][] globalMask;
    private final int vocabSize;
    private final int[][] transitions;

    public FSMlogitMask(int[][] transitions) {
        if (transitions == null || transitions.length == 0 || transitions[0] == null) {
            throw new IllegalArgumentException("Provide at least one transition row");
        }
        this.transitions = transitions;
        vocabSize = transitions[0].length;
        globalMask = new long[transitions.length][(vocabSize + 63) / 64];
        for (int state = 0; state < transitions.length; state++) {
            if (transitions[state] == null || transitions[state].length != vocabSize) {
                throw new IllegalArgumentException("Every state must have the same vocabulary size");
            }
            buildMaskForState(transitions[state], state);
        }
    }

    private void buildMaskForState(int[] transitions, int state) {
        for (int tokenId = 0; tokenId < vocabSize; tokenId++) {
            if (transitions[tokenId] != -1) {
                int wordIndex = tokenId / 64;
                int bitPosition = tokenId % 64;
                long localMask = 1L << bitPosition;
                globalMask[state][wordIndex] |= localMask;
            }
        }
    }

    boolean isTokenAllowed(int tokenId, int currentState) {
        checkState(currentState);
        if (tokenId < 0 || tokenId >= vocabSize) {
            throw new IllegalArgumentException("Token ID outside vocabulary: " + tokenId);
        }
        int wordIndex = tokenId / 64;
        int bitPosition = tokenId % 64;
        return (globalMask[currentState][wordIndex] & (1L << bitPosition)) != 0L;
    }

    void applyMaskInPlace(float[] logits, int currentState) {
        checkState(currentState);
        if (logits.length != vocabSize) {
            throw new IllegalArgumentException("Logits length must equal vocabulary size " + vocabSize);
        }
        for (int tokenId = 0; tokenId < logits.length; tokenId++) {
            if (!isTokenAllowed(tokenId, currentState)) {
                logits[tokenId] = Float.NEGATIVE_INFINITY;
            }
        }
    }

    private void checkState(int state) {
        if (state < 0 || state >= globalMask.length) {
            throw new IllegalArgumentException("Unknown state: " + state);
        }
    }

    int nextState(int currentState, int tokenId){
        checkState(currentState);
        if (tokenId < 0 || tokenId >= vocabSize) {
            throw new IllegalArgumentException("Token ID outside vocabulary: " + tokenId);
        }
        int next = transitions[currentState][tokenId];
        if (next == -1) {
            throw new IllegalArgumentException("Token " + tokenId
                    + " is illegal for state " + currentState);
        }
        return next;
    }
}
