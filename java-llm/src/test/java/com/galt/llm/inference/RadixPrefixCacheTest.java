package com.galt.llm.inference;

import com.galt.llm.*;
import com.galt.llm.config.GPTConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadixPrefixCacheTest {

    @Test
    void testTreeMatchingAndRefCounts() {
        int blockSize = 4;
        RadixPrefixCache tree = new RadixPrefixCache(blockSize);

        // Sequence A: 8 tokens -> 2 blocks
        int[] seqA = { 10, 20, 30, 40, 50, 60, 70, 80 };
        int[] blocksA = { 0, 1 };
        tree.insertBlockSequence(seqA, blocksA);

        assertEquals(2, tree.getNumCachedBlocks());

        // Match sequence that shares the first block [10, 20, 30, 40] but diverges in block 2
        int[] seqB = { 10, 20, 30, 40, 99, 88, 77, 66 };
        RadixPrefixCache.PrefixMatchResult matchB = tree.matchPrefix(seqB);

        assertEquals(4, matchB.cachedTokenCount());
        assertArrayEquals(new int[]{ 0 }, matchB.sharedPhysicalBlocks());

        // Verify refCount incremented on block 0
        RadixPrefixCache.RadixNode root = tree.getRoot();
        RadixPrefixCache.RadixNode node0 = root.children.get(new RadixPrefixCache.BlockKey(new int[]{ 10, 20, 30, 40 }));
        assertNotNull(node0);
        assertEquals(1, node0.refCount);

        // Match exact seqA: since prompt length is 8, delta invariant keeps last block as delta
        RadixPrefixCache.PrefixMatchResult matchA = tree.matchPrefix(seqA);
        assertEquals(4, matchA.cachedTokenCount());
        assertArrayEquals(new int[]{ 0 }, matchA.sharedPhysicalBlocks());
        assertEquals(2, node0.refCount);

        // Release sequence
        tree.releaseSequence(new int[]{ 0 });
        assertEquals(1, node0.refCount);
        tree.releaseSequence(new int[]{ 0 });
        assertEquals(0, node0.refCount);
    }

    @Test
    void testLRUEviction() {
        int blockSize = 4;
        int maxBlocks = 4;
        PagedKVCache kvCache = new PagedKVCache(maxBlocks, 1, 1, blockSize, 2);
        RadixPrefixCache tree = new RadixPrefixCache(blockSize);

        int p0 = kvCache.allocateBlock();
        int p1 = kvCache.allocateBlock();
        int p2 = kvCache.allocateBlock();

        // Sequence 1: Block 0 -> Block 1
        tree.insertBlockSequence(new int[]{ 1, 2, 3, 4, 5, 6, 7, 8 }, new int[]{ p0, p1 });

        // Sequence 2: Branch off Block 0 -> Block 2
        tree.insertBlockSequence(new int[]{ 1, 2, 3, 4, 9, 10, 11, 12 }, new int[]{ p0, p2 });

        assertEquals(3, tree.getNumCachedBlocks());

        // Pin block 2 (refCount = 1)
        tree.matchPrefix(new int[]{ 1, 2, 3, 4, 9, 10, 11, 12, 13 });

        // p0 is a non-leaf (has children p1 and p2) -> CANNOT be evicted
        // p2 has refCount > 0 -> CANNOT be evicted
        // p1 is a leaf with refCount == 0 -> CAN be evicted!
        int evicted = tree.evictLRUBlock(kvCache);
        assertEquals(p1, evicted);
        assertEquals(2, tree.getNumCachedBlocks());

        // Now allocate freed block back from kvCache without exception
        int reclaimed = kvCache.allocateBlock();
        assertEquals(p1, reclaimed);

        // If we try to evict now, p0 still has child p2, and p2 is pinned (refCount=1) -> nothing eligible
        int nextEvicted = tree.evictLRUBlock(kvCache);
        assertEquals(-1, nextEvicted);

        // Release p2
        tree.releaseSequence(new int[]{ p2 });
        // Now p2 is unreferenced leaf -> should be evicted
        int evictedP2 = tree.evictLRUBlock(kvCache);
        assertEquals(p2, evictedP2);
    }

    @Test
    void testMilestone2DeltaPrefillEquivalence() {
        // Milestone 2 Ground Truth Verification:
        // Turn 1 prompt: [10, 20, 30, 40, 50, 60] (length 6)
        // Block 0 holds [10, 20, 30, 40]
        // Turn 2 prompt: [10, 20, 30, 40, 50, 60, 70, 80] (length 8)
        // Verify cachedTokenCount = 4, refCount = 2, and delta prefill produces logits matching full prefill within 1e-5f
        int vocabSize = 100;
        int contextLength = 32;
        int embDim = 4;
        int numHeads = 2;
        int headDim = 2;
        int numLayers = 1;
        int blockSize = 4;
        int maxPhysicalBlocks = 8;

        InferenceConfig infConfig = new InferenceConfig(
            vocabSize, contextLength, embDim, numHeads, numHeads, headDim, numLayers, blockSize, maxPhysicalBlocks
        );
        GPTConfig gptConfig = new GPTConfig(vocabSize, contextLength, embDim, numHeads, numLayers, false);

        // Shared Embeddings
        float[] tokData = new float[vocabSize * embDim];
        for (int i = 0; i < tokData.length; i++) {
            tokData[i] = (i % 7) * 0.1f + 0.01f;
        }
        Tensor tokEmb = Tensor.of(tokData, vocabSize, embDim);

        float[] posData = new float[contextLength * embDim];
        for (int i = 0; i < posData.length; i++) {
            posData[i] = (i % 5) * 0.05f + 0.01f;
        }
        Tensor posEmb = Tensor.of(posData, contextLength, embDim);

        // Shared Attention Weights
        float[] wData = new float[embDim * embDim];
        for (int i = 0; i < wData.length; i++) {
            wData[i] = (i % 4) * 0.1f + 0.05f;
        }
        Tensor wQ = Tensor.of(wData.clone(), embDim, embDim);
        Tensor wK = Tensor.of(wData.clone(), embDim, embDim);
        Tensor wV = Tensor.of(wData.clone(), embDim, embDim);
        Tensor outProj = Tensor.of(wData.clone(), embDim, embDim);

        // Shared FeedForward Weights
        int hiddenDim = 4 * embDim;
        float[] w1Data = new float[embDim * hiddenDim];
        java.util.Arrays.fill(w1Data, 0.02f);
        Tensor w1 = Tensor.of(w1Data, embDim, hiddenDim);

        float[] b1Data = new float[hiddenDim];
        java.util.Arrays.fill(b1Data, 0.01f);
        Tensor b1 = Tensor.of(b1Data, hiddenDim);

        float[] w2Data = new float[hiddenDim * embDim];
        java.util.Arrays.fill(w2Data, 0.02f);
        Tensor w2 = Tensor.of(w2Data, hiddenDim, embDim);

        float[] b2Data = new float[embDim];
        java.util.Arrays.fill(b2Data, 0.01f);
        Tensor b2 = Tensor.of(b2Data, embDim);

        FeedForward ff = new FeedForward(w1, b1, w2, b2);

        float[] ones = new float[embDim];
        java.util.Arrays.fill(ones, 1.0f);
        float[] zeros = new float[embDim];

        LayerNorm ln1 = new LayerNorm(Tensor.of(ones.clone(), embDim), Tensor.of(zeros.clone(), embDim));
        LayerNorm ln2 = new LayerNorm(Tensor.of(ones.clone(), embDim), Tensor.of(zeros.clone(), embDim));

        MultiHeadAttention statelessMha = new MultiHeadAttention(wQ, wK, wV, outProj, numHeads);
        TransformerBlock[] statelessBlocks = {
            new TransformerBlock(statelessMha, ff, ln1, ln2)
        };

        CachedAttention cachedAtt = new CachedAttention(wQ, wK, wV, outProj, numHeads, numHeads);
        CachedTransformerBlock[] cachedBlocks = {
            new CachedTransformerBlock(cachedAtt, ff, ln1, ln2)
        };

        LayerNorm finalNorm = new LayerNorm(Tensor.of(ones.clone(), embDim), Tensor.of(zeros.clone(), embDim));
        float[] headData = new float[embDim * vocabSize];
        for (int i = 0; i < headData.length; i++) {
            headData[i] = (i % 6) * 0.04f + 0.01f;
        }
        Tensor outHead = Tensor.of(headData, embDim, vocabSize);

        GPTModel statelessModel = new GPTModel(gptConfig, tokEmb, posEmb, statelessBlocks, finalNorm, outHead);
        CachedGPTModel cachedModel = new CachedGPTModel(infConfig, tokEmb, posEmb, cachedBlocks, finalNorm, outHead);

        PagedKVCache kvCache = new PagedKVCache(maxPhysicalBlocks, numLayers, numHeads, blockSize, headDim);
        RadixPrefixCache prefixCache = new RadixPrefixCache(blockSize);

        // Turn 1: prompt [10, 20, 30, 40, 50, 60] (length 6)
        int[] promptTurn1 = { 10, 20, 30, 40, 50, 60 };
        int block0 = kvCache.allocateBlock();
        int block1 = kvCache.allocateBlock();
        int[] blockTableTurn1 = { block0, block1 };

        cachedModel.prefill(new int[][]{ promptTurn1 }, blockTableTurn1, kvCache);

        // Commit full block [10, 20, 30, 40] to RadixPrefixCache
        prefixCache.insertBlockSequence(new int[]{ 10, 20, 30, 40 }, new int[]{ block0 });

        // Turn 2 arrives: prompt [10, 20, 30, 40, 50, 60, 70, 80] (length 8)
        int[] promptTurn2 = { 10, 20, 30, 40, 50, 60, 70, 80 };
        RadixPrefixCache.PrefixMatchResult matchResult = prefixCache.matchPrefix(promptTurn2);

        // Verify matchPrefix contract
        assertEquals(4, matchResult.cachedTokenCount());
        assertArrayEquals(new int[]{ block0 }, matchResult.sharedPhysicalBlocks());

        RadixPrefixCache.RadixNode node0 = prefixCache.getRoot().children.get(
            new RadixPrefixCache.BlockKey(new int[]{ 10, 20, 30, 40 })
        );
        assertNotNull(node0);
        // Initial insert refCount was 0, matchPrefix incremented to 1
        assertEquals(1, node0.refCount);

        // Allocate remaining physical block for Turn 2 delta tokens
        int block2 = kvCache.allocateBlock();
        int[] blockTableTurn2 = { block0, block2 };

        // Execute Rectangular Delta Prefill
        Tensor deltaLogits = cachedModel.prefillDelta(new int[][]{ promptTurn2 }, blockTableTurn2, 4, kvCache);
        assertEquals(1, deltaLogits.shape()[0]);
        assertEquals(4, deltaLogits.shape()[1]); // 4 delta tokens (pos 4, 5, 6, 7)
        assertEquals(vocabSize, deltaLogits.shape()[2]);

        // Verify against full 8-token prefill from scratch
        Tensor fullLogits = statelessModel.forward(new int[][]{ promptTurn2 });

        for (int t = 0; t < 4; t++) {
            int absPos = 4 + t;
            for (int v = 0; v < vocabSize; v++) {
                assertEquals(
                    fullLogits.get(0, absPos, v),
                    deltaLogits.get(0, t, v),
                    1e-5f,
                    "Logits mismatch at delta token " + t + " (absPos " + absPos + "), vocab " + v
                );
            }
        }
    }
}
