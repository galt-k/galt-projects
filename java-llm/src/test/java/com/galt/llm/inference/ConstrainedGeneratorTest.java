package com.galt.llm.inference;

import com.galt.llm.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConstrainedGeneratorTest {
    private static final int[][] GRAMMAR = {
        {1,-1,-1,-1,-1,-1,-1,-1},
        {-1,2,-1,-1,-1,-1,-1,-1},
        {-1,-1,3,3,-1,-1,-1,-1},
        {-1,-1,-1,-1,4,-1,-1,-1},
        {-1,-1,-1,-1,-1,-1,-1,5}
    };

    @Test
    void branchingOrchestrationWorksWithActualCachedModelAndReleasesLosers() {
        TrackingModel model = new TrackingModel();
        PagedKVCache cache = cache();
        EntropyBranchScheduler scheduler = new EntropyBranchScheduler();
        BranchState winner = scheduler.generateWithBranching(new int[]{1,2,3,4,5},
                model, new FSMlogitMask(GRAMMAR), cache, 7, 5, 0.0, 2);
        assertEquals(List.of(0,1,2,4,7), winner.outputTokenIds);
        assertTrue(model.tokens.contains(2));
        assertTrue(model.tokens.contains(3));
        for (int block : winner.blockTable) {
            if (block != -1) assertEquals(1, cache.getRefCount(block));
        }
        scheduler.releaseBranch(winner, cache);
        assertEquals(cache.getNumBlocks(), cache.getNumFreeBlocks());
    }

    @Test
    void generatesFixtureUsingRealPrefillAndDecodeAcrossBlocks() {
        TrackingModel model = new TrackingModel();
        PagedKVCache cache = cache();
        int[] blocks = {cache.allocateBlock(), cache.allocateBlock(), cache.allocateBlock()};
        int[] result = new ConstrainedGenerator().generate(new int[]{1,2,3,4,5,6},
                model, new FSMlogitMask(GRAMMAR), blocks, cache, 7, 5);
        assertArrayEquals(new int[]{0,1,2,4,7}, result);
        assertEquals(List.of(6,7,8,9), model.positions);
        assertEquals(List.of(0,1,2,4), model.tokens);
        assertEquals(1, model.prefills);
    }

    @Test
    void stopsAtBudgetWithoutExtraDecodeAndAllowsZeroBudget() {
        TrackingModel model = new TrackingModel();
        PagedKVCache cache = cache();
        int[] blocks = {cache.allocateBlock()};
        ConstrainedGenerator generator = new ConstrainedGenerator();
        assertArrayEquals(new int[0], generator.generate(new int[]{1}, model,
                new FSMlogitMask(GRAMMAR), blocks, cache, 7, 0));
        assertEquals(0, model.prefills);
        assertArrayEquals(new int[]{0,1}, generator.generate(new int[]{1}, model,
                new FSMlogitMask(GRAMMAR), blocks, cache, 7, 2));
        assertEquals(List.of(1), model.positions);
    }

    @Test
    void rejectsInsufficientCapacityAndDeadEndGrammar() {
        TrackingModel model = new TrackingModel();
        PagedKVCache cache = cache();
        ConstrainedGenerator generator = new ConstrainedGenerator();
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[]{1},
                model, new FSMlogitMask(GRAMMAR), new int[0], cache, 7, 5));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(new int[]{1},
                model, new FSMlogitMask(GRAMMAR), new int[4], cache, 7, 17));
        int[] blocks = {cache.allocateBlock()};
        assertThrows(IllegalStateException.class, () -> generator.generate(new int[]{1},
                model, new FSMlogitMask(new int[][]{{-1,-1,-1,-1,-1,-1,-1,-1}}),
                blocks, cache, 7, 1));
    }

    private static PagedKVCache cache() {
        return new PagedKVCache(16, 1, 1, 4, 2);
    }

    private static LayerNorm norm() {
        return new LayerNorm(Tensor.of(new float[]{1,1,1,1}, 4), Tensor.zeros(4));
    }

    private static Tensor embeddings() {
        float[] data = new float[32];
        for (int token = 0; token < 8; token++) data[token * 4] = 1;
        return Tensor.of(data, 8, 4);
    }

    private static Tensor head() {
        float[] data = new float[32];
        data[2] = 4;
        data[3] = 3;
        data[5] = 99;
        return Tensor.of(data, 4, 8);
    }

    // Records calls while executing actual attention, KV writes, and model projections.
    private static class TrackingModel extends CachedGPTModel {
        final List<Integer> positions = new ArrayList<>();
        final List<Integer> tokens = new ArrayList<>();
        int prefills;

        TrackingModel() {
            super(InferenceConfig.micro(), embeddings(), Tensor.zeros(16,4),
                new CachedTransformerBlock[]{new CachedTransformerBlock(
                    new CachedAttention(Tensor.zeros(4,4), Tensor.zeros(4,2),
                        Tensor.zeros(4,2), Tensor.zeros(4,4), 2, 1),
                    new FeedForward(Tensor.zeros(4,16), Tensor.zeros(16),
                        Tensor.zeros(16,4), Tensor.zeros(4)), norm(), norm())}, norm(), head());
        }

        @Override
        public Tensor prefill(int[][] prompt, int[] table, PagedKVCache cache) {
            prefills++;
            Tensor scores = super.prefill(prompt, table, cache);
            assertTrue(scores.get(0, prompt[0].length - 1, 5) > scores.get(0, prompt[0].length - 1, 2));
            return scores;
        }

        @Override
        public Tensor decodeStep(int[][] token, int pos, int[] table, PagedKVCache cache) {
            positions.add(pos);
            tokens.add(token[0][0]);
            return super.decodeStep(token, pos, table, cache);
        }
    }
}
