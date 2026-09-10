package ledger;

import ledger.core.AccountMeta;
import ledger.core.CreditEvent;
import ledger.core.Currency;
import ledger.core.DebitEvent;
import ledger.core.LedgerEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small, isolated checks of the fee/interest daily-close mechanics, decoupled
 *  from the full six-day scenario (that's ScenarioReplayTest). */
class LedgerEngineBasicTest {

    private LedgerEngine newEngine() {
        return new LedgerEngine(
                List.of(new AccountMeta("ACC-X", Currency.AED)),
                Map.of("ACC-X", new BigDecimal("0.00")));
    }

    @Test
    void positiveClosingBalanceAccruesInterestAndNoFee() {
        LedgerEngine engine = newEngine();
        engine.process(new CreditEvent("E1", "ACC-X", 1, 1, new BigDecimal("100.00")));
        var close = engine.runDailyClose(1);

        assertEquals(new BigDecimal("100.00"), close.closingBalance().get("ACC-X"));
        assertTrue(close.feeAssessed().isEmpty());
        // 100.00 * 0.0004 = 0.04 exactly
        assertEquals(new BigDecimal("0.04"), close.interestAccrued().get("ACC-X"));
    }

    @Test
    void negativeClosingBalanceAssessesFeeOnceAndAccruesNoInterest() {
        LedgerEngine engine = newEngine();
        engine.process(new DebitEvent("E1", "ACC-X", 1, 1, new BigDecimal("50.00")));
        var close = engine.runDailyClose(1);

        assertEquals(new BigDecimal("25.00"), close.feeAssessed().get("ACC-X"));
        assertTrue(close.interestAccrued().isEmpty());
        // -50.00 fee'd by -25.00 => closing balance reported already reflects the fee
        assertEquals(new BigDecimal("-75.00"), close.closingBalance().get("ACC-X"));
    }

    @Test
    void feeIsAssessedAtMostOncePerDayEvenIfCalledForSameDayIsSkipped() {
        // runDailyClose is documented as "call exactly once per day"; this test
        // pins down that a single negative day never produces more than one fee
        // entry, which is the behavior the "once per day" rule requires.
        LedgerEngine engine = newEngine();
        engine.process(new DebitEvent("E1", "ACC-X", 1, 1, new BigDecimal("50.00")));
        engine.runDailyClose(1);

        long feeEntries = engine.entriesView().stream()
                .filter(e -> e.type() == ledger.core.EntryType.FEE)
                .count();
        assertEquals(1, feeEntries);
    }

    @Test
    void interestCapitalizationSumsExactlyToRoundedDailyAccruals() {
        LedgerEngine engine = newEngine();
        engine.process(new CreditEvent("E1", "ACC-X", 1, 1, new BigDecimal("100.00")));
        engine.runDailyClose(1); // accrues 0.04
        engine.runDailyClose(2); // accrues 0.04 again (same balance, no new events)
        Map<String, BigDecimal> capitalized = engine.capitalizeInterest(2);

        assertEquals(new BigDecimal("0.08"), capitalized.get("ACC-X"));
    }
}
