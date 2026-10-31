package com.galt.llm.inference;

import com.galt.llm.SamplingUtils;
import com.galt.llm.Tensor;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;

public class EntropyBranchScheduler {
    /** Uses a default budget of at most 64 candidate leaves, including completed leaves. */
    public BranchState generateWithBranching(int[] prompt, CachedGPTModel model,
            FSMlogitMask masker, PagedKVCache cache, int eosTokenId,
            int maxOutputTokens, double thresholdBits, int branchWidth) {
        return generateWithBranching(prompt, model, masker, cache, eosTokenId,
                maxOutputTokens, thresholdBits, branchWidth, 64);
    }

    /**
     * Checks uncertainty at every token selection on every continuation. Each
     * uncertain branch explores up to branchWidth distinct tokens, subject to a
     * global maxBranches leaf budget. Once the budget is consumed, selections
     * proceed greedily. Candidates run sequentially with shared-prefix KV and COW.
     * Returns the best average-log-probability leaf; the caller releases its blocks
     * using releaseBranch. All owned references are released on failure. Output
     * limits include EOS; budget-limited leaves may be incomplete. Uses fresh
     * prefill and never takes ownership of prefix-cache blocks.
     */
    public BranchState generateWithBranching(int[] prompt, CachedGPTModel model,
            FSMlogitMask masker, PagedKVCache cache, int eosTokenId,
            int maxOutputTokens, double thresholdBits, int branchWidth, int maxBranches) {
        InferenceConfig config = model.config();
        if (prompt == null || prompt.length == 0 || maxOutputTokens <= 0
                || branchWidth < 1 || maxBranches < 1 || !Double.isFinite(thresholdBits) || thresholdBits < 0) {
            throw new IllegalArgumentException("Invalid prompt, output limit, threshold, or branch width");
        }
        long slots = (long) prompt.length + maxOutputTokens - 1;
        if (slots > config.contextLength() || eosTokenId < 0 || eosTokenId >= config.vocabSize()) {
            throw new IllegalArgumentException("Context limit exceeded or EOS outside vocabulary");
        }
        if (cache.getBlockSize() != config.blockSize() || cache.getNumLayers() != config.numLayers()
                || cache.getNumKvHeads() != config.numKvHeads() || cache.getHeadDim() != config.headDim()) {
            throw new IllegalArgumentException("Cache dimensions do not match model");
        }
        for (int token : prompt) {
            if (token < 0 || token >= config.vocabSize()) {
                throw new IllegalArgumentException("Prompt token outside vocabulary");
            }
        }
        int[] table = new int[(int) ((slots + cache.getBlockSize() - 1) / cache.getBlockSize())];
        Arrays.fill(table, -1);
        BranchState root = new BranchState(0, prompt.length, table);
        List<BranchState> candidates = new ArrayList<>();
        candidates.add(root);
        try {
            int promptBlocks = (prompt.length + cache.getBlockSize() - 1) / cache.getBlockSize();
            for (int index = 0; index < promptBlocks; index++) {
                root.blockTable[index] = cache.allocateBlock();
            }
            Tensor scores = model.prefill(new int[][]{prompt}, root.blockTable, cache);
            java.util.ArrayDeque<PendingSelection> pending = new java.util.ArrayDeque<>();
            pending.addLast(new PendingSelection(root,
                    vocabularyRow(scores, prompt.length - 1, config.vocabSize())));
            while (!pending.isEmpty()) {
                PendingSelection work = pending.removeFirst();
                BranchState parent = work.branch();
                float[] logits = work.logits();
                masker.applyMaskInPlace(logits, parent.currentGrammarState);
                float[] probabilities = probabilitiesFromLogits(logits);
                double entropy = entropyBits(probabilities);
                varentropyBitsSquare(probabilities);
                int width = entropy > thresholdBits
                        ? Math.min(branchWidth, maxBranches - candidates.size() + 1) : 1;
                int[] choices = topTokens(probabilities, width);
                List<BranchState> children = new ArrayList<>();
                children.add(parent);
                // Fork before recording selections: every child starts at the same prefix.
                for (int index = 1; index < choices.length; index++) {
                    BranchState child = forkBranch(parent, cache);
                    candidates.add(child);
                    children.add(child);
                }
                for (int index = 0; index < choices.length; index++) {
                    BranchState branch = children.get(index);
                    int selected = choices[index];
                    recordSelectedToken(branch, selected, probabilities[selected], masker);
                    if (selected == eosTokenId || branch.outputTokenIds.size() == maxOutputTokens) {
                        continue;
                    }
                    prepareForDecode(branch, cache);
                    Tensor nextScores = model.decodeStep(new int[][]{{selected}},
                            branch.nextDecodePosition, branch.blockTable, cache);
                    branch.nextDecodePosition++;
                    pending.addLast(new PendingSelection(branch,
                            vocabularyRow(nextScores, 0, config.vocabSize())));
                }
            }
            return selectBestBranch(candidates, cache);
        } catch (RuntimeException failure) {
            for (BranchState branch : candidates) {
                releaseBranch(branch, cache);
            }
            throw failure;
        }
    }

