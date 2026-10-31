package com.galt.llm.inference;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BranchSelectionTest {
    private final EntropyBranchScheduler scheduler = new EntropyBranchScheduler();

    @Test
    void choosesAverageScoreAndReleasesLosersWithoutFreeingWinnerPrefix() {
        PagedKVCache cache = new PagedKVCache(4,1,1,4,2);
        int prefix = cache.allocateBlock();
        int tail = cache.allocateBlock();
        BranchState loser = new BranchState(0,6,new int[]{prefix,tail,-1});
        loser.cumulativeLogProbability = -2.4;
        loser.scoredTokenCount = 4;
        BranchState winner = scheduler.forkBranch(loser,cache);
        scheduler.prepareForDecode(winner,cache);
        int winningTail = winner.blockTable[1];
        winner.cumulativeLogProbability = -3.0;
        winner.scoredTokenCount = 10; // -0.3 beats -0.6 despite a lower sum.

        assertSame(winner,scheduler.selectBestBranch(List.of(loser,winner),cache));
        assertArrayEquals(new int[]{-1,-1,-1},loser.blockTable);
        assertEquals(1,cache.getRefCount(prefix));
        assertEquals(1,cache.getRefCount(winningTail));
        assertEquals(0,cache.getRefCount(tail));
        assertEquals(2,cache.getNumFreeBlocks());
    }

    @Test
    void tieKeepsFirstAndReleasesPreparedButUndecodedBlock() {
        PagedKVCache cache = new PagedKVCache(2,1,1,4,2);
        BranchState winner = new BranchState(0,0,new int[]{-1});
        winner.scoredTokenCount = 1;
        BranchState loser = scheduler.forkBranch(winner,cache);
        scheduler.prepareForDecode(loser,cache);
        int prepared = loser.blockTable[0];
        assertEquals(0,loser.nextDecodePosition);

        assertSame(winner,scheduler.selectBestBranch(List.of(winner,loser),cache));
        assertEquals(0,cache.getRefCount(prepared));
        assertEquals(2,cache.getNumFreeBlocks());
        assertArrayEquals(new int[]{-1},loser.blockTable);
    }

    @Test
    void validatesAllCandidatesBeforeReleasingAnything() {
        PagedKVCache cache = new PagedKVCache(2,1,1,4,2);
        int block = cache.allocateBlock();
        BranchState valid = new BranchState(0,1,new int[]{block});
        valid.scoredTokenCount = 1;
        BranchState unscored = new BranchState(0,0,new int[]{-1});
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.selectBestBranch(List.of(valid,unscored),cache));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.selectBestBranch(List.of(valid,valid),cache));
        assertThrows(IllegalArgumentException.class,
            () -> scheduler.selectBestBranch(List.of(),cache));
        BranchState unretained = new BranchState(0,1,new int[]{block});
        unretained.scoredTokenCount = 1;
        assertThrows(IllegalStateException.class,
            () -> scheduler.selectBestBranch(List.of(valid,unretained),cache));
        assertEquals(1,cache.getRefCount(block));
        assertEquals(block,valid.blockTable[0]);
    }
}
