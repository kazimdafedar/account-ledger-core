package ledger;

import ledger.core.Event;
import ledger.core.HoldStatus;
import ledger.core.LedgerEngine;
import ledger.core.ReversalEvent;
import ledger.replay.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Replays the EXACT six-day, ten-event scenario from the assessment brief and
 * pins down every number this exercise hinges on. Each assertion is annotated
 * with which acceptance criterion (if any) it demonstrates and whether that
 * criterion was accepted or rejected — the full reasoning for rejections lives
 * in REJECTED.md, this file is just the executable proof.
 */
class ScenarioReplayTest {

    private LedgerEngine engine;

    /** Day 6 closing balance for ACC-001 captured right after E9 is processed
     *  and Day 6's close has run, but BEFORE interest capitalization is booked
     *  (both land on postedDay/valueDate 6, so they can't be told apart by day
     *  alone — this field is what lets criterion 6's test isolate E9's effect
     *  from the unrelated 0.83 interest credit that also posts that day). */
    private BigDecimal acc001Day6BalanceBeforeCapitalization;

    @BeforeEach
    void replayScenario() {
        engine = new LedgerEngine(Scenario.accounts(), Scenario.openingBalances());

        Map<Integer, List<Event>> byDay = new LinkedHashMap<>();
        for (Event e : Scenario.eventsExceptReversal()) {
            byDay.computeIfAbsent(e.postedDay(), d -> new ArrayList<>()).add(e);
        }

        for (int day = 1; day <= 6; day++) {
            for (Event e : byDay.getOrDefault(day, List.of())) {
                engine.process(e);
            }
            if (day == 6) {
                String e7EntryId = engine.entriesView().stream()
                        .filter(en -> "E7".equals(en.sourceEventId())).findFirst().orElseThrow().entryId();
                engine.process(Scenario.reversalOfE7(e7EntryId));
            }
            engine.runDailyClose(day);
            if (day == 6) {
                acc001Day6BalanceBeforeCapitalization = engine.closingBalance(Scenario.ACC_001, 6, 6);
            }
        }
        engine.capitalizeInterest(6);
    }

    @Test
    void day1And2ClosingBalanceAsOfTheirOwnDayIs250() {
        assertEquals(new BigDecimal("250.00"), engine.closingBalance(Scenario.ACC_001, 1, 1));
        // E3 (Day 2) is an authorization hold, not a booked entry — it does not
        // move the ledger balance.
        assertEquals(new BigDecimal("250.00"), engine.closingBalance(Scenario.ACC_001, 2, 2));
    }

    @Test
    void day4ClosingBalanceReflectsAuth_A_SettlementOf185NotTheFull200Hold() {
        // Criterion 3 (ACCEPTED): the Day 4 settlement of Auth-A must be accepted.
        assertEquals(new BigDecimal("465.00"), engine.closingBalance(Scenario.ACC_001, 4, 4));
    }

    @Test
    void auth_Z_SettlementIsRejectedAndMovesNoFunds() {
        // Criterion 4 (ACCEPTED): settlement referencing an unknown auth id is
        // rejected and funds must not leave the account. 465.00 already accounts
        // for Auth-A's settlement; if Auth-Z's 180.00 had wrongly gone through,
        // this would be 285.00 instead.
        assertEquals(new BigDecimal("465.00"), engine.closingBalance(Scenario.ACC_001, 4, 4));
        assertEquals(1, engine.errorsView().stream()
                .filter(e -> e.eventId().equals("E6")).count());
        assertEquals(HoldStatus.REJECTED_UNKNOWN_AUTH, engine.currentHoldState("Auth-Z", 4).orElseThrow().status());
    }

    @Test
    void criterion1_day2ClosingBalanceRetrospectivelyAsOfDay5IsMinus370() {
        // Criterion 1 (ACCEPTED): "Day 2 closing ledger balance, evaluated at end
        // of Day 5 and before any fee is assessed, is AED -370.00."
        // 1200.00 - 950.00 - 620.00 (E7, value_date Day 2, posted Day 5) = -370.00.
        assertEquals(new BigDecimal("-370.00"), engine.closingBalance(Scenario.ACC_001, 2, 5));
    }

