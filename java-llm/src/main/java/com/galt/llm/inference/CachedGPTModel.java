package com.galt.llm.inference;

import com.galt.llm.LayerNorm;
import com.galt.llm.Tensor;

/**
 * Stateful GPT Model for high-throughput, low-latency autoregressive inference.
 * <p>
 * Implements:
 * 1. prefill: Parallel multi-token prompt processing with causal masking and KV block allocation.
 * 2. decodeStep: Zero-allocation single-token decode using absolute position embedding and FlashPagedAttention.
 */
public class CachedGPTModel {
    private final InferenceConfig config;
    private final Tensor tokenEmb;
    private final Tensor posEmb;
    private final CachedTransformerBlock[] blocks;
    private final LayerNorm finalLayerNorm;
    private final Tensor outHead;

    public CachedGPTModel(InferenceConfig config,
                          Tensor tokenEmb,
                          Tensor posEmb,
                          CachedTransformerBlock[] blocks,
                          LayerNorm finalLayerNorm,
                          Tensor outHead) {
        this.config = config;
        this.tokenEmb = tokenEmb;
        this.posEmb = posEmb;
        this.blocks = blocks;
        this.finalLayerNorm = finalLayerNorm;
        this.outHead = outHead;
    }

    /**
     * Executes prompt prefill across all prompt tokens [B, T_prompt]:
     * 1. Looks up tokenEmb[token] + posEmb[0..T-1].
     * 2. Runs through all CachedTransformerBlocks (populating PagedKVCache).
     * 3. Runs finalLayerNorm and projects to vocabulary logits [B, T_prompt, vocabSize].
     */
    public Tensor prefill(int[][] inIdx, int[] blockTable, PagedKVCache cache) {
        int B = inIdx.length;
        int T = inIdx[0].length;
        int D = config.embDim();

        Tensor x = Tensor.zeros(B, T, D);
        for (int b = 0; b < B; b++) {
            for (int t = 0; t < T; t++) {
                int tokenID = inIdx[b][t];
                for (int d = 0; d < D; d++) {
                    float tokVal = tokenEmb.get(tokenID, d);
                    float posVal = posEmb.get(t, d);
                    x.set(tokVal + posVal, b, t, d);
                }
            }
        }

        for (int layer = 0; layer < blocks.length; layer++) {
            x = blocks[layer].prefill(x, blockTable, cache, layer);
        }

        x = finalLayerNorm.forward(x);
        return x.matmul(outHead); // shape: [B, T, vocabSize]
    }

    /**
     * Executes a single decode step for the newest token [B, 1] at absolute position pos:
     * 1. Looks up tokenEmb[token] + posEmb[pos] (using absolute sequence position pos!).
     * 2. Passes through all CachedTransformerBlocks using FlashPagedAttention over cached blocks.
     * 3. Runs finalLayerNorm and projects to vocabulary logits [B, 1, vocabSize].
     */
    public Tensor decodeStep(int[][] inIdx, int pos, int[] blockTable, PagedKVCache cache) {
        int B = inIdx.length;
        int D = config.embDim();

        Tensor x = Tensor.zeros(B, 1, D);
        for (int b = 0; b < B; b++) {
            int tokenID = inIdx[b][0];
            for (int d = 0; d < D; d++) {
                float tokVal = tokenEmb.get(tokenID, d);
                float posVal = posEmb.get(pos, d); // Must use absolute pos, NOT 0!
                x.set(tokVal + posVal, b, 0, d);
            }
        }

        for (int layer = 0; layer < blocks.length; layer++) {
            x = blocks[layer].decodeStep(x, pos, blockTable, cache, layer);
        }

        x = finalLayerNorm.forward(x);
        return x.matmul(outHead); // shape: [B, 1, vocabSize]
    }
}
