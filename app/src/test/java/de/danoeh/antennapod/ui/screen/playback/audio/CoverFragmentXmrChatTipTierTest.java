package de.danoeh.antennapod.ui.screen.playback.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CoverFragmentXmrChatTipTierTest {
    @Test
    public void getTipMessageLengthWithoutTiersUsesFallback() {
        assertEquals(255, CoverFragment.getTipMessageLength(new BigDecimal("0.01"), Collections.emptyList()));
    }

    @Test
    public void getTipMessageLengthWithoutAmountUsesLowestTierMessageLengthCappedAtFallback() {
        List<CoverFragment.PageTipTier> tiers = Arrays.asList(
                tier("0.01", 500),
                tier("0.10", 50));

        assertEquals(50, CoverFragment.getTipMessageLength(null, tiers));
    }

    @Test
    public void getTipMessageLengthUsesHighestMatchingMinimumAmount() {
        List<CoverFragment.PageTipTier> tiers = Arrays.asList(
                tier("0.01", 50),
                tier("0.10", 500),
                tier("1.00", 1000));

        assertEquals(50, CoverFragment.getTipMessageLength(new BigDecimal("0.05"), tiers));
        assertEquals(500, CoverFragment.getTipMessageLength(new BigDecimal("0.10"), tiers));
        assertEquals(500, CoverFragment.getTipMessageLength(new BigDecimal("0.50"), tiers));
        assertEquals(1000, CoverFragment.getTipMessageLength(new BigDecimal("1.00"), tiers));
    }

    @Test
    public void getTipMessageLengthFallsBackWhenMatchingTierHasNoMessageLength() {
        List<CoverFragment.PageTipTier> tiers = Arrays.asList(
                tier("0.01", 50),
                tier("0.10", null));

        assertEquals(50, CoverFragment.getTipMessageLength(new BigDecimal("0.10"), tiers));
    }

    @Test
    public void isBelowMinimumReturnsFalseWhenAmountAtOrAboveMinimum() {
        BigDecimal minimum = new BigDecimal("0.001");
        assertEquals(false, CoverFragment.isBelowMinimum(new BigDecimal("0.001"), minimum));
        assertEquals(false, CoverFragment.isBelowMinimum(new BigDecimal("0.01"), minimum));
        assertEquals(false, CoverFragment.isBelowMinimum(new BigDecimal("1"), minimum));
    }

    @Test
    public void isBelowMinimumReturnsTrueWhenAmountBelowMinimum() {
        BigDecimal minimum = new BigDecimal("0.001");
        assertEquals(true, CoverFragment.isBelowMinimum(new BigDecimal("0.0009"), minimum));
        assertEquals(true, CoverFragment.isBelowMinimum(new BigDecimal("0.0001"), minimum));
    }

    @Test
    public void isBelowMinimumReturnsFalseWhenMinimumIsNull() {
        assertEquals(false, CoverFragment.isBelowMinimum(new BigDecimal("0.0001"), null));
    }

    @Test
    public void isBelowMinimumReturnsFalseWhenAmountIsNull() {
        assertEquals(false, CoverFragment.isBelowMinimum(null, new BigDecimal("0.001")));
    }

    @Test
    public void isBelowMinimumReturnsFalseWhenMinimumIsZeroOrNegative() {
        assertEquals(false, CoverFragment.isBelowMinimum(new BigDecimal("0.0001"), BigDecimal.ZERO));
        assertEquals(false, CoverFragment.isBelowMinimum(new BigDecimal("0.0001"), new BigDecimal("-1")));
    }

    private CoverFragment.PageTipTier tier(String minAmount, Integer messageLength) {
        return new CoverFragment.PageTipTier(null, null, new BigDecimal(minAmount), messageLength, null);
    }
}
