package com.galt.llm.inference;

import com.galt.llm.Tensor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BranchingGenerationTest {
    private final EntropyBranchScheduler scheduler = new EntropyBranchScheduler();
    // Extended fixture: both branches have a real two-choice continuation.
    private final FSMlogitMask masker = new FSMlogitMask(new int[][]{
        {1,-1,-1,-1,-1,-1,-1,-1},
        {-1,2,-1,-1,-1,-1,-1,-1},
        {-1,-1,3,4,-1,-1,-1,-1},
        {-1,-1,-1,-1,5,-1,5,-1},
        {-1,-1,-1,-1,5,-1,5,-1},
        {-1,-1,-1,-1,-1,-1,-1,6}
    });

    @Test
    void completeDecisionTrapFixtureBeatsGreedyWithVerifiedScores() {
        // Both paths have five scored output tokens. Forced tokens contribute log(1)=0.
        // Greedy path: 0.52 * 0.50; alternative path: 0.48 * 0.99.
        assertEquals(0.9988455, scheduler.entropyBits(new float[]{0.52f,0.48f}), 1e-6);
        assertTrue(scheduler.shouldBranch(new float[]{0.52f,0.48f}, 0.80));
        PagedKVCache greedyCache = new PagedKVCache(8,1,1,4,2);
        PagedKVCache searchCache = new PagedKVCache(8,1,1,4,2);
        int[] prompt = {1,2,3,4,5};
        BranchState greedy = scheduler.generateWithBranching(prompt,
                new ScriptedModel(false),masker,greedyCache,7,5,0.80,1,4);
        BranchState searched = scheduler.generateWithBranching(prompt,
                new ScriptedModel(false),masker,searchCache,7,5,0.80,2,4);
        try {
            assertEquals(List.of(0,1,2,4,7),greedy.outputTokenIds);
            assertEquals(List.of(0,1,3,4,7),searched.outputTokenIds);
            assertEquals(-1.347073647966609,greedy.cumulativeLogProbability,1e-6);
            assertEquals(-0.744019510933702,searched.cumulativeLogProbability,1e-6);
            assertEquals(5,greedy.scoredTokenCount);
            assertEquals(5,searched.scoredTokenCount);
            double greedyScore = scheduler.scoreBranch(greedy.cumulativeLogProbability,5);
            double searchedScore = scheduler.scoreBranch(searched.cumulativeLogProbability,5);
            assertEquals(-0.269414729593322,greedyScore,1e-6);
            assertEquals(-0.148803902186740,searchedScore,1e-6);
            assertTrue(searchedScore > greedyScore);
            // Winner has exactly its three occupied blocks; losing branches retain none.
            assertEquals(5,searchCache.getNumFreeBlocks());
        } finally {
            scheduler.releaseBranch(greedy,greedyCache);
            scheduler.releaseBranch(searched,searchCache);
        }
        assertEquals(8,greedyCache.getNumFreeBlocks());
        assertEquals(8,searchCache.getNumFreeBlocks());
    }

    @Test
    void nearTieExploresBothPathsAndKeepsBetterContinuation() {
        PagedKVCache cache = new PagedKVCache(8,1,1,4,2);
        ScriptedModel model = new ScriptedModel(false);
        BranchState winner = scheduler.generateWithBranching(new int[]{1,2,3,4,5},
                model,masker,cache,7,5,0.80,2);
        assertEquals(List.of(0,1,3,4,7),winner.outputTokenIds);
        assertEquals(6,winner.currentGrammarState);
        assertEquals(9,winner.nextDecodePosition);
        assertEquals((Math.log(0.48)+Math.log(0.99))/5,
                scheduler.scoreBranch(winner.cumulativeLogProbability,winner.scoredTokenCount),1e-6);
        assertEquals(3,cache.getNumBlocks()-cache.getNumFreeBlocks());
        for (int block : winner.blockTable) {
            if (block != -1) assertEquals(1,cache.getRefCount(block));
        }
        assertTrue(model.sawAllow);
        assertTrue(model.sawBlock);
        scheduler.releaseBranch(winner,cache);
        assertEquals(8,cache.getNumFreeBlocks());
    }

    @Test
    void highThresholdKeepsGreedyPath() {
        PagedKVCache cache = new PagedKVCache(8,1,1,4,2);
        ScriptedModel model = new ScriptedModel(false);
        BranchState winner = scheduler.generateWithBranching(new int[]{1,2,3,4,5},
                model,masker,cache,7,5,2.0,2);
        assertEquals(List.of(0,1,2,4,7),winner.outputTokenIds);
        assertFalse(model.sawBlock);
        scheduler.releaseBranch(winner,cache);
        assertEquals(8,cache.getNumFreeBlocks());
    }

    @Test
    void exhaustionAndDecodeFailureReleaseAllOwnedBlocks() {
        for (int capacity : new int[]{2,8}) {
            PagedKVCache cache = new PagedKVCache(capacity,1,1,4,2);
            assertThrows(IllegalStateException.class,
                () -> scheduler.generateWithBranching(new int[]{1,2,3,4,5},
                    new ScriptedModel(capacity==8),masker,cache,7,5,0.80,2));
            assertEquals(capacity,cache.getNumFreeBlocks());
        }
    }

    private static class ScriptedModel extends CachedGPTModel {
        final boolean fail;
        boolean sawAllow;
        boolean sawBlock;
        ScriptedModel(boolean fail) {
            super(InferenceConfig.micro(),null,null,null,null,null);
            this.fail = fail;
        }
        private float[] scores() {
            return new float[]{0,0,(float)Math.log(0.52),(float)Math.log(0.48),0,99,0,0};
        }
        @Override
        public Tensor prefill(int[][] prompt,int[] table,PagedKVCache cache) {
            float[] data = new float[prompt[0].length*8];
            for (int row=0;row<prompt[0].length;row++) {
                System.arraycopy(scores(),0,data,row*8,8);
                cache.writeKV(table[row/4],0,0,row%4,new float[]{row,1},0,new float[]{row,2},0);
            }
            return Tensor.of(data,1,prompt[0].length,8);
        }
        @Override
        public Tensor decodeStep(int[][] input,int pos,int[] table,PagedKVCache cache) {
            int token = input[0][0];
            if (fail && token==3) throw new IllegalStateException("Branch decode failure");
            cache.writeKV(table[pos/4],0,0,pos%4,new float[]{token,1},0,new float[]{token,2},0);
            float[] logits = scores();
            if (token==2 || token==3) {
                sawAllow |= token==2;
                sawBlock |= token==3;
                logits[4]=(float)Math.log(token==2 ? 0.5 : 0.99);
                logits[6]=(float)Math.log(token==2 ? 0.5 : 0.01);
            }
            return Tensor.of(logits,1,1,8);
        }
    }
}
