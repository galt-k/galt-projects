package com.galt.llm.inference;

import com.galt.llm.Tensor;

/**
 * Stateful Attention module supporting Grouped-Query Attention (GQA) and paged KV caching.
 * <p>
 * Supports three distinct execution paths:
 * 1. prefill: Parallel multi-token prompt processing with causal masking and cache population.
 * 2. prefillDelta: Rectangular delta-prefill attention reusing cached prefix blocks with zero recomputation.
 * 3. decodeStep: Zero-allocation single-token decode using FlashPagedAttention over cached blocks.
 */
public class CachedAttention {
    private final Tensor wQuery;
    private final Tensor bQuery;
    private final Tensor wKey;
    private final Tensor bKey;
    private final Tensor wValue;
    private final Tensor bValue;
    private final Tensor outProj;
    private final Tensor bOutProj;

    private final int numQHeads;
    private final int numKvHeads;
    private final int headDim;
    private final int embDim;

    public CachedAttention(Tensor wQuery, Tensor bQuery,
                           Tensor wKey, Tensor bKey,
                           Tensor wValue, Tensor bValue,
                           Tensor outProj, Tensor bOutProj,
                           int numQHeads, int numKvHeads) {
        this.wQuery = wQuery;
        this.bQuery = bQuery;
        this.wKey = wKey;
        this.bKey = bKey;
        this.wValue = wValue;
        this.bValue = bValue;
        this.outProj = outProj;
        this.bOutProj = bOutProj;

        this.numQHeads = numQHeads;
        this.numKvHeads = numKvHeads;
        this.embDim = wQuery.shape()[0];

        int qDim = wQuery.shape()[1];
        if (qDim % numQHeads != 0) {
            throw new IllegalArgumentException("Query projection dim must be divisible by numQHeads");
        }
        this.headDim = qDim / numQHeads;

        int kvDim = wKey.shape()[1];
        if (kvDim != numKvHeads * headDim) {
            throw new IllegalArgumentException("Key projection dim (" + kvDim + ") must equal numKvHeads * headDim ("
                    + (numKvHeads * headDim) + ")");
        }
    }

    public CachedAttention(Tensor wQuery, Tensor wKey, Tensor wValue, Tensor outProj,
                           int numQHeads, int numKvHeads) {
        this(wQuery, null, wKey, null, wValue, null, outProj, null, numQHeads, numKvHeads);
    }

    /**
     * Executes single-token decode attention:
     * 1. Projects Q, K, V for this single token.
     * 2. Writes K and V into the paged cache.
     * 3. Runs FlashPagedAttention over cached tokens 0..pos.
     * 4. Projects context through outProj and returns [1, 1, embDim].
     */
    public Tensor decodeStep(Tensor x, int pos, int[] blockTable, PagedKVCache cache, int layer) {
        // x shape: [1, 1, embDim]
        Tensor q = x.matmul(wQuery);
        if (bQuery != null) q = q.addBias(bQuery);

        Tensor k = x.matmul(wKey);
        if (bKey != null) k = k.addBias(bKey);

        Tensor v = x.matmul(wValue);
        if (bValue != null) v = v.addBias(bValue);

        // Write K and V for this token into the physical block
        int blockSize = cache.getBlockSize();
        int logicalBlockIdx = pos / blockSize;
        int slotOffset = pos % blockSize;
        int physicalBlockId = blockTable[logicalBlockIdx];

        float[] kData = k.data();
        float[] vData = v.data();
        for (int kvh = 0; kvh < numKvHeads; kvh++) {
            int srcOffset = kvh * headDim;
            cache.writeKey(physicalBlockId, layer, kvh, slotOffset, kData, srcOffset);
            cache.writeValue(physicalBlockId, layer, kvh, slotOffset, vData, srcOffset);
        }

        // Single-pass FlashPagedAttention over positions 0..pos
        float[] attnOut = new float[numQHeads * headDim];
        FlashPagedAttention.forwardDecode(q.data(), blockTable, pos, cache, layer, numQHeads, attnOut);

        // Project through outProj -> [1, 1, embDim]
        Tensor context = Tensor.of(attnOut, 1, 1, numQHeads * headDim);
        Tensor projected = context.matmul(outProj);
        if (bOutProj != null) {
            projected = projected.addBias(bOutProj);
        }
        return projected;
    }

