package com.galt.llm.inference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PagedKVCacheTest {
    @Test
    void sharedBlockIsRecycledOnlyAfterLastOwnerReleasesIt() {
        PagedKVCache cache = new PagedKVCache(1, 1, 1, 4, 2);
        int block = cache.allocateBlock();
        assertEquals(1, cache.getRefCount(block));
        cache.writeKV(block, 0, 0, 0, new float[]{2, 3}, 0, new float[]{4, 5}, 0);
        cache.retainBlock(block);
        assertEquals(2, cache.getRefCount(block));

        cache.freeBlock(block);
        assertEquals(1, cache.getRefCount(block));
        assertEquals(0, cache.getNumFreeBlocks());
        assertThrows(IllegalStateException.class, cache::allocateBlock);
        assertEquals(2.0f, cache.getKeyData()[cache.getOffset(block, 0, 0, 0)]);

        cache.freeBlock(block);
        assertEquals(0, cache.getRefCount(block));
        assertEquals(1, cache.getNumFreeBlocks());
        assertEquals(block, cache.allocateBlock());
        assertEquals(1, cache.getRefCount(block));
    }

    @Test
    void rejectsRetainingFreeBlocksAndInvalidReferenceLookups() {
        PagedKVCache cache = new PagedKVCache(1, 1, 1, 4, 2);
        assertEquals(0, cache.getRefCount(0));
        assertThrows(IllegalStateException.class, () -> cache.retainBlock(0));
        assertThrows(IllegalArgumentException.class, () -> cache.retainBlock(-1));
        assertThrows(IllegalArgumentException.class, () -> cache.retainBlock(1));
        assertThrows(IllegalArgumentException.class, () -> cache.getRefCount(-1));
        assertThrows(IllegalArgumentException.class, () -> cache.getRefCount(1));
    }

    @Test
    void testInitializationAndCapacity() {
        int numBlocks = 4;
        int numLayers = 2;
        int numKvHeads = 1;
        int blockSize = 4;
        int headDim = 8;

        PagedKVCache cache = new PagedKVCache(numBlocks, numLayers, numKvHeads, blockSize, headDim);

        assertEquals(numBlocks, cache.getNumBlocks());
        assertEquals(numBlocks, cache.getNumFreeBlocks());
        assertEquals(numLayers, cache.getNumLayers());
        assertEquals(numKvHeads, cache.getNumKvHeads());
        assertEquals(blockSize, cache.getBlockSize());
        assertEquals(headDim, cache.getHeadDim());

        // blockStride = 2 layers * 1 kvHead * 4 slots * 8 floats = 64
        // total capacity = 4 blocks * 64 = 256 floats
        assertEquals(256, cache.getKeyData().length);
        assertEquals(256, cache.getValueData().length);
    }

    @Test
    void testAllocationAndExhaustion() {
        int numBlocks = 3;
        PagedKVCache cache = new PagedKVCache(numBlocks, 1, 1, 4, 4);

        assertEquals(3, cache.getNumFreeBlocks());

        int b0 = cache.allocateBlock();
        int b1 = cache.allocateBlock();
        int b2 = cache.allocateBlock();

        assertEquals(0, cache.getNumFreeBlocks());

        // Pool is exhausted, must throw IllegalStateException
        assertThrows(IllegalStateException.class, cache::allocateBlock);

        // Free one block and re-allocate
        cache.freeBlock(b1);
        assertEquals(1, cache.getNumFreeBlocks());

        int reallocated = cache.allocateBlock();
        assertEquals(b1, reallocated);
        assertEquals(0, cache.getNumFreeBlocks());
    }

    @Test
    void testDoubleFreeAndOutOfBoundsValidation() {
        PagedKVCache cache = new PagedKVCache(2, 1, 1, 4, 4);

        int b0 = cache.allocateBlock();

        // Out of bounds free
        assertThrows(IllegalArgumentException.class, () -> cache.freeBlock(-1));
        assertThrows(IllegalArgumentException.class, () -> cache.freeBlock(2));

        // Free allocated block
        cache.freeBlock(b0);

        // Double-free of the same block must throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> cache.freeBlock(b0));
    }

    @Test
    void testOffsetCalculation() {
        int numBlocks = 4;
        int numLayers = 3;
        int numKvHeads = 2;
        int blockSize = 4;
        int headDim = 8;

        // headStride = 4 * 8 = 32
        // layerStride = 2 * 32 = 64
        // blockStride = 3 * 64 = 192
        PagedKVCache cache = new PagedKVCache(numBlocks, numLayers, numKvHeads, blockSize, headDim);

        // Coordinate: block 1, layer 2, kvHead 1, slot 3
        // Expected offset: (1 * 192) + (2 * 64) + (1 * 32) + (3 * 8) = 192 + 128 + 32 + 24 = 376
        int offset = cache.getOffset(1, 2, 1, 3);
        assertEquals(376, offset);

        // Bounds checks
        assertThrows(IllegalArgumentException.class, () -> cache.getOffset(4, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> cache.getOffset(0, 3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> cache.getOffset(0, 0, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> cache.getOffset(0, 0, 0, 4));
    }

    @Test
    void testWriteAndReadKV() {
        PagedKVCache cache = new PagedKVCache(2, 1, 1, 4, 2);
        int blockId = cache.allocateBlock();

        float[] key = { 1.5f, 2.5f };
        float[] val = { 3.5f, 4.5f };

        cache.writeKV(blockId, 0, 0, 1, key, 0, val, 0);

        int offset = cache.getOffset(blockId, 0, 0, 1);
        assertEquals(1.5f, cache.getKeyData()[offset]);
        assertEquals(2.5f, cache.getKeyData()[offset + 1]);
        assertEquals(3.5f, cache.getValueData()[offset]);
        assertEquals(4.5f, cache.getValueData()[offset + 1]);
    }
}
