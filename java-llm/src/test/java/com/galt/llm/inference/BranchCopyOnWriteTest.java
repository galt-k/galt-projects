package com.galt.llm.inference;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BranchCopyOnWriteTest {
    private final EntropyBranchScheduler scheduler = new EntropyBranchScheduler();

    @Test
    void copiesAllLayersAndHeadsAndIsolatesChildWrites() {
        PagedKVCache cache = new PagedKVCache(4, 2, 2, 4, 2);
        int full = cache.allocateBlock();
        int tail = cache.allocateBlock();
        for (int layer = 0; layer < 2; layer++) {
            for (int head = 0; head < 2; head++) {
                for (int slot = 0; slot < 2; slot++) {
                    float x = 100 * layer + 10 * head + slot;
                    cache.writeKV(tail, layer, head, slot,
                            new float[]{x, x + 1}, 0, new float[]{-x, -x - 1}, 0);
                }
            }
        }
        BranchState parent = new BranchState(2, 6, new int[]{full, tail, -1});
        BranchState child = scheduler.forkBranch(parent, cache);
        scheduler.prepareForDecode(child, cache);
        int privateTail = child.blockTable[1];
        assertNotEquals(tail, privateTail);
        assertEquals(full, child.blockTable[0]);
        assertEquals(2, cache.getRefCount(full));
        assertEquals(1, cache.getRefCount(tail));
        assertEquals(1, cache.getRefCount(privateTail));
        assertEquals(6, child.nextDecodePosition);
        for (int layer = 0; layer < 2; layer++) {
            for (int head = 0; head < 2; head++) {
                for (int slot = 0; slot < 2; slot++) {
                    int source = cache.getOffset(tail, layer, head, slot);
                    int copy = cache.getOffset(privateTail, layer, head, slot);
                    for (int dim = 0; dim < 2; dim++) {
                        assertEquals(cache.getKeyData()[source + dim], cache.getKeyData()[copy + dim]);
                        assertEquals(cache.getValueData()[source + dim], cache.getValueData()[copy + dim]);
                    }
                }
            }
        }
        int oldOffset = cache.getOffset(tail, 0, 0, 2);
        cache.writeKV(privateTail, 0, 0, 2, new float[]{99, 98}, 0, new float[]{97, 96}, 0);
        assertEquals(0.0f, cache.getKeyData()[oldOffset]);
        assertEquals(0.0f, cache.getValueData()[oldOffset]);
        int free = cache.getNumFreeBlocks();
        scheduler.prepareForDecode(child, cache);
        scheduler.prepareForDecode(parent, cache);
        assertEquals(free, cache.getNumFreeBlocks());
        assertEquals(tail, parent.blockTable[1]);
    }

    @Test
    void allocatesAtBoundaryWithoutCopyingFullPrefix() {
        PagedKVCache cache = new PagedKVCache(3, 1, 1, 4, 2);
        int prefix = cache.allocateBlock();
        BranchState parent = new BranchState(0, 4, new int[]{prefix, -1});
        BranchState child = scheduler.forkBranch(parent, cache);
        scheduler.prepareForDecode(child, cache);
        assertEquals(prefix, child.blockTable[0]);
        assertEquals(2, cache.getRefCount(prefix));
        assertNotEquals(-1, child.blockTable[1]);
        assertEquals(-1, parent.blockTable[1]);
        assertEquals(1, cache.getRefCount(child.blockTable[1]));
    }

    @Test
    void exhaustionLeavesSharedTailAndReferencesUnchanged() {
        PagedKVCache cache = new PagedKVCache(1, 1, 1, 4, 2);
        int tail = cache.allocateBlock();
        BranchState child = scheduler.forkBranch(new BranchState(0, 2, new int[]{tail}), cache);
        assertThrows(IllegalStateException.class, () -> scheduler.prepareForDecode(child, cache));
        assertEquals(tail, child.blockTable[0]);
        assertEquals(2, cache.getRefCount(tail));
    }

    @Test
    void rejectsMissingTailAndInsufficientTableCapacity() {
        PagedKVCache cache = new PagedKVCache(1, 1, 1, 4, 2);
        assertThrows(IllegalStateException.class,
                () -> scheduler.prepareForDecode(new BranchState(0, 2, new int[]{-1}), cache));
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.prepareForDecode(new BranchState(0, 4, new int[]{-1}), cache));
        assertEquals(1, cache.getNumFreeBlocks());
    }
}
