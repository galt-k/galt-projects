package com.galt.llm.inference;

import com.galt.llm.MultiHeadAttention;
import com.galt.llm.Tensor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CachedAttentionTest {

    // Identity projection weights for clean verification
    private static final float[] W_IDENTITY_4X4 = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };

    @Test
    void testEquivalenceBetweenStatelessMHAAndStatefulCachedAttention() {
        int B = 1;
        int T_prompt = 4;
        int embDim = 4;
        int numHeads = 2; // numQHeads = numKvHeads = 2 (Standard MHA comparison)
        int headDim = 2;
        int blockSize = 4;

        Tensor wQ = Tensor.of(W_IDENTITY_4X4.clone(), embDim, embDim);
        Tensor wK = Tensor.of(W_IDENTITY_4X4.clone(), embDim, embDim);
        Tensor wV = Tensor.of(W_IDENTITY_4X4.clone(), embDim, embDim);
        Tensor outProj = Tensor.of(W_IDENTITY_4X4.clone(), embDim, embDim);

        // Stateless reference MHA from Chapter 4
        MultiHeadAttention statelessMha = new MultiHeadAttention(wQ, wK, wV, outProj, numHeads);

        // Stateful CachedAttention from Chapter 5
        CachedAttention cachedAtt = new CachedAttention(wQ, wK, wV, outProj, numHeads, numHeads);

        // 6-token sequence: 4-token prompt + 2 decode steps
        float[] fullSequenceData = {
            0.1f, 0.2f, 0.3f, 0.4f, // token 0
            0.5f, 0.6f, 0.7f, 0.8f, // token 1
            0.9f, 1.0f, 1.1f, 1.2f, // token 2
            1.3f, 1.4f, 1.5f, 1.6f, // token 3 (prompt ends here)
            1.7f, 1.8f, 1.9f, 2.0f, // token 4 (decode step 1)
            2.1f, 2.2f, 2.3f, 2.4f  // token 5 (decode step 2)
        };

        // Cache setup: pool of 4 blocks
        PagedKVCache cache = new PagedKVCache(4, 1, numHeads, blockSize, headDim);
        int block0 = cache.allocateBlock();
        int block1 = cache.allocateBlock();
        int[] blockTable = { block0, block1 };

        // 1. Prefill phase: 4 prompt tokens
        float[] promptData = new float[T_prompt * embDim];
        System.arraycopy(fullSequenceData, 0, promptData, 0, promptData.length);
        Tensor promptTensor = Tensor.of(promptData, B, T_prompt, embDim);

        Tensor prefillOutput = cachedAtt.prefill(promptTensor, blockTable, cache, 0);

        // Verify prefill output matches stateless MHA over prompt [1, 4, 4]
        Tensor statelessPromptOut = statelessMha.forward(promptTensor, true);
        for (int t = 0; t < T_prompt; t++) {
            for (int d = 0; d < embDim; d++) {
                assertEquals(
                    statelessPromptOut.get(0, t, d),
                    prefillOutput.get(0, t, d),
                    1e-5f,
                    "Prefill output mismatch at token " + t + ", dim " + d
                );
            }
        }

        // 2. Decode Step 1: Token 4 at pos = 4
        float[] tok4Data = new float[embDim];
        System.arraycopy(fullSequenceData, 4 * embDim, tok4Data, 0, embDim);
        Tensor tok4Tensor = Tensor.of(tok4Data, 1, 1, embDim);

        Tensor decodeOut4 = cachedAtt.decodeStep(tok4Tensor, 4, blockTable, cache, 0);

        // Stateless reference forward on all 5 tokens [1, 5, 4]
        float[] seq5Data = new float[5 * embDim];
        System.arraycopy(fullSequenceData, 0, seq5Data, 0, seq5Data.length);
        Tensor statelessSeq5 = statelessMha.forward(Tensor.of(seq5Data, 1, 5, embDim), true);

        // Verify decode step 4 output matches last row (row 4) of stateless MHA
        for (int d = 0; d < embDim; d++) {
            assertEquals(
                statelessSeq5.get(0, 4, d),
                decodeOut4.get(0, 0, d),
                1e-5f,
                "Decode step 4 output must match stateless MHA last row at dim " + d
            );
        }

        // 3. Decode Step 2: Token 5 at pos = 5
        float[] tok5Data = new float[embDim];
        System.arraycopy(fullSequenceData, 5 * embDim, tok5Data, 0, embDim);
        Tensor tok5Tensor = Tensor.of(tok5Data, 1, 1, embDim);

        Tensor decodeOut5 = cachedAtt.decodeStep(tok5Tensor, 5, blockTable, cache, 0);

        // Stateless reference forward on all 6 tokens [1, 6, 4]
        Tensor statelessSeq6 = statelessMha.forward(Tensor.of(fullSequenceData, 1, 6, embDim), true);

        // Verify decode step 5 output matches last row (row 5) of stateless MHA
        for (int d = 0; d < embDim; d++) {
            assertEquals(
                statelessSeq6.get(0, 5, d),
                decodeOut5.get(0, 0, d),
                1e-5f,
                "Decode step 5 output must match stateless MHA last row at dim " + d
            );
        }
    }
}
