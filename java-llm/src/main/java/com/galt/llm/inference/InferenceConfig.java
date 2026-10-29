package com.galt.llm.inference;

/**
 * Hyperparameter configuration for the stateful agentic inference engine.
 * Supports Grouped-Query Attention (GQA) and paged KV block sizing.
 */
public record InferenceConfig(
    int vocabSize,
    int contextLength,
    int embDim,
    int numQHeads,
    int numKvHeads,
    int headDim,
    int numLayers,
    int blockSize,
    int maxPhysicalBlocks
) {
    /**
     * Deterministic Micro-GPT configuration from AGENTIC_INFERENCE_ENGINE_SPEC.md Section 5.
     * Features 2 Query Heads sharing 1 KV Head (GQA ratio 2:1).
     */
    public static InferenceConfig micro() {
        return new InferenceConfig(8, 16, 4, 2, 1, 2, 1, 4, 16);
    }
}
