package com.galt.llm.inference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntropyBranchSchedulerTest {
    private static final double EPS = 1e-6;
    private final EntropyBranchScheduler scheduler = new EntropyBranchScheduler();

    @Test
    void recordingSelectionsAccumulatesScoreAndStateWithoutAdvancingKvPosition() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{{1, -1}, {-1, 2}});
        BranchState branch = new BranchState(0, 6, new int[]{3, 4});

        scheduler.recordSelectedToken(branch, 0, 0.5f, masker);
        scheduler.recordSelectedToken(branch, 1, 0.25f, masker);

        assertEquals(java.util.List.of(0, 1), branch.outputTokenIds);
        assertEquals(Math.log(0.5) + Math.log(0.25), branch.cumulativeLogProbability, EPS);
        assertEquals(2, branch.scoredTokenCount);
        assertEquals(2, branch.currentGrammarState);
        assertEquals(6, branch.nextDecodePosition);
        assertArrayEquals(new int[]{3, 4}, branch.blockTable);
    }

    @Test
    void invalidSelectionsLeaveBranchUnchanged() {
        FSMlogitMask masker = new FSMlogitMask(new int[][]{{1, -1}});
        BranchState branch = new BranchState(0, 6, new int[]{3, 4});
        branch.outputTokenIds.add(7);
        branch.cumulativeLogProbability = -0.3;
        branch.scoredTokenCount = 1;

        for (float probability : new float[]{0, -0.1f, 1.1f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.recordSelectedToken(branch, 0, probability, masker));
        }
        for (int token : new int[]{-1, 1, 2}) {
            assertThrows(IllegalArgumentException.class,
                    () -> scheduler.recordSelectedToken(branch, token, 0.5f, masker));
        }
        assertEquals(java.util.List.of(7), branch.outputTokenIds);
        assertEquals(-0.3, branch.cumulativeLogProbability);
        assertEquals(1, branch.scoredTokenCount);
        assertEquals(0, branch.currentGrammarState);
        assertEquals(6, branch.nextDecodePosition);
        assertArrayEquals(new int[]{3, 4}, branch.blockTable);
    }

    @Test
    void softmaxPreservesInputAndAssignsZeroToMaskedTokens() {
        float[] logits = {Float.NEGATIVE_INFINITY, 2, 2, Float.NEGATIVE_INFINITY};
        float[] original = logits.clone();
        assertArrayEquals(new float[]{0, 0.5f, 0.5f, 0}, scheduler.probabilitiesFromLogits(logits));
        assertArrayEquals(original, logits);
    }

    @Test
    void softmaxIsStableForLargeScoresAndNormalizesUnequalScores() {
        float[] probabilities = scheduler.probabilitiesFromLogits(new float[]{1000, 999});
        assertEquals(0.73105858, probabilities[0], EPS);
        assertEquals(0.26894142, probabilities[1], EPS);
        assertEquals(1.0, (double) probabilities[0] + probabilities[1], EPS);
        assertArrayEquals(probabilities,
                scheduler.probabilitiesFromLogits(new float[]{0, -1}));
        assertArrayEquals(new float[]{1, 0}, scheduler.probabilitiesFromLogits(
                new float[]{Float.MAX_VALUE, -Float.MAX_VALUE}));
    }

    @Test
    void softmaxWithOneLegalTokenIsCertain() {
        assertArrayEquals(new float[]{0, 1, 0}, scheduler.probabilitiesFromLogits(
                new float[]{Float.NEGATIVE_INFINITY, -1000, Float.NEGATIVE_INFINITY}));
    }

    @Test
    void softmaxRejectsMissingDistributionAndInvalidScores() {
        assertThrows(IllegalArgumentException.class, () -> scheduler.probabilitiesFromLogits(null));
        for (float[] logits : new float[][]{
                {}, {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY},
                {0, Float.NaN}, {0, Float.POSITIVE_INFINITY}}) {
            assertThrows(IllegalArgumentException.class, () -> scheduler.probabilitiesFromLogits(logits));
        }
    }

    @Test
    void forkCopiesStateAndRetainsOnlyOccupiedBlocksWithoutAllocating() {
        PagedKVCache cache = new PagedKVCache(4, 1, 1, 4, 2);
        int full = cache.allocateBlock();
        int tail = cache.allocateBlock();
        int unused = cache.allocateBlock();
        BranchState parent = new BranchState(2, 6, new int[]{full, tail, unused});
        parent.outputTokenIds.add(0);
        parent.outputTokenIds.add(1);
        parent.cumulativeLogProbability = -0.7;
        parent.scoredTokenCount = 2;
        int freeBefore = cache.getNumFreeBlocks();

        BranchState child = scheduler.forkBranch(parent, cache);

        assertNotSame(parent.blockTable, child.blockTable);
        assertArrayEquals(new int[]{full, tail, -1}, child.blockTable);
        assertEquals(parent.outputTokenIds, child.outputTokenIds);
        assertNotSame(parent.outputTokenIds, child.outputTokenIds);
        assertEquals(2, child.currentGrammarState);
        assertEquals(6, child.nextDecodePosition);
        assertEquals(-0.7, child.cumulativeLogProbability);
        assertEquals(2, child.scoredTokenCount);
        assertEquals(2, cache.getRefCount(full));
        assertEquals(2, cache.getRefCount(tail));
        assertEquals(1, cache.getRefCount(unused));
        assertEquals(freeBefore, cache.getNumFreeBlocks());

        child.outputTokenIds.add(3);
        child.blockTable[0] = -1;
        assertEquals(2, parent.outputTokenIds.size());
        assertEquals(full, parent.blockTable[0]);
    }

    @Test
    void failedForkRollsBackPreviouslyRetainedBlocks() {
        PagedKVCache cache = new PagedKVCache(2, 1, 1, 4, 2);
        int allocated = cache.allocateBlock();
        BranchState parent = new BranchState(0, 6, new int[]{allocated, 1});
        assertThrows(IllegalStateException.class, () -> scheduler.forkBranch(parent, cache));
        assertEquals(1, cache.getRefCount(allocated));
        assertEquals(0, cache.getRefCount(1));
        assertEquals(1, cache.getNumFreeBlocks());
    }

    @Test
    void forkHandlesEmptyAndExactlyFullPrefixesAndRejectsShortTable() {
        PagedKVCache cache = new PagedKVCache(1, 1, 1, 4, 2);
        BranchState empty = scheduler.forkBranch(new BranchState(0, 0, new int[]{-1}), cache);
        assertArrayEquals(new int[]{-1}, empty.blockTable);
        int block = cache.allocateBlock();
        scheduler.forkBranch(new BranchState(0, 4, new int[]{block}), cache);
        assertEquals(2, cache.getRefCount(block));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.forkBranch(new BranchState(0, 5, new int[]{block}), cache));
        assertEquals(2, cache.getRefCount(block));
    }

    @Test
    void certainOutcomeHasZeroEntropyAndVarentropy() {
        float[] probabilities = {1.0f, 0.0f};
        assertEquals(0.0, scheduler.entropyBits(probabilities), EPS);
        assertEquals(0.0, scheduler.varentropyBitsSquare(probabilities), EPS);
    }

    @Test
    void twoEqualOutcomesHaveOneBitAndZeroVarentropy() {
        float[] probabilities = {0.5f, 0.5f};
        assertEquals(1.0, scheduler.entropyBits(probabilities), EPS);
        assertEquals(0.0, scheduler.varentropyBitsSquare(probabilities), EPS);
    }

    @Test
    void fourEqualOutcomesHaveTwoBitsAndZeroVarentropy() {
        float[] probabilities = {0.25f, 0.25f, 0.25f, 0.25f};
        assertEquals(2.0, scheduler.entropyBits(probabilities), EPS);
        assertEquals(0.0, scheduler.varentropyBitsSquare(probabilities), EPS);
    }

    @Test
    void nearTieMatchesFixtureAndHasNonzeroVarentropy() {
        float[] probabilities = {0.52f, 0.48f};
        assertEquals(0.9988455, scheduler.entropyBits(probabilities), EPS);
        // For two outcomes, variance = p*q*(difference in surprises)^2.
        assertEquals(0.00332844, scheduler.varentropyBitsSquare(probabilities), EPS);
        assertTrue(scheduler.entropyBits(probabilities) > 0.80);
    }

    @Test
    void zeroProbabilityTokensDoNotChangeMeasurementsOrInput() {
        float[] probabilities = {0, 0, 0.52f, 0.48f, 0, 0, 0, 0};
        float[] original = probabilities.clone();
        assertEquals(scheduler.entropyBits(new float[]{0.52f, 0.48f}),
                scheduler.entropyBits(probabilities), EPS);
        assertEquals(scheduler.varentropyBitsSquare(new float[]{0.52f, 0.48f}),
                scheduler.varentropyBitsSquare(probabilities), EPS);
        assertArrayEquals(original, probabilities);
    }
}
