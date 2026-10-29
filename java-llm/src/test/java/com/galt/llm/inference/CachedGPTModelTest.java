package com.galt.llm.inference;

import com.galt.llm.*;
import com.galt.llm.config.GPTConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CachedGPTModelTest {

    @Test
    void testEndToEndModelEquivalence() {
        int vocabSize = 8;
        int contextLength = 16;
        int embDim = 4;
        int numHeads = 2; // GQA ratio 1:1 for direct comparison with standard GPTModel
        int headDim = 2;
        int numLayers = 1;
        int blockSize = 4;
        int maxPhysicalBlocks = 4;

        InferenceConfig infConfig = new InferenceConfig(
            vocabSize, contextLength, embDim, numHeads, numHeads, headDim, numLayers, blockSize, maxPhysicalBlocks
        );
        GPTConfig gptConfig = new GPTConfig(vocabSize, contextLength, embDim, numHeads, numLayers, false);

        // 1. Shared Embeddings
        float[] tokData = new float[vocabSize * embDim];
        for (int i = 0; i < tokData.length; i++) {
            tokData[i] = (i % 7) * 0.1f;
        }
        Tensor tokEmb = Tensor.of(tokData, vocabSize, embDim);

        float[] posData = new float[contextLength * embDim];
        for (int i = 0; i < posData.length; i++) {
            posData[i] = (i % 5) * 0.05f;
        }
        Tensor posEmb = Tensor.of(posData, contextLength, embDim);

        // 2. Shared Attention & FeedForward Weights
        float[] wData = new float[embDim * embDim];
        for (int i = 0; i < wData.length; i++) {
            wData[i] = (i % 4) * 0.1f + 0.05f;
        }
        Tensor wQ = Tensor.of(wData.clone(), embDim, embDim);
        Tensor wK = Tensor.of(wData.clone(), embDim, embDim);
        Tensor wV = Tensor.of(wData.clone(), embDim, embDim);
        Tensor outProj = Tensor.of(wData.clone(), embDim, embDim);

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

        // Stateless TransformerBlock
        MultiHeadAttention statelessMha = new MultiHeadAttention(wQ, wK, wV, outProj, numHeads);
        TransformerBlock[] statelessBlocks = {
            new TransformerBlock(statelessMha, ff, ln1, ln2)
        };

        // Stateful CachedTransformerBlock
        CachedAttention cachedAtt = new CachedAttention(wQ, wK, wV, outProj, numHeads, numHeads);
        CachedTransformerBlock[] cachedBlocks = {
            new CachedTransformerBlock(cachedAtt, ff, ln1, ln2)
        };

        // Shared Final LayerNorm & OutHead
        LayerNorm finalNorm = new LayerNorm(Tensor.of(ones.clone(), embDim), Tensor.of(zeros.clone(), embDim));
        float[] headData = new float[embDim * vocabSize];
        for (int i = 0; i < headData.length; i++) {
            headData[i] = (i % 6) * 0.04f + 0.01f;
        }
        Tensor outHead = Tensor.of(headData, embDim, vocabSize);

        // Instantiate both models
        GPTModel statelessModel = new GPTModel(gptConfig, tokEmb, posEmb, statelessBlocks, finalNorm, outHead);
        CachedGPTModel cachedModel = new CachedGPTModel(infConfig, tokEmb, posEmb, cachedBlocks, finalNorm, outHead);

        // KV Cache and Block Table
        PagedKVCache cache = new PagedKVCache(maxPhysicalBlocks, numLayers, numHeads, blockSize, headDim);
        int block0 = cache.allocateBlock();
        int block1 = cache.allocateBlock();
        int[] blockTable = { block0, block1 };

        // 1. Prefill Phase: 4-token prompt [1, 2, 3, 4]
        int[][] prompt = { { 1, 2, 3, 4 } };
        Tensor prefillLogits = cachedModel.prefill(prompt, blockTable, cache);
        Tensor statelessPromptLogits = statelessModel.forward(prompt);

        for (int t = 0; t < 4; t++) {
            for (int v = 0; v < vocabSize; v++) {
                assertEquals(
                    statelessPromptLogits.get(0, t, v),
                    prefillLogits.get(0, t, v),
                    1e-5f,
                    "Prefill logits mismatch at token " + t + ", vocab " + v
                );
            }
        }

        // 2. Decode Step 1: Token 5 at pos = 4
        int[][] step1Token = { { 5 } };
        Tensor decode1Logits = cachedModel.decodeStep(step1Token, 4, blockTable, cache);

        int[][] seq5 = { { 1, 2, 3, 4, 5 } };
        Tensor statelessSeq5Logits = statelessModel.forward(seq5);

        for (int v = 0; v < vocabSize; v++) {
            assertEquals(
                statelessSeq5Logits.get(0, 4, v),
                decode1Logits.get(0, 0, v),
                1e-5f,
                "Decode step 1 logits mismatch at vocab " + v
            );
        }

        // 3. Decode Step 2: Token 6 at pos = 5
        int[][] step2Token = { { 6 } };
        Tensor decode2Logits = cachedModel.decodeStep(step2Token, 5, blockTable, cache);

        int[][] seq6 = { { 1, 2, 3, 4, 5, 6 } };
        Tensor statelessSeq6Logits = statelessModel.forward(seq6);

        for (int v = 0; v < vocabSize; v++) {
            assertEquals(
                statelessSeq6Logits.get(0, 5, v),
                decode2Logits.get(0, 0, v),
                1e-5f,
                "Decode step 2 logits mismatch at vocab " + v
            );
        }
    }
}
