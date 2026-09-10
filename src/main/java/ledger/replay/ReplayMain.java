package ledger.replay;

import ledger.core.AccountMeta;
import ledger.core.Event;
import ledger.core.HoldEvent;
import ledger.core.LedgerEngine;
import ledger.core.LedgerEngine.DayCloseResult;
import ledger.core.LedgerEntry;
import ledger.core.ErrorRecord;
import ledger.core.ReversalEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runnable script: replays the exact six-day, ten-event scenario from the
 * assessment brief through {@link LedgerEngine} and prints, per day: closing
 * ledger balance, fee assessments, authorization states, and errors — plus
 * interest accrual/capitalization (central to the design) and a handful of
 * explicit acceptance-criteria checks at the end.
 *
 * Run with: {@code mvn -q compile exec:java}  (see README.md)
 */
public final class ReplayMain {

    private ReplayMain() {
    }

    public static void main(String[] args) {
        LedgerEngine engine = new LedgerEngine(Scenario.accounts(), Scenario.openingBalances());

        // Group E1..E8 + E10 by postedDay, preserving relative order within a day.
        Map<Integer, List<Event>> byDay = new LinkedHashMap<>();
        for (Event e : Scenario.eventsExceptReversal()) {
            byDay.computeIfAbsent(e.postedDay(), d -> new ArrayList<>()).add(e);
        }

        printHeader();

        for (int day = 1; day <= 6; day++) {
            List<Event> todaysEvents = byDay.getOrDefault(day, List.of());

            for (Event e : todaysEvents) {
                engine.process(e);
                if (e.id().equals("E7")) {
                    // Print the exact entry id E7 was booked as, since E9 needs it and
                    // it's not knowable ahead of time (see Scenario javadoc).
                    String e7EntryId = engine.entriesView().stream()
                            .filter(en -> "E7".equals(en.sourceEventId()))
                            .findFirst().orElseThrow().entryId();
                    System.out.println("  [info] E7 booked as ledger entry '" + e7EntryId
                            + "' (this is the id E9's reversal targets)");
                }
            }

            if (day == 6) {
                // E9 is applied here, after E1-E8/E10 are all in, because it needs
                // E7's runtime entry id. It is still processed strictly before
                // day 6's close (fee/interest assessment), which is what matters.
                String e7EntryId = engine.entriesView().stream()
                        .filter(en -> "E7".equals(en.sourceEventId()))
                        .findFirst().orElseThrow().entryId();
                ReversalEvent e9 = Scenario.reversalOfE7(e7EntryId);
                engine.process(e9);
                System.out.println("  [info] E9 processed: reverses " + e7EntryId + ", value_date=Day2");
            }

            DayCloseResult close = engine.runDailyClose(day);
            printDayReport(engine, day, todaysEvents, close);
        }

        Map<String, BigDecimal> capitalized = engine.capitalizeInterest(6);
        System.out.println();
        System.out.println("=== Interest capitalization (single credit, end of Day 6) ===");
        for (AccountMeta meta : engine.accountsView().values()) {
            BigDecimal cap = capitalized.getOrDefault(meta.accountId(), BigDecimal.ZERO);
            BigDecimal sumOfAccruals = engine.accrualsView().stream()
                    .filter(a -> a.accountId().equals(meta.accountId()))
                    .map(ledger.core.InterestAccrual::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            System.out.printf("  %-8s capitalized=%-10s  sum-of-rounded-daily-accruals=%-10s  match=%s%n",
                    meta.accountId(), cap, sumOfAccruals, cap.compareTo(sumOfAccruals) == 0 || sumOfAccruals.compareTo(BigDecimal.ZERO)==0 && cap.compareTo(BigDecimal.ZERO)==0);
        }

        printFinalState(engine);
        printAcceptanceCriteriaChecks(engine);
    }

    private static void printHeader() {
        System.out.println("################################################################");
        System.out.println("  In-Memory Account Ledger Core — six-day scenario replay");
        System.out.println("################################################################");
    }

    private static void printDayReport(LedgerEngine engine, int day, List<Event> todaysEvents, DayCloseResult close) {
        System.out.println();
        System.out.println("================ Day " + day + " ================");
        System.out.println("Events posted today: " + (todaysEvents.isEmpty() ? "(none)" :
                todaysEvents.stream().map(Event::id).reduce((a, b) -> a + ", " + b).orElse("")));

        System.out.println("-- Closing ledger balance (value_date <= " + day + ", as booked through Day " + day + ") --");
        for (AccountMeta meta : engine.accountsView().values()) {
            System.out.printf("  %-8s %s %s%n", meta.accountId(), meta.currency(), close.closingBalance().get(meta.accountId()));
        }

        System.out.println("-- Fee assessments --");
        if (close.feeAssessed().isEmpty()) {
            System.out.println("  (none)");
        } else {
            close.feeAssessed().forEach((acc, amt) -> System.out.printf("  %-8s overdraft fee assessed: %s%n", acc, amt));
        }

        System.out.println("-- Interest accrued today (not yet capitalized) --");
        if (close.interestAccrued().isEmpty()) {
            System.out.println("  (none)");
        } else {
            close.interestAccrued().forEach((acc, amt) -> System.out.printf("  %-8s +%s%n", acc, amt));
        }

        System.out.println("-- Authorization state changes today --");
        List<HoldEvent> todaysHolds = engine.holdEventsView().stream().filter(h -> h.day() == day).toList();
        if (todaysHolds.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (HoldEvent h : todaysHolds) {
                System.out.printf("  %-8s %-30s %-24s amount=%-10s %s%n",
                        h.accountId(), h.authId(), h.status(), h.amount(), h.note());
            }
        }

        System.out.println("-- Errors today --");
        List<ErrorRecord> todaysErrors = engine.errorsView().stream().filter(er -> er.day() == day).toList();
        if (todaysErrors.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (ErrorRecord er : todaysErrors) {
                System.out.printf("  event=%-6s %s%n", er.eventId(), er.reason());
            }
        }
    }

    private static void printFinalState(LedgerEngine engine) {
        System.out.println();
        System.out.println("=== Full ledger entry log (append-only, in booking order) ===");
        for (LedgerEntry e : engine.entriesView()) {
            System.out.printf("  seq=%-3d %-8s %-24s %-10s postedDay=%d valueDate=%d %s%n",
                    e.seq(), e.accountId(), e.type(), e.signedAmount(), e.postedDay(), e.valueDate(), e.note());
        }

        System.out.println();
        System.out.println("=== Full authorization hold log (append-only) ===");
        for (HoldEvent h : engine.holdEventsView()) {
            System.out.printf("  seq=%-3d day=%d %-8s %-12s %-24s amount=%s%n",
                    h.seq(), h.day(), h.accountId(), h.authId(), h.status(), h.amount());
        }

        System.out.println();
        System.out.println("=== Full error log ===");
        for (ErrorRecord er : engine.errorsView()) {
            System.out.printf("  seq=%-3d day=%d event=%-6s %s%n", er.seq(), er.day(), er.eventId(), er.reason());
        }
    }

    private static void printAcceptanceCriteriaChecks(LedgerEngine engine) {
        System.out.println();
        System.out.println("=== Selected acceptance-criteria demonstrations ===");
        System.out.println("(Full analysis of every criterion, including the ones rejected as wrong, is in REJECTED.md)");

        BigDecimal day2AsOfDay5 = engine.closingBalance(Scenario.ACC_001, 2, 5);
        System.out.println("  Criterion 1: Day 2 closing balance, evaluated at end of Day 5 = " + day2AsOfDay5
                + "  (expected -370.00, ACCEPTED as correct)");

        long overdraftFeeCountAcc001 = engine.entriesView().stream()
                .filter(e -> e.accountId().equals(Scenario.ACC_001))
                .filter(e -> e.type() == ledger.core.EntryType.FEE)
                .count();
        List<Integer> feeDays = engine.entriesView().stream()
                .filter(e -> e.accountId().equals(Scenario.ACC_001))
                .filter(e -> e.type() == ledger.core.EntryType.FEE)
                .map(LedgerEntry::postedDay)
                .toList();
        System.out.println("  Criterion 2 (REJECTED, see REJECTED.md): overdraft fees on ACC-001 = " + overdraftFeeCountAcc001
                + ", assessed on day(s) " + feeDays + "  (brief claims Day 2; actually Day 5 — fee assessment can't be backdated)");

        BigDecimal day5BalanceAfterE9 = engine.closingBalance(Scenario.ACC_001, 5, 6);
        System.out.println("  Criterion 6 (REJECTED, see REJECTED.md): Day 5 closing balance re-evaluated after E9 = "
                + day5BalanceAfterE9 + "  (pre-E7 value was 465.00 — the 25.00 fee is NOT auto-reversed by E9)");

        Map<String, HoldEvent> authBFinal = new LinkedHashMap<>();
        engine.holdEventsView().stream().filter(h -> h.authId().equals("Auth-B"))
                .forEach(h -> authBFinal.put(h.authId(), h));
        System.out.println("  Auth-B final state: " + authBFinal.get("Auth-B").status()
                + " (declined at authorization time because the account was already overdrawn from E7 — see AMBIGUITIES.md)");
    }
}
