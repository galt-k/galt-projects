package com.galt.llm.inference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RadixPrefixCache: Token-to-block prefix search index on top of PagedKVCache.
 * Enables zero-copy prefix sharing across multi-turn agent interactions and parallel branches.
 */
public class RadixPrefixCache {

    /**
     * Result of matching a token sequence against the radix cache.
     *
     * @param sharedPhysicalBlocks Array of physical block IDs from PagedKVCache matching the prefix.
     * @param cachedTokenCount     Total number of tokens matched (always a multiple of blockSize).
     */
    public record PrefixMatchResult(int[] sharedPhysicalBlocks, int cachedTokenCount) {}

    /**
     * Composite key for a block of tokens with array-based equality and hash code.
     */
    public record BlockKey(int[] tokens) {
        public BlockKey {
            tokens = tokens.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BlockKey other)) return false;
            return Arrays.equals(tokens, other.tokens);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(tokens);
        }
    }

    /**
     * Node in the Radix prefix tree representing one physical KV block of blockSize tokens.
     */
    public static class RadixNode {
        public final int[] blockTokens;
        public final int physicalBlockId;
        public int refCount;
        public long lastAccessedTimestamp;
        public final Map<BlockKey, RadixNode> children = new HashMap<>();
        public RadixNode parent;

        public RadixNode(int[] blockTokens, int physicalBlockId, RadixNode parent) {
            this.blockTokens = blockTokens;
            this.physicalBlockId = physicalBlockId;
            this.parent = parent;
            this.refCount = 0;
            this.lastAccessedTimestamp = System.nanoTime();
        }
    }

    private final int blockSize;
    private final RadixNode root;
    private final Map<Integer, RadixNode> blockIdToNode = new HashMap<>();

    public RadixPrefixCache(int blockSize) {
        this.blockSize = blockSize;
        this.root = new RadixNode(new int[0], -1, null);
    }

    public int getBlockSize() {
        return blockSize;
    }

    public RadixNode getRoot() {
        return root;
    }

    public int getNumCachedBlocks() {
        return blockIdToNode.size();
    }

    /**
     * Walks the tree to find the longest common prefix of full blocks for the given prompt tokens.
     * Increments refCount on all matched blocks and updates their lastAccessedTimestamp.
     *
     * @param tokenIds Array of prompt token IDs
     * @return PrefixMatchResult containing the matched physical block IDs and total cached tokens
     */
    public PrefixMatchResult matchPrefix(int[] tokenIds) {
        if (tokenIds == null || tokenIds.length < blockSize) {
            return new PrefixMatchResult(new int[0], 0);
        }

        List<RadixNode> matchedNodes = new ArrayList<>();
        RadixNode curr = root;
        int maxBlocks = tokenIds.length / blockSize;

        for (int b = 0; b < maxBlocks; b++) {
            int start = b * blockSize;
            int end = start + blockSize;
            int[] blockTokens = Arrays.copyOfRange(tokenIds, start, end);
            BlockKey key = new BlockKey(blockTokens);
            RadixNode child = curr.children.get(key);
            if (child == null) {
                break; // Divergence reached: stop walking
            }
            matchedNodes.add(child);
            curr = child;
        }

        // Invariant: Leave at least 1 delta token for forwardDelta()
        // If the entire prompt matched with 0 leftover tokens, leave the last block as delta
        if (!matchedNodes.isEmpty() && matchedNodes.size() * blockSize == tokenIds.length) {
            matchedNodes.remove(matchedNodes.size() - 1);
        }

        if (matchedNodes.isEmpty()) {
            return new PrefixMatchResult(new int[0], 0);
        }

        int[] sharedBlocks = new int[matchedNodes.size()];
        for (int i = 0; i < matchedNodes.size(); i++) {
            RadixNode node = matchedNodes.get(i);
            node.refCount++;
            node.lastAccessedTimestamp = System.nanoTime();
            sharedBlocks[i] = node.physicalBlockId;
        }

        return new PrefixMatchResult(sharedBlocks, matchedNodes.size() * blockSize);
    }

    /**
     * Inserts a sequence of full physical blocks corresponding to consecutive token blocks.
     *
     * @param tokenIds         Array of token IDs
     * @param physicalBlockIds Corresponding physical block IDs in PagedKVCache
     */
    public void insertBlockSequence(int[] tokenIds, int[] physicalBlockIds) {
        if (tokenIds == null || physicalBlockIds == null || physicalBlockIds.length == 0) {
            return;
        }
        RadixNode curr = root;
        for (int b = 0; b < physicalBlockIds.length; b++) {
            int start = b * blockSize;
            int end = start + blockSize;
            if (end > tokenIds.length) {
                break;
            }
            int[] blockTokens = Arrays.copyOfRange(tokenIds, start, end);
            BlockKey key = new BlockKey(blockTokens);
            RadixNode child = curr.children.get(key);
            if (child == null) {
                child = new RadixNode(blockTokens, physicalBlockIds[b], curr);
                curr.children.put(key, child);
                blockIdToNode.put(physicalBlockIds[b], child);
            }
            child.lastAccessedTimestamp = System.nanoTime();
            curr = child;
        }
    }

    /**
     * Decrements refCount for the specified physical blocks when a sequence completes or releases them.
     *
     * @param physicalBlockIds Physical block IDs to decrement refCount on
     */
    public void releaseSequence(int[] physicalBlockIds) {
        if (physicalBlockIds == null) {
            return;
        }
        for (int blockId : physicalBlockIds) {
            RadixNode node = blockIdToNode.get(blockId);
            if (node != null && node.refCount > 0) {
                node.refCount--;
            }
        }
    }

    /**
     * Evicts the least recently used leaf block with refCount == 0 from the tree,
     * frees it in kvCache, and returns the freed physicalBlockId.
     *
     * @param kvCache Physical KV cache to return the freed block to
     * @return The physicalBlockId that was freed, or -1 if no eligible unreferenced leaf exists
     */
    public int evictLRUBlock(PagedKVCache kvCache) {
        RadixNode victim = null;
        long oldestTime = Long.MAX_VALUE;

        for (RadixNode node : blockIdToNode.values()) {
            if (node.refCount == 0 && node.children.isEmpty()) {
                if (node.lastAccessedTimestamp < oldestTime) {
                    oldestTime = node.lastAccessedTimestamp;
                    victim = node;
                }
            }
        }

        if (victim == null) {
            return -1;
        }

        // Detach from parent
        if (victim.parent != null) {
            BlockKey victimKey = new BlockKey(victim.blockTokens);
            victim.parent.children.remove(victimKey);
        }

        // Remove from lookup map
        blockIdToNode.remove(victim.physicalBlockId);

        // Free physical block in PagedKVCache
        kvCache.freeBlock(victim.physicalBlockId);

        return victim.physicalBlockId;
    }
}
