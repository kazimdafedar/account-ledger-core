package ledger;

import ledger.core.Currency;
import ledger.core.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneyRoundingTest {

    @Test
    void aedRoundsToTwoDecimalPlaces() {
        assertEquals(new BigDecimal("0.19"), Money.round(new BigDecimal("0.186"), Currency.AED));
        assertEquals(new BigDecimal("0.10"), Money.round(new BigDecimal("0.1"), Currency.AED));
    }

    @Test
    void bhdRoundsToThreeDecimalPlaces() {
        assertEquals(new BigDecimal("0.004"), Money.round(new BigDecimal("0.0040"), Currency.BHD));
        assertEquals(new BigDecimal("3.334"), Money.round(new BigDecimal("3.3335"), Currency.BHD));
    }

    @Test
    void halfUpRoundingIsUsedConsistently() {
        // 0.005 at 2dp is exactly the halfway point; HALF_UP rounds away from
        // zero. Documented as a chosen constant in NUMBERS.md.
        assertEquals(new BigDecimal("0.01"), Money.round(new BigDecimal("0.005"), Currency.AED));
        assertEquals(new BigDecimal("-0.01"), Money.round(new BigDecimal("-0.005"), Currency.AED));
    }
}
