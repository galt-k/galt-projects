package com.galt.llm.inference;

import java.util.ArrayDeque;

public class PagedKVCache {
    private final int numBlocks;
    private final int numLayers;
    private final int numKvHeads;
    private final int blockSize;
    private final int headDim;

    private final int headStride;
    private final int layerStride;
    private final int blockStride;

    private final float[] keyData;
    private final float[] valueData;
    private final ArrayDeque<Integer> freeBlocks;

    public PagedKVCache(int numBlocks,
                        int numLayers,
                        int numKvHeads,
                        int blockSize,
                        int headDim) {
        if (numBlocks <= 0 || numLayers <= 0 || numKvHeads <= 0 || blockSize <= 0 || headDim <= 0) {
            throw new IllegalArgumentException("All dimensions must be positive");
        }
        this.numBlocks = numBlocks;
        this.numLayers = numLayers;
        this.numKvHeads = numKvHeads;
        this.blockSize = blockSize;
        this.headDim = headDim;

        this.headStride = blockSize * headDim;
        this.layerStride = numKvHeads * this.headStride;
        this.blockStride = numLayers * this.layerStride;

        this.keyData = new float[numBlocks * this.blockStride];
        this.valueData = new float[numBlocks * this.blockStride];

        this.freeBlocks = new ArrayDeque<>(numBlocks);
        for (int i = 0; i < numBlocks; i++) {
            this.freeBlocks.add(i);
        }
    }

    /**
     * Allocates a free physical block from the available pool.
     * <p>
     * Note: This does not allocate new memory on the heap. It takes an available
     * physical block index from the pre-allocated pool and marks it as in-use.
     *
     * @return the physical block ID (in range [0, numBlocks - 1])
     * @throws IllegalStateException if all physical blocks are exhausted
     */
    public int allocateBlock() {
        if (freeBlocks.isEmpty()) {
            throw new IllegalStateException("Out of KV cache physical blocks: pool exhausted");
        }
        return freeBlocks.pop();
    }

    /**
     * Releases a physical block back to the pool of free blocks.
     *
     * @param blockId the physical block ID to release
     * @throws IllegalArgumentException if blockId is out of bounds [0, numBlocks - 1]
     * @throws IllegalStateException    if blockId is already present in the free pool (double-free)
     */
    public void freeBlock(int blockId) {
        if (blockId < 0 || blockId >= numBlocks) {
            throw new IllegalArgumentException(
                "Invalid blockId: " + blockId + ". Must be in range [0, " + (numBlocks - 1) + "]"
            );
        }
        if (freeBlocks.contains(blockId)) {
            throw new IllegalStateException("Double-free detected for blockId: " + blockId);
        }
        freeBlocks.push(blockId);
    }

    /**
     * Returns the current number of free physical blocks available in the pool.
     */
    public int getNumFreeBlocks() {
        return freeBlocks.size();
    }

    /**
     * Calculates the flat 1D offset in keyData and valueData for a given
     * physical block, layer, KV head, and slot.
     * <p>
     * The subsequent {@code headDim} floats starting from this offset belong
     * to this coordinate.
     *
     * @param blockId the physical block ID [0, numBlocks - 1]
     * @param layer   the transformer layer index [0, numLayers - 1]
     * @param kvHead  the KV head index [0, numKvHeads - 1]
     * @param slot    the slot offset within the block [0, blockSize - 1]
     * @return the flat 1D index into keyData or valueData
     * @throws IllegalArgumentException if any coordinate is out of bounds
     */
    public int getOffset(int blockId,
                         int layer,
                         int kvHead,
                         int slot) {
        if (blockId < 0 || blockId >= numBlocks) {
            throw new IllegalArgumentException("blockId out of bounds: " + blockId);
        }
        if (layer < 0 || layer >= numLayers) {
            throw new IllegalArgumentException("layer out of bounds: " + layer);
        }
        if (kvHead < 0 || kvHead >= numKvHeads) {
            throw new IllegalArgumentException("kvHead out of bounds: " + kvHead);
        }
        if (slot < 0 || slot >= blockSize) {
            throw new IllegalArgumentException("slot out of bounds: " + slot);
        }
        return (blockId * blockStride)
             + (layer * layerStride)
             + (kvHead * headStride)
             + (slot * headDim);
    }

    /**
     * Writes a headDim-length Key slice into keyData.
     */
    public void writeKey(int blockId, int layer, int kvHead, int slot, float[] src, int srcOffset) {
        int destOffset = getOffset(blockId, layer, kvHead, slot);
        System.arraycopy(src, srcOffset, keyData, destOffset, headDim);
    }

    /**
     * Writes a headDim-length Value slice into valueData.
     */
    public void writeValue(int blockId, int layer, int kvHead, int slot, float[] src, int srcOffset) {
        int destOffset = getOffset(blockId, layer, kvHead, slot);
        System.arraycopy(src, srcOffset, valueData, destOffset, headDim);
    }

    /**
     * Convenience method to write both Key and Value vectors in one shot.
     */
    public void writeKV(int blockId, int layer, int kvHead, int slot,
                        float[] keySrc, int keyOffset,
                        float[] valSrc, int valOffset) {
        writeKey(blockId, layer, kvHead, slot, keySrc, keyOffset);
        writeValue(blockId, layer, kvHead, slot, valSrc, valOffset);
    }

    public float[] getKeyData() {
        return keyData;
    }

    public float[] getValueData() {
        return valueData;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public int getHeadDim() {
        return headDim;
    }

    public int getNumKvHeads() {
        return numKvHeads;
    }

    public int getNumBlocks() {
        return numBlocks;
    }

    public int getNumLayers() {
        return numLayers;
    }
}
