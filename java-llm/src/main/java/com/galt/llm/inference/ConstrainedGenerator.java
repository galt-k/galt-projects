package com.galt.llm.inference;

import com.galt.llm.SamplingUtils;
import com.galt.llm.Tensor;

import java.util.Arrays;

/** Greedy grammar-constrained generation for a single sequence. */
public class ConstrainedGenerator {
    /**
     * Returns output token IDs, including EOS when reached. Reaching the token limit
     * returns a potentially incomplete output. Grammar state starts at zero.
     * The caller owns the cache and must supply allocated, writable blocks covering
     * the prompt and at most maxOutputTokens - 1 decoded output tokens.
     * EOS and the final selected token are not fed back into the model.
     */
    public int[] generate(int[] prompt, CachedGPTModel model, FSMlogitMask masker,
                          int[] blockTable, PagedKVCache cache,
                          int eosTokenId, int maxOutputTokens) {
        InferenceConfig config = model.config();
        if (prompt == null || prompt.length == 0 || maxOutputTokens < 0) {
            throw new IllegalArgumentException("Provide a nonempty prompt and a nonnegative output limit");
        }
        if (eosTokenId < 0 || eosTokenId >= config.vocabSize()) {
            throw new IllegalArgumentException("EOS token outside vocabulary");
        }
        if (prompt.length > config.contextLength()
                || (long) prompt.length + Math.max(0, maxOutputTokens - 1) > config.contextLength()) {
            throw new IllegalArgumentException("Requested generation exceeds model context length");
        }
        for (int token : prompt) {
            if (token < 0 || token >= config.vocabSize()) {
                throw new IllegalArgumentException("Prompt token outside vocabulary: " + token);
            }
        }
        if (maxOutputTokens == 0) {
            return new int[0];
        }
        long requiredSlots = (long) prompt.length + maxOutputTokens - 1;
        long requiredBlocks = (requiredSlots + cache.getBlockSize() - 1) / cache.getBlockSize();
        if (blockTable.length < requiredBlocks) {
            throw new IllegalArgumentException("Block table is too small for requested generation");
        }

        Tensor scores = model.prefill(new int[][]{prompt}, blockTable, cache);
        int scorePosition = prompt.length - 1;
        int currentState = 0;
        int[] output = new int[maxOutputTokens];
        float[] logits = new float[config.vocabSize()];
        for (int step = 0; step < maxOutputTokens; step++) {
            for (int token = 0; token < logits.length; token++) {
                logits[token] = scores.get(0, scorePosition, token);
            }
            masker.applyMaskInPlace(logits, currentState);
            int selected = SamplingUtils.findMaxIndex(logits);
            if (!Float.isFinite(logits[selected])) {
                throw new IllegalStateException("No finite legal score in grammar state " + currentState);
            }
            currentState = masker.nextState(currentState, selected);
            output[step] = selected;
            if (selected == eosTokenId || step == maxOutputTokens - 1) {
                return Arrays.copyOf(output, step + 1);
            }
            scores = model.decodeStep(new int[][]{{selected}}, prompt.length + step, blockTable, cache);
            scorePosition = 0;
        }
        return output;
    }
}