    @Test
    void criterion2_isRejected_theOverdraftFeeLandsOnDay5NotDay2() {
        // Criterion 2 (REJECTED — see REJECTED.md): "E7 causes exactly one
        // overdraft fee, assessed on Day 2." A fee can only be assessed on the
        // day its batch actually runs; Day 2's batch already ran (and found a
        // positive balance) before E7 ever existed. E7 instead drags DAY 5's own
        // closing balance negative (465.00 - 620.00 = -155.00), so the single fee
        // lands on Day 5, booked with value_date = Day 5.
        long feeCount = engine.entriesView().stream()
                .filter(e -> e.accountId().equals(Scenario.ACC_001))
                .filter(e -> e.type() == ledger.core.EntryType.FEE)
                .count();
        assertEquals(1, feeCount, "exactly one fee — this half of criterion 2 is correct");

        var fee = engine.entriesView().stream()
                .filter(e -> e.accountId().equals(Scenario.ACC_001))
                .filter(e -> e.type() == ledger.core.EntryType.FEE)
                .findFirst().orElseThrow();
        assertEquals(5, fee.postedDay(), "the fee is on Day 5, not Day 2 — this half of criterion 2 is wrong");
        assertEquals(5, fee.valueDate());
        assertEquals(new BigDecimal("-25.00"), fee.signedAmount());
    }

    @Test
    void criterion6_isRejected_balancesDoNotFullyReturnToPreE7ValuesAfterReversal() {
        // Criterion 6 (REJECTED — see REJECTED.md): "After E9, all balances and
        // fees return to their pre-E7 values." E9 only reverses E7 itself; the
        // 25.00 overdraft fee that E7 *caused* is a separate, independent
        // ledger entry and is never automatically reversed. Pre-E7, Day 4's
        // (and would-be Day 5's) balance was 465.00; post-E9 it is 440.00 — a
        // permanent 25.00 shortfall.
        BigDecimal postE9Balance = acc001Day6BalanceBeforeCapitalization;
        assertEquals(new BigDecimal("440.00"), postE9Balance);
        assertEquals(new BigDecimal("465.00").subtract(new BigDecimal("25.00")), postE9Balance);
    }

    @Test
    void auth_B_IsDeclinedNotMerelyUnsettled() {
        // The brief states "Auth-B is never settled inside the window" but does
        // not claim it was approved. By the time E8 is processed (Day 5, after
        // E7 has already posted), the account is already overdrawn (-155.00),
        // so a new 90.00 hold would push available balance to -245.00 — below
        // zero — and must be DECLINED per the non-negotiable authorization rule.
        assertEquals(HoldStatus.DECLINED, engine.currentHoldState("Auth-B", 5).orElseThrow().status());
        assertEquals(new BigDecimal("0.00"), engine.activeHoldsTotal(Scenario.ACC_001, 5));
    }

    @Test
    void criterion7_isRejected_theThreeBhdInstalmentsCannotAllBe3_334() {
        // Criterion 7 (REJECTED — see REJECTED.md): 3 x 3.334 = 10.002, which is
        // NOT 10.000. Our split is 3.333 / 3.333 / 3.334 (largest-remainder,
        // remainder to the last instalment), which sums exactly to 10.000.
        List<BigDecimal> instalments = engine.entriesView().stream()
                .filter(e -> e.accountId().equals(Scenario.ACC_002))
                .filter(e -> e.type() == ledger.core.EntryType.CREDIT)
                .map(ledger.core.LedgerEntry::signedAmount)
                .toList();
        assertEquals(List.of(new BigDecimal("3.333"), new BigDecimal("3.333"), new BigDecimal("3.334")), instalments);
        BigDecimal sum = instalments.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("10.000"), sum);
    }

    @Test
    void interestCapitalizationSumsExactlyToTheRoundedDailyAccrualsForBothAccounts() {
        // The non-negotiable rule: "rounded daily accruals must sum exactly to
        // the capitalized total" — never discarded, never independently
        // re-rounded (criterion 8 is rejected precisely because it proposes
        // discarding a remainder that, by construction, never arises here).
        BigDecimal acc001Capitalized = engine.entriesView().stream()
                .filter(e -> e.accountId().equals(Scenario.ACC_001))
                .filter(e -> e.type() == ledger.core.EntryType.INTEREST_CAPITALIZATION)
                .map(ledger.core.LedgerEntry::signedAmount).findFirst().orElseThrow();
        BigDecimal acc001AccrualSum = engine.accrualsView().stream()
                .filter(a -> a.accountId().equals(Scenario.ACC_001))
                .map(ledger.core.InterestAccrual::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(acc001AccrualSum, acc001Capitalized);
        assertEquals(new BigDecimal("0.83"), acc001Capitalized);

        BigDecimal acc002Capitalized = engine.entriesView().stream()
                .filter(e -> e.accountId().equals(Scenario.ACC_002))
                .filter(e -> e.type() == ledger.core.EntryType.INTEREST_CAPITALIZATION)
                .map(ledger.core.LedgerEntry::signedAmount).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.008"), acc002Capitalized);
    }
}
