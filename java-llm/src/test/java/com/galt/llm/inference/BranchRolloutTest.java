package com.galt.llm.inference;

import com.galt.llm.Tensor;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BranchRolloutTest {
    private final EntropyBranchScheduler scheduler = new EntropyBranchScheduler();
    private final FSMlogitMask masker = new FSMlogitMask(new int[][]{
        {1,-1,-1,-1,-1,-1,-1,-1},
        {-1,2,-1,-1,-1,-1,-1,-1},
        {-1,-1,3,3,-1,-1,-1,-1},
        {-1,-1,-1,-1,4,-1,-1,-1},
        {-1,-1,-1,-1,-1,-1,-1,5}
    });

    @Test
    void rollsOutToEosWithCorrectPositionsAndPrivateWrites() {
        PagedKVCache cache = new PagedKVCache(5,1,1,4,2);
        int full = cache.allocateBlock();
        int tail = cache.allocateBlock();
        BranchState parent = new BranchState(0,6,new int[]{full,tail,-1,-1});
        scheduler.recordSelectedToken(parent,0,1,masker);
        BranchState child = scheduler.forkBranch(parent,cache);
        RecordingModel model = new RecordingModel(false);

        scheduler.rolloutBranch(child,model,masker,cache,7,10);

        assertEquals(List.of(0,1,2,4,7), child.outputTokenIds);
        assertEquals(List.of(6,7,8,9),model.positions);
        assertEquals(List.of(0,1,2,4),model.tokens);
        assertEquals(10,child.nextDecodePosition);
        assertEquals(5,child.currentGrammarState);
        assertEquals(5,child.scoredTokenCount);
        assertEquals(Math.log(1.0/(1.0+Math.exp(-1))), child.cumulativeLogProbability,1e-6);
        assertEquals(List.of(0),parent.outputTokenIds);
        assertEquals(6,parent.nextDecodePosition);
        assertNotEquals(tail,child.blockTable[1]);
        assertEquals(0,cache.getKeyData()[cache.getOffset(tail,0,0,2)]);
        assertEquals(2,cache.getRefCount(full));
        assertEquals(1,cache.getRefCount(tail));
        assertNotEquals(-1,child.blockTable[2]);
        int calls = model.positions.size();
        scheduler.rolloutBranch(child,model,masker,cache,7,10);
        assertEquals(calls,model.positions.size());
    }

    @Test
    void stopsAtOutputLimitWithoutDecodingLastSelection() {
        PagedKVCache cache = new PagedKVCache(2,1,1,4,2);
        BranchState branch = new BranchState(0,1,new int[]{cache.allocateBlock()});
        scheduler.recordSelectedToken(branch,0,1,masker);
        RecordingModel model = new RecordingModel(false);
        scheduler.rolloutBranch(branch,model,masker,cache,7,2);
        assertEquals(List.of(0,1),branch.outputTokenIds);
        assertEquals(List.of(1),model.positions);
        assertEquals(2,branch.nextDecodePosition);
    }

    @Test
    void failedDecodeDoesNotAdvancePositionOrRecordAnotherToken() {
        PagedKVCache cache = new PagedKVCache(1,1,1,4,2);
        BranchState branch = new BranchState(0,1,new int[]{cache.allocateBlock()});
        scheduler.recordSelectedToken(branch,0,1,masker);
        assertThrows(IllegalStateException.class,
            () -> scheduler.rolloutBranch(branch,new RecordingModel(true),masker,cache,7,5));
        assertEquals(1,branch.nextDecodePosition);
        assertEquals(List.of(0),branch.outputTokenIds);
        assertEquals(1,branch.scoredTokenCount);
    }

    // Controlled model boundary: verifies orchestration, KV writes and adversarial scores.
    private static class RecordingModel extends CachedGPTModel {
        final List<Integer> positions = new ArrayList<>();
        final List<Integer> tokens = new ArrayList<>();
        final boolean fail;
        RecordingModel(boolean fail) {
            super(InferenceConfig.micro(),null,null,null,null,null);
            this.fail = fail;
        }
        @Override
        public Tensor decodeStep(int[][] input,int position,int[] table,PagedKVCache cache) {
            if (fail) throw new IllegalStateException("Decode failed");
            positions.add(position);
            tokens.add(input[0][0]);
            cache.writeKV(table[position/4],0,0,position%4,
                new float[]{99,98},0,new float[]{97,96},0);
            return Tensor.of(new float[]{0,0,4,3,0,99,0,0},1,1,8);
        }
    }
}
