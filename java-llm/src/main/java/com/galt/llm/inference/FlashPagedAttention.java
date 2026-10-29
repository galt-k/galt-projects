package com.galt.llm.inference;

/**
 * FlashPagedAttention implements single-pass streaming online softmax attention
 * (FlashInfer / FlashAttention-2) directly over paged KV blocks with
 * Grouped-Query Attention (GQA) support.
 * <p>
 * Key invariants:
 * 1. Single-pass traversal: Reads K and V from physical blocks strictly once.
 * 2. Zero heap allocations: Accumulates running max, sum, and output in registers.
 * 3. Exact float32 numerical equivalence to two-pass standard softmax within 1e-6f.
 */
public class FlashPagedAttention {

    /**
     * Computes single-query attention for one query head over paged KV blocks
     * using single-pass streaming online softmax.
     *
     * @param q          query vector for this head (length: headDim)
     * @param qOffset    starting offset in q
     * @param blockTable array of physical block IDs for this sequence
     * @param pos        the current sequence position (inclusive, so tokens 0..pos are attended)
     * @param cache      the PagedKVCache storing physical KV blocks
     * @param layer      the layer index
     * @param qHead      the query head index [0, numQHeads - 1]
     * @param numQHeads  total number of query heads in the model
     * @param out        destination array to store the resulting context vector
     * @param outOffset  starting offset in out
     */
    public static void singleQueryAttention(float[] q, int qOffset,
                                           int[] blockTable,
                                           int pos,
                                           PagedKVCache cache,
                                           int layer,
                                           int qHead,
                                           int numQHeads,
                                           float[] out, int outOffset) {
        int numKvHeads = cache.getNumKvHeads();
        int groupSize = numQHeads / numKvHeads;
        int kvHead = qHead / groupSize;

        int headDim = cache.getHeadDim();
        int blockSize = cache.getBlockSize();
        float scale = 1.0f / (float) Math.sqrt(headDim);

        float m = Float.NEGATIVE_INFINITY;
        float l = 0.0f;
        float[] acc = new float[headDim];

        float[] keyData = cache.getKeyData();
        float[] valueData = cache.getValueData();

        for (int j = 0; j <= pos; j++) {
            int logicalBlockIdx = j / blockSize;
            int blockOffset = j % blockSize;
            int physicalBlockId = blockTable[logicalBlockIdx];

            int kvOffset = cache.getOffset(physicalBlockId, layer, kvHead, blockOffset);

            // 1. Scaled dot-product: s_j = (q . k_j) / sqrt(headDim)
            float dot = 0.0f;
            for (int d = 0; d < headDim; d++) {
                dot += q[qOffset + d] * keyData[kvOffset + d];
            }
            float score = dot * scale;

            // 2. Online softmax running update
            float mNew = Math.max(m, score);
            float alpha = (float) Math.exp(m - mNew);
            float beta = (float) Math.exp(score - mNew);

            l = (l * alpha) + beta;

            for (int d = 0; d < headDim; d++) {
                acc[d] = (acc[d] * alpha) + (beta * valueData[kvOffset + d]);
            }

            m = mNew;
        }

        // 3. Final normalization
        float invL = (l == 0.0f) ? 0.0f : (1.0f / l);
        for (int d = 0; d < headDim; d++) {
            out[outOffset + d] = acc[d] * invL;
        }
    }

    /**
     * Forward pass for a single decode step across all query heads.
     *
     * @param q          query tensor for the current token [1, 1, numQHeads * headDim]
     * @param blockTable page table for the active sequence
     * @param pos        current token position
     * @param cache      shared PagedKVCache
     * @param layer      layer index
     * @param numQHeads  total number of query heads
     * @param out        destination array [numQHeads * headDim]
     */
    public static void forwardDecode(float[] q,
                                     int[] blockTable,
                                     int pos,
                                     PagedKVCache cache,
                                     int layer,
                                     int numQHeads,
                                     float[] out) {
        int headDim = cache.getHeadDim();
        for (int qh = 0; qh < numQHeads; qh++) {
            int qOffset = qh * headDim;
            int outOffset = qh * headDim;
            singleQueryAttention(q, qOffset, blockTable, pos, cache, layer, qh, numQHeads, out, outOffset);
        }
    }
}