    /**
     * Executes prompt prefill attention:
     * 1. Projects Q, K, V in parallel for all prompt tokens [1, T, embDim].
     * 2. Writes all prompt K and V vectors into the sequence's physical blocks.
     * 3. Computes causal scaled dot-product attention and returns [1, T, embDim].
     */
    public Tensor prefill(Tensor x, int[] blockTable, PagedKVCache cache, int layer) {
        int B = x.shape()[0];
        int T = x.shape()[1];

        Tensor Q = x.matmul(wQuery);
        if (bQuery != null) Q = Q.addBias(bQuery);

        Tensor K = x.matmul(wKey);
        if (bKey != null) K = K.addBias(bKey);

        Tensor V = x.matmul(wValue);
        if (bValue != null) V = V.addBias(bValue);

        // Write prompt K and V rows into PagedKVCache
        int blockSize = cache.getBlockSize();
        float[] kData = K.data();
        float[] vData = V.data();

        for (int t = 0; t < T; t++) {
            int logicalBlockIdx = t / blockSize;
            int slotOffset = t % blockSize;
            int physicalBlockId = blockTable[logicalBlockIdx];

            for (int kvh = 0; kvh < numKvHeads; kvh++) {
                int srcOffset = (t * numKvHeads + kvh) * headDim;
                cache.writeKey(physicalBlockId, layer, kvh, slotOffset, kData, srcOffset);
                cache.writeValue(physicalBlockId, layer, kvh, slotOffset, vData, srcOffset);
            }
        }

        // Causal attention computation for prompt tokens
        // For GQA: expand K and V to match numQHeads if groupSize > 1
        int groupSize = numQHeads / numKvHeads;
        Tensor qHeads = Q.view(B, T, numQHeads, headDim).transpose(1, 2); // [B, numQHeads, T, headDim]

        Tensor kExpanded;
        Tensor vExpanded;
        if (groupSize == 1) {
            kExpanded = K.view(B, T, numKvHeads, headDim).transpose(1, 2);
            vExpanded = V.view(B, T, numKvHeads, headDim).transpose(1, 2);
        } else {
            // Expand KV heads to match Q heads for batched prompt matmul
            float[] kExpData = new float[B * numQHeads * T * headDim];
            float[] vExpData = new float[B * numQHeads * T * headDim];
            for (int b = 0; b < B; b++) {
                for (int qh = 0; qh < numQHeads; qh++) {
                    int kvh = qh / groupSize;
                    for (int t = 0; t < T; t++) {
                        for (int d = 0; d < headDim; d++) {
                            int srcIdx = ((b * T + t) * numKvHeads + kvh) * headDim + d;
                            int dstIdx = ((b * numQHeads + qh) * T + t) * headDim + d;
                            kExpData[dstIdx] = kData[srcIdx];
                            vExpData[dstIdx] = vData[srcIdx];
                        }
                    }
                }
            }
            kExpanded = Tensor.of(kExpData, B, numQHeads, T, headDim);
            vExpanded = Tensor.of(vExpData, B, numQHeads, T, headDim);
        }

        // Scaled dot-product scores [B, numQHeads, T, T]
        Tensor scores = qHeads.matmul(kExpanded.transpose(2, 3));
        Tensor scaledScores = scores.scale(1.0f / (float) Math.sqrt(headDim));

        // Causal mask (c > r -> -Infinity)
        for (int b = 0; b < B; b++) {
            for (int h = 0; h < numQHeads; h++) {
                for (int r = 0; r < T; r++) {
                    for (int c = 0; c < T; c++) {
                        if (c > r) {
                            scaledScores.set(Float.NEGATIVE_INFINITY, b, h, r, c);
                        }
                    }
                }
            }
        }

        Tensor weights = scaledScores.softmax(-1);
        Tensor context = weights.matmul(vExpanded); // [B, numQHeads, T, headDim]
        context = context.transpose(1, 2).contiguous().view(B, T, numQHeads * headDim);

        Tensor projected = context.matmul(outProj);
        if (bOutProj != null) {
            projected = projected.addBias(bOutProj);
        }
        return projected;
    }

    /**
     * Executes rectangular delta-prefill attention reusing cached prefix blocks:
     * 1. Projects Q, K, V only for the newly arrived delta tokens [B, T_delta, embDim].
     * 2. Writes new K and V rows into PagedKVCache starting at pos = cachedTokenCount + t.
     * 3. Runs single-pass FlashPagedAttention for each delta query row across all keys 0..pos.
     * 4. Projects context through outProj and returns [B, T_delta, embDim].
     */
    public Tensor prefillDelta(Tensor xDelta, int[] blockTable, int cachedTokenCount, PagedKVCache cache, int layer) {
        int B = xDelta.shape()[0];
        int T_delta = xDelta.shape()[1];

        Tensor Q = xDelta.matmul(wQuery);
        if (bQuery != null) Q = Q.addBias(bQuery);

        Tensor K = xDelta.matmul(wKey);
        if (bKey != null) K = K.addBias(bKey);

        Tensor V = xDelta.matmul(wValue);
        if (bValue != null) V = V.addBias(bValue);

        // Write new delta K and V rows into PagedKVCache starting at cachedTokenCount
        int blockSize = cache.getBlockSize();
        float[] kData = K.data();
        float[] vData = V.data();

        for (int t = 0; t < T_delta; t++) {
            int pos = cachedTokenCount + t;
            int logicalBlockIdx = pos / blockSize;
            int slotOffset = pos % blockSize;
            int physicalBlockId = blockTable[logicalBlockIdx];

            for (int kvh = 0; kvh < numKvHeads; kvh++) {
                int srcOffset = (t * numKvHeads + kvh) * headDim;
                cache.writeKey(physicalBlockId, layer, kvh, slotOffset, kData, srcOffset);
                cache.writeValue(physicalBlockId, layer, kvh, slotOffset, vData, srcOffset);
            }
        }

        // Rectangular attention: For each delta query, attend across 0..(cachedTokenCount + t)
        float[] qData = Q.data();
        float[] contextData = new float[B * T_delta * numQHeads * headDim];

        for (int b = 0; b < B; b++) {
            for (int t = 0; t < T_delta; t++) {
                int pos = cachedTokenCount + t;
                float[] qRow = new float[numQHeads * headDim];
                System.arraycopy(qData, (b * T_delta + t) * numQHeads * headDim, qRow, 0, numQHeads * headDim);

                float[] attnOut = new float[numQHeads * headDim];
                FlashPagedAttention.forwardDecode(qRow, blockTable, pos, cache, layer, numQHeads, attnOut);

                System.arraycopy(attnOut, 0, contextData, (b * T_delta + t) * numQHeads * headDim, numQHeads * headDim);
            }
        }

        Tensor context = Tensor.of(contextData, B, T_delta, numQHeads * headDim);
        Tensor projected = context.matmul(outProj);
        if (bOutProj != null) {
            projected = projected.addBias(bOutProj);
        }
        return projected;
    }
}
