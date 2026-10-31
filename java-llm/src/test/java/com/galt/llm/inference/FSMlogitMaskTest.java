package com.galt.llm.inference;

import com.galt.llm.SamplingUtils;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FSMlogitMaskTest {
    @Test
    void generatesSchemaFixtureDespiteHighestScoringIllegalToken() {
        // Fixture C: {"act":"ALLOW"|"BLOCK"}<EOS>; state 5 is terminal.
        FSMlogitMask masker = new FSMlogitMask(new int[][]{
                {1, -1, -1, -1, -1, -1, -1, -1},
                {-1, 2, -1, -1, -1, -1, -1, -1},
                {-1, -1, 3, 3, -1, -1, -1, -1},
                {-1, -1, -1, -1, 4, -1, -1, -1},
                {-1, -1, -1, -1, -1, -1, -1, 5}
        });
        int currentState = 0;
        int eosTokenId = 7;
        int[] output = new int[5];
        int outputLength = 0;
        boolean reachedEos = false;

        for (int step = 0; step < output.length; step++) {
            // Fresh model-like scores for every selection; ALLOW beats BLOCK.
            float[] logits = {0, 0, 4, 3, 0, 99, 0, 0};
            assertEquals(5, SamplingUtils.findMaxIndex(logits));

            masker.applyMaskInPlace(logits, currentState);
            assertEquals(Float.NEGATIVE_INFINITY, logits[5]);
            int tokenId = SamplingUtils.findMaxIndex(logits);
            assertTrue(masker.isTokenAllowed(tokenId, currentState));
            output[outputLength++] = tokenId;
            currentState = masker.nextState(currentState, tokenId);
            if (tokenId == eosTokenId) {
                reachedEos = true;
                break;
            }
        }

        assertTrue(reachedEos, "Generation must reach EOS within the token budget");
        assertEquals(5, currentState);
        assertArrayEquals(new int[]{0, 1, 2, 4, 7}, Arrays.copyOf(output, outputLength));
    }

    @Test
    void checksAllowedTokensAcrossWordBoundaries() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{transitions()});

        for (int tokenId = 0; tokenId < 130; tokenId++) {
            assertEquals(isAllowed(tokenId), masker.isTokenAllowed(tokenId, 0),
                    "Token " + tokenId);
        }
    }

    @Test
    void preservesLegalScoresAndMasksAllIllegalScores() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{transitions()});
        float[] logits = new float[130];
        for (int tokenId = 0; tokenId < logits.length; tokenId++) {
            logits[tokenId] = tokenId + 0.5f;
        }
        logits[5] = 99.0f;

        masker.applyMaskInPlace(logits, 0);

        for (int tokenId = 0; tokenId < logits.length; tokenId++) {
            float expected = isAllowed(tokenId)
                    ? tokenId + 0.5f : Float.NEGATIVE_INFINITY;
            assertEquals(expected, logits[tokenId], "Token " + tokenId);
        }
    }

    @Test
    void rejectsWrongLogitLengthBeforeChangingScores() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{transitions()});
        for (int length : new int[]{129, 131}) {
            float[] logits = new float[length];
            Arrays.fill(logits, 7.0f);
            float[] original = logits.clone();

            assertThrows(IllegalArgumentException.class,
                    () -> masker.applyMaskInPlace(logits, 0));
            assertArrayEquals(original, logits);
        }
    }

    @Test
    void selectsIndependentMasksForEachState() {
        int[] first = transitions();
        int[] second = new int[130];
        Arrays.fill(second, -1);
        second[64] = 0;
        FSMlogitMask masker = new FSMlogitMask(new int[][]{first, second});

        for (int state : new int[]{0, 1, 0}) {
            float[] logits = new float[130];
            Arrays.fill(logits, 8.0f);
            masker.applyMaskInPlace(logits, state);
            for (int token = 0; token < 130; token++) {
                boolean allowed = state == 0 ? isAllowed(token) : token == 64;
                assertEquals(allowed, masker.isTokenAllowed(token, state));
                assertEquals(allowed ? 8.0f : Float.NEGATIVE_INFINITY, logits[token]);
            }
        }
    }

    @Test
    void rejectsInvalidStatesTokensAndUnequalRows() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{transitions()});
        assertThrows(IllegalArgumentException.class, () -> masker.isTokenAllowed(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> masker.isTokenAllowed(130, 0));
        assertThrows(IllegalArgumentException.class, () -> masker.applyMaskInPlace(new float[130], -1));
        assertThrows(IllegalArgumentException.class, () -> masker.applyMaskInPlace(new float[130], 1));
        assertThrows(IllegalArgumentException.class,
                () -> new FSMlogitMask(new int[][]{new int[8], new int[7]}));
    }

    @Test
    void followsTableIncludingLoopsSkipsAndTerminalState() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{
                {0, 2, -1},
                {-1, -1, 0},
                {-1, -1, 3}
        });
        assertEquals(0, masker.nextState(0, 0));
        assertEquals(2, masker.nextState(0, 1));
        assertEquals(0, masker.nextState(1, 2));
        assertEquals(3, masker.nextState(2, 2));
        assertThrows(IllegalArgumentException.class, () -> masker.nextState(0, 2));
    }

    @Test
    void validatesStateAndTokenBeforeTransitionLookup() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{transitions()});
        assertThrows(IllegalArgumentException.class, () -> masker.nextState(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> masker.nextState(1, 2));
        assertThrows(IllegalArgumentException.class, () -> masker.nextState(0, -1));
        assertThrows(IllegalArgumentException.class, () -> masker.nextState(0, 130));
    }

    private static int[] transitions() {
        int[] transitions = new int[130];
        Arrays.fill(transitions, -1);
        for (int tokenId : new int[]{2, 3, 63, 65, 129}) {
            transitions[tokenId] = 0;
        }
        return transitions;
    }

    private static boolean isAllowed(int tokenId) {
        return tokenId == 2 || tokenId == 3 || tokenId == 63
                || tokenId == 65 || tokenId == 129;
    }
}
