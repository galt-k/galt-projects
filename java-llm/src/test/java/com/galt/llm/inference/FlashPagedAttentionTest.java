package com.galt.llm.inference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlashPagedAttentionTest {

    // Ground Truth Fixture B from AGENTIC_INFERENCE_ENGINE_SPEC.md:
    // Shared KV Head 0 (N_KV_HEADS = 1, HEAD_DIM = 2) for a 6-token sequence (pos 0..5)
    // Physical Block 0 holds pos 0..3; Physical Block 1 holds pos 4..5
    private static final float[][] K_SEQ_6X2 = {
        { 1.0f,  0.0f}, // pos 0 (Block 0, Offset 0)
        { 0.0f,  1.0f}, // pos 1 (Block 0, Offset 1)
        { 1.0f,  1.0f}, // pos 2 (Block 0, Offset 2)
        {-1.0f,  0.5f}, // pos 3 (Block 0, Offset 3) -- Block 0 Full!
        { 0.5f, -0.5f}, // pos 4 (Block 1, Offset 0)
        { 1.5f,  0.5f}  // pos 5 (Block 1, Offset 1)
    };

    private static final float[][] V_SEQ_6X2 = {
        { 1.0f,  2.0f}, // pos 0
        { 3.0f,  4.0f}, // pos 1
        { 5.0f,  6.0f}, // pos 2
        { 7.0f,  8.0f}, // pos 3
        { 9.0f, 10.0f}, // pos 4
        {11.0f, 12.0f}  // pos 5
    };

    // Decode Query at pos = 5: q_5 = [1.0f, 0.5f]
    private static final float[] Q_POS_5 = { 1.0f, 0.5f };

    /**
     * Reference standard two-pass softmax attention over pos 0..5.
     */
    private float[] computeStandardAttentionReference() {
        int headDim = 2;
        float scale = 1.0f / (float) Math.sqrt(headDim);
        int T = 6;
        float[] scores = new float[T];
        float maxScore = Float.NEGATIVE_INFINITY;

        // Pass 1: compute scores & find max
        for (int j = 0; j < T; j++) {
            float dot = Q_POS_5[0] * K_SEQ_6X2[j][0] + Q_POS_5[1] * K_SEQ_6X2[j][1];
            scores[j] = dot * scale;
            if (scores[j] > maxScore) {
                maxScore = scores[j];
            }
        }

        // Pass 2: compute softmax sum
        float sumExp = 0.0f;
        for (int j = 0; j < T; j++) {
            sumExp += (float) Math.exp(scores[j] - maxScore);
        }

        // Pass 3: weight and accumulate context vector
        float[] expected = new float[headDim];
        for (int j = 0; j < T; j++) {
            float weight = (float) Math.exp(scores[j] - maxScore) / sumExp;
            for (int d = 0; d < headDim; d++) {
                expected[d] += weight * V_SEQ_6X2[j][d];
            }
        }
        return expected;
    }

    @Test
    void testFlashPagedAttentionMatchesTwoPassStandardSoftmax() {
        int numBlocks = 4;
        int numLayers = 1;
        int numKvHeads = 1;
        int numQHeads = 2; // GQA 2:1 ratio
        int blockSize = 4;
        int headDim = 2;

        PagedKVCache cache = new PagedKVCache(numBlocks, numLayers, numKvHeads, blockSize, headDim);

        int block0 = cache.allocateBlock(); // ID 0
        int block1 = cache.allocateBlock(); // ID 1
        int[] blockTable = { block0, block1 };

        // Populate pos 0..3 into Block 0
        for (int pos = 0; pos < 4; pos++) {
            int slot = pos % blockSize;
            cache.writeKV(block0, 0, 0, slot, K_SEQ_6X2[pos], 0, V_SEQ_6X2[pos], 0);
        }

        // Populate pos 4..5 into Block 1
        for (int pos = 4; pos < 6; pos++) {
            int slot = pos % blockSize;
            cache.writeKV(block1, 0, 0, slot, K_SEQ_6X2[pos], 0, V_SEQ_6X2[pos], 0);
        }

        // Output destination buffer
        float[] out = new float[headDim];

        // Execute singleQueryAttention for qHead = 0 at pos = 5
        FlashPagedAttention.singleQueryAttention(
            Q_POS_5, 0,
            blockTable,
            5,
            cache,
            0,
            0,
            numQHeads,
            out, 0
        );

        float[] reference = computeStandardAttentionReference();

        // 1. Must match reference standard attention within 1e-6f IEEE 754 precision
        assertEquals(reference[0], out[0], 1e-6f, "Context dim 0 must match two-pass reference");
        assertEquals(reference[1], out[1], 1e-6f, "Context dim 1 must match two-pass reference");

        // 2. Numerical values: exactly 6.3544917f and 7.3544917f
        assertEquals(6.3544917f, out[0], 1e-5f);
        assertEquals(7.3544917f, out[1], 1e-5f);

        // 3. Since V_SEQ[:, 1] = V_SEQ[:, 0] + 1.0, output must maintain exact dimension shift:
        assertEquals(out[0] + 1.0f, out[1], 1e-6f, "Context vector dimension shift invariant");
    }

    @Test
    void testGQAQueryHeadsShareKVHead() {
        int numBlocks = 4;
        int numLayers = 1;
        int numKvHeads = 1;
        int numQHeads = 2; // Both qHead 0 and qHead 1 map to kvHead 0
        int blockSize = 4;
        int headDim = 2;

        PagedKVCache cache = new PagedKVCache(numBlocks, numLayers, numKvHeads, blockSize, headDim);
        int block0 = cache.allocateBlock();
        int block1 = cache.allocateBlock();
        int[] blockTable = { block0, block1 };

        for (int pos = 0; pos < 6; pos++) {
            int b = (pos < 4) ? block0 : block1;
            int slot = pos % blockSize;
            cache.writeKV(b, 0, 0, slot, K_SEQ_6X2[pos], 0, V_SEQ_6X2[pos], 0);
        }

        // Query input with 2 heads: both heads have the identical query [1.0f, 0.5f]
        float[] qBothHeads = { 1.0f, 0.5f, 1.0f, 0.5f };
        float[] outBothHeads = new float[numQHeads * headDim];

        FlashPagedAttention.forwardDecode(
            qBothHeads,
            blockTable,
            5,
            cache,
            0,
            numQHeads,
            outBothHeads
        );

        // Head 0 output
        assertEquals(6.3544917f, outBothHeads[0], 1e-5f);
        assertEquals(7.3544917f, outBothHeads[1], 1e-5f);

        // Head 1 output (shares kvHead 0, so receives identical context vector)
        assertEquals(6.3544917f, outBothHeads[2], 1e-5f);
        assertEquals(7.3544917f, outBothHeads[3], 1e-5f);
    }
}
