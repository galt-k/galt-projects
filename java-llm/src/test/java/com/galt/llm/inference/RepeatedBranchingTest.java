package com.galt.llm.inference;

import com.galt.llm.Tensor;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepeatedBranchingTest {
    private final EntropyBranchScheduler scheduler = new EntropyBranchScheduler();
    private final FSMlogitMask masker = new FSMlogitMask(new int[][]{
        {1,1,-1,-1,-1,-1,-1,-1},
        {2,2,-1,-1,-1,-1,-1,-1},
        {-1,-1,-1,-1,-1,-1,-1,3}
    });

    @Test
    void forksAgainOnBothContinuationsAndReleasesAllLosingLeaves() {
        PagedKVCache cache = cache(32);
        TreeModel model = new TreeModel(false);
        BranchState winner = scheduler.generateWithBranching(new int[]{4}, model,
                masker, cache, 7, 3, 0.8, 2, 4);
        assertEquals(List.of(0,0,7), winner.outputTokenIds);
        assertEquals(6, model.calls);
        assertEquals(List.of("0:0","0:1","1:0","1:1"), model.paths);
        assertEquals(Math.log(0.5)*2, winner.cumulativeLogProbability, 1e-6);
        assertEquals(3, winner.scoredTokenCount);
        assertEquals(3, winner.nextDecodePosition);
        assertEquals(31, cache.getNumFreeBlocks());
        assertEquals(1, cache.getRefCount(winner.blockTable[0]));
        scheduler.releaseBranch(winner,cache);
        assertEquals(32, cache.getNumFreeBlocks());
    }

    @Test
    void globalBudgetBoundsRepeatedSearchAndOneMeansGreedy() {
        for (int budget : new int[]{1,2,3}) {
            PagedKVCache cache = cache(32);
            TreeModel model = new TreeModel(false);
            BranchState winner = scheduler.generateWithBranching(new int[]{4},model,
                    masker,cache,7,3,0.8,2,budget);
            assertEquals(budget, model.paths.size());
            assertEquals(List.of(0,0,7),winner.outputTokenIds);
            scheduler.releaseBranch(winner,cache);
            assertEquals(32,cache.getNumFreeBlocks());
        }
    }

    @Test
    void nestedFailureAndExhaustionReleaseQueuedAndCompletedBranches() {
        for (int capacity : new int[]{2,32}) {
            PagedKVCache cache = cache(capacity);
            assertThrows(IllegalStateException.class, () -> scheduler.generateWithBranching(
                    new int[]{4},new TreeModel(capacity==32),masker,cache,7,3,0.8,2,4));
            assertEquals(capacity,cache.getNumFreeBlocks());
        }
    }

    @Test
    void budgetLimitedOutputsDoNotPerformUnnecessaryDecode() {
        PagedKVCache cache = cache(32);
        TreeModel model = new TreeModel(false);
        BranchState winner = scheduler.generateWithBranching(new int[]{4},model,
                masker,cache,7,1,0.8,2,4);
        assertEquals(List.of(0),winner.outputTokenIds);
        assertEquals(0,model.calls);
        assertEquals(1,winner.nextDecodePosition);
        scheduler.releaseBranch(winner,cache);
        assertEquals(32,cache.getNumFreeBlocks());
    }

    private static PagedKVCache cache(int capacity) {
        return new PagedKVCache(capacity,1,1,4,2);
    }

    private static class TreeModel extends CachedGPTModel {
        final boolean fail;
        final List<String> paths = new ArrayList<>();
        int calls;
        TreeModel(boolean fail) {
            super(InferenceConfig.micro(),null,null,null,null,null);
            this.fail=fail;
        }
        @Override
        public Tensor prefill(int[][] prompt,int[] table,PagedKVCache cache) {
            cache.writeKV(table[0],0,0,0,new float[]{4,0},0,new float[]{4,0},0);
            return scores();
        }
        @Override
        public Tensor decodeStep(int[][] input,int position,int[] table,PagedKVCache cache) {
            calls++;
            int token=input[0][0];
            if (position==2) {
                int first=(int)cache.getKeyData()[cache.getOffset(table[0],0,0,1)];
                paths.add(first+":"+token);
                if (fail && first==1 && token==1) throw new IllegalStateException("Nested decode failure");
            }
            cache.writeKV(table[position/4],0,0,position%4,
                    new float[]{token,0},0,new float[]{token,0},0);
            return scores();
        }
        private Tensor scores() {
            return Tensor.of(new float[]{0,0,0,0,0,99,0,0},1,1,8);
        }
    }
}