    private record PendingSelection(BranchState branch, float[] logits) {}

    private float[] vocabularyRow(Tensor scores, int row, int vocabSize) {
        float[] logits = new float[vocabSize];
        for (int token = 0; token < vocabSize; token++) {
            logits[token] = scores.get(0, row, token);
        }
        return logits;
    }

    private int[] topTokens(float[] probabilities, int width) {
        int[] choices = new int[Math.min(width, probabilities.length)];
        boolean[] used = new boolean[probabilities.length];
        int count = 0;
        while (count < choices.length) {
            int best = -1;
            for (int token = 0; token < probabilities.length; token++) {
                if (!used[token] && probabilities[token] > 0
                        && (best == -1 || probabilities[token] > probabilities[best])) {
                    best = token;
                }
            }
            if (best == -1) break;
            choices[count++] = best;
            used[best] = true;
        }
        return Arrays.copyOf(choices, count);
    }

    /** Releases all references owned by this branch, including prepared blocks. */
    public void releaseBranch(BranchState branch, PagedKVCache cache) {
        for (int index = 0; index < branch.blockTable.length; index++) {
            int block = branch.blockTable[index];
            if (block != -1) {
                cache.freeBlock(block);
                branch.blockTable[index] = -1;
            }
        }
    }

    /**
     * Keeps the first highest-scoring candidate and consumes all losers.
     * Each nonnegative block-table entry must represent one owned reference;
     * -1 denotes an unallocated or released entry. Includes prepared blocks
     * even when a decode failure has left nextDecodePosition unchanged.
     * Losing branches must not be used again. Ownership changes are serialized.
     */
    public BranchState selectBestBranch(List<BranchState> branches, PagedKVCache cache) {
        if (branches == null || branches.isEmpty()) {
            throw new IllegalArgumentException("Provide at least one candidate branch");
        }
        Set<BranchState> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] candidateOwners = new int[cache.getNumBlocks()];
        BranchState winner = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        // Validate every candidate before modifying ownership.
        for (BranchState branch : branches) {
            if (branch == null || !seen.add(branch)) {
                throw new IllegalArgumentException("Candidates must be nonnull and distinct");
            }
            double score = scoreBranch(branch.cumulativeLogProbability, branch.scoredTokenCount);
            if (Double.isNaN(score) || score > 0.0) {
                throw new IllegalArgumentException("Invalid branch log-probability score");
            }
            Set<Integer> branchBlocks = new HashSet<>();
            for (int block : branch.blockTable) {
                if (block == -1) continue;
                if (block < 0 || block >= candidateOwners.length || !branchBlocks.add(block)) {
                    throw new IllegalArgumentException("Invalid or duplicate branch block mapping");
                }
                candidateOwners[block]++;
            }
            if (winner == null || score > bestScore) {
                winner = branch;
                bestScore = score;
            }
        }
        for (int block = 0; block < candidateOwners.length; block++) {
            if (candidateOwners[block] > cache.getRefCount(block)) {
                throw new IllegalStateException("Candidate ownership exceeds references for block " + block);
            }
        }
        for (BranchState branch : branches) {
            if (branch == winner) continue;
            for (int index = 0; index < branch.blockTable.length; index++) {
                int block = branch.blockTable[index];
                if (block != -1) {
                    cache.freeBlock(block);
                    branch.blockTable[index] = -1;
                }
            }
        }
        return winner;
    }

    /**
     * Continues a branch greedily, without creating further forks. On entry the
     * last output token must be selected but not yet decoded. maxOutputTokens
     * caps the total output list length, including tokens selected before entry.
     * EOS and the last budget-limited token are left outside the KV cache.
     * The caller retains ownership of the branch and its blocks, including on failure.
     */
    public void rolloutBranch(BranchState branch, CachedGPTModel model,
                              FSMlogitMask masker, PagedKVCache cache,
                              int eosTokenId, int maxOutputTokens) {
        InferenceConfig config = model.config();
        if (branch.outputTokenIds.isEmpty()) {
            throw new IllegalArgumentException("Rollout requires an already selected token");
        }
        if (maxOutputTokens < branch.outputTokenIds.size()) {
            throw new IllegalArgumentException("Output limit is smaller than existing output");
        }
        if (eosTokenId < 0 || eosTokenId >= config.vocabSize()) {
            throw new IllegalArgumentException("EOS token outside vocabulary");
        }
        float[] logits = new float[config.vocabSize()];
        while (branch.outputTokenIds.size() < maxOutputTokens) {
            int selected = branch.outputTokenIds.get(branch.outputTokenIds.size() - 1);
            if (selected == eosTokenId) {
                return;
            }
            if (selected < 0 || selected >= config.vocabSize()) {
                throw new IllegalArgumentException("Selected token outside vocabulary");
            }
            if (branch.nextDecodePosition >= config.contextLength()) {
                throw new IllegalStateException("Branch exceeds model context length");
            }
            prepareForDecode(branch, cache);
            Tensor scores = model.decodeStep(new int[][]{{selected}},
                    branch.nextDecodePosition, branch.blockTable, cache);
            branch.nextDecodePosition++;
            for (int token = 0; token < logits.length; token++) {
                logits[token] = scores.get(0, 0, token);
            }
            masker.applyMaskInPlace(logits, branch.currentGrammarState);
            float[] probabilities = probabilitiesFromLogits(logits);
            int nextToken = SamplingUtils.findMaxIndex(probabilities);
            recordSelectedToken(branch, nextToken, probabilities[nextToken], masker);
        }
    }

    /** Records a selection, without decoding it or advancing the KV position. */
    public void recordSelectedToken(BranchState branch, int tokenId,
                                    float probability, FSMlogitMask masker) {
        if (!Float.isFinite(probability) || probability <= 0.0f || probability > 1.0f) {
            throw new IllegalArgumentException("Selected token probability must be finite and in (0, 1]");
        }
        int nextState = masker.nextState(branch.currentGrammarState, tokenId);
        branch.outputTokenIds.add(tokenId);
        branch.cumulativeLogProbability += Math.log(probability);
        branch.scoredTokenCount++;
        branch.currentGrammarState = nextState;
    }

    /** Stable softmax at temperature 1; preserves the input and maps -infinity to zero. */
    public float[] probabilitiesFromLogits(float[] maskedLogits) {
        if (maskedLogits == null || maskedLogits.length == 0) {
            throw new IllegalArgumentException("Provide a nonempty logits array");
        }
        float max = Float.NEGATIVE_INFINITY;
        for (float logit : maskedLogits) {
            if (Float.isNaN(logit) || logit == Float.POSITIVE_INFINITY) {
                throw new IllegalArgumentException("Logits must be finite or negative infinity");
            }
            max = Math.max(max, logit);
        }
        if (max == Float.NEGATIVE_INFINITY) {
            throw new IllegalArgumentException("At least one legal finite logit is required");
        }
        double sum = 0.0;
        for (float logit : maskedLogits) {
            sum += Math.exp((double) logit - max);
        }
        float[] probabilities = new float[maskedLogits.length];
        for (int token = 0; token < maskedLogits.length; token++) {
            probabilities[token] = (float) (Math.exp((double) maskedLogits[token] - max) / sum);
        }
        return probabilities;
    }

    public double entropyBits(float[] probabilities){
        double accumulator = 0;
        for(int index = 0; index < probabilities.length; index++){
            if(probabilities[index]!= 0){
                accumulator += (-probabilities[index] * Math.log(probabilities[index]) / Math.log(2.0));
            }
        }
        return accumulator;
    }

    public double varentropyBitsSquare(float[] probabilities) {
        //calcualte entropy using entropyBits
        double entropy = entropyBits(probabilities);
        double accumulator = 0.0;
        double surprise;
        for (int index = 0; index < probabilities.length; index++) {
            if(probabilities[index]!=0){
                //calcualte the surprise
                surprise = -Math.log(probabilities[index]) / Math.log(2.0);
                accumulator += (probabilities[index] * (surprise-entropy)*(surprise-entropy));
            }
        }
        return accumulator;
    }

    public boolean shouldBranch(float[] probabilities, double thresholdBits){
        return entropyBits(probabilities) > thresholdBits;
    }

    public double scoreBranch(double cumulativeLogProbability, int generatedTokenCount){
        if(generatedTokenCount <=0){
            throw new IllegalArgumentException("invalid token count");
        }
        return cumulativeLogProbability/generatedTokenCount;
    }

    /**
     * Prepares the block for the next decode write without advancing the position.
     * Unallocated table entries must be -1. Calls and subsequent writes are serialized.
     */
    public void prepareForDecode(BranchState branch, PagedKVCache cache) {
        int logicalBlock = branch.nextDecodePosition / cache.getBlockSize();
        int slot = branch.nextDecodePosition % cache.getBlockSize();
        if (logicalBlock >= branch.blockTable.length) {
            throw new IllegalArgumentException("Branch block table has no room for the next token");
        }
        int oldBlock = branch.blockTable[logicalBlock];
        if (oldBlock == -1) {
            if (slot != 0) {
                throw new IllegalStateException("Partial tail block is missing");
            }
            branch.blockTable[logicalBlock] = cache.allocateBlock();
            return;
        }
        int owners = cache.getRefCount(oldBlock);
        if (owners == 0) {
            throw new IllegalStateException("Branch references a free block");
        }
        if (owners == 1) {
            return;
        }

        int privateBlock = cache.allocateBlock();
        try {
            if (slot != 0) {
                cache.copyBlock(oldBlock, privateBlock);
            }
        } catch (RuntimeException failure) {
            cache.freeBlock(privateBlock);
            throw failure;
        }
        branch.blockTable[logicalBlock] = privateBlock;
        cache.freeBlock(oldBlock);
    }

    public BranchState forkBranch(BranchState parent, PagedKVCache cache){
        // nextDecodePosition is the number of tokens already stored in KV.
        int blockCount = (int) (((long) parent.nextDecodePosition
                + cache.getBlockSize() - 1) / cache.getBlockSize());
        if (blockCount > parent.blockTable.length) {
            throw new IllegalArgumentException("Parent block table does not cover its cached tokens");
        }

        BranchState child = new BranchState(parent.currentGrammarState,
                parent.nextDecodePosition, parent.blockTable);
        child.outputTokenIds.addAll(parent.outputTokenIds);
        child.cumulativeLogProbability = parent.cumulativeLogProbability;
        child.scoredTokenCount = parent.scoredTokenCount;

        int retainedCount = 0;
        try {
            for (; retainedCount < blockCount; retainedCount++) {
                cache.retainBlock(child.blockTable[retainedCount]);
            }
        } catch (RuntimeException failure) {
            // Undo only the references acquired by this unfinished fork.
            for (int index = 0; index < retainedCount; index++) {
                cache.freeBlock(child.blockTable[index]);
            }
            throw failure;
        }
        // Unoccupied table entries are not owned by the child. They must be
        // allocated before use; zero would otherwise look like a valid block ID.
        java.util.Arrays.fill(child.blockTable, blockCount, child.blockTable.length, -1);
        return child;
    }


}
