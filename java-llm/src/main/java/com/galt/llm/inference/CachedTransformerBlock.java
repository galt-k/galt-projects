package com.galt.llm.inference;

import com.galt.llm.FeedForward;
import com.galt.llm.LayerNorm;
import com.galt.llm.Tensor;

/**
 * Stateful Transformer Block coordinating CachedAttention, LayerNorm, and FeedForward.
 */
public class CachedTransformerBlock {
    private final CachedAttention att;
    private final FeedForward ff;
    private final LayerNorm norm1;
    private final LayerNorm norm2;

    public CachedTransformerBlock(CachedAttention att,
                                  FeedForward ff,
                                  LayerNorm norm1,
                                  LayerNorm norm2) {
        this.att = att;
        this.ff = ff;
        this.norm1 = norm1;
        this.norm2 = norm2;
    }

    public Tensor prefill(Tensor x, int[] blockTable, PagedKVCache cache, int layer) {
        // Attention + shortcut
        Tensor shortcut = x;
        x = norm1.forward(x);
        x = att.prefill(x, blockTable, cache, layer);
        x = shortcut.add(x);

        // FeedForward + shortcut
        shortcut = x;
        x = norm2.forward(x);
        x = ff.forward(x);
        x = shortcut.add(x);

        return x;
    }

    public Tensor prefillDelta(Tensor x, int[] blockTable, int cachedTokenCount, PagedKVCache cache, int layer) {
        // Attention + shortcut
        Tensor shortcut = x;
        x = norm1.forward(x);
        x = att.prefillDelta(x, blockTable, cachedTokenCount, cache, layer);
        x = shortcut.add(x);

        // FeedForward + shortcut
        shortcut = x;
        x = norm2.forward(x);
        x = ff.forward(x);
        x = shortcut.add(x);

        return x;
    }

    public Tensor decodeStep(Tensor x, int pos, int[] blockTable, PagedKVCache cache, int layer) {
        // Attention + shortcut
        Tensor shortcut = x;
        x = norm1.forward(x);
        x = att.decodeStep(x, pos, blockTable, cache, layer);
        x = shortcut.add(x);

        // FeedForward + shortcut
        shortcut = x;
        x = norm2.forward(x);
        x = ff.forward(x);
        x = shortcut.add(x);

        return x;
    }
}
