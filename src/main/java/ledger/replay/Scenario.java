package ledger.replay;

import ledger.core.AccountMeta;
import ledger.core.AuthorizationEvent;
import ledger.core.CreditEvent;
import ledger.core.Currency;
import ledger.core.DebitEvent;
import ledger.core.Event;
import ledger.core.ReversalEvent;
import ledger.core.SettlementEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The exact six-day, ten-event scenario from the assessment brief, expressed
 * as {@link Event} objects. This is the ONLY file that encodes the scenario's
 * specific numbers/days — everything else (engine, reporting) is generic.
 *
 * One thing this file can NOT do statically: E9 ("reverses E7") needs the
 * *booked entry id* that E7 produced, which only exists once E7 has actually
 * been processed by the engine (see {@link ReplayMain}). So this class exposes
 * the events up to E8 as a fixed prefix, and a factory method for E9 once the
 * caller knows E7's resulting entry id, plus E10 (independent of E9).
 */
public final class Scenario {

    public static final String ACC_001 = "ACC-001"; // AED
    public static final String ACC_002 = "ACC-002"; // BHD

    private Scenario() {
    }

    public static List<AccountMeta> accounts() {
        return List.of(
                new AccountMeta(ACC_001, Currency.AED),
                new AccountMeta(ACC_002, Currency.BHD));
    }

    public static Map<String, BigDecimal> openingBalances() {
        return Map.of(
                ACC_001, new BigDecimal("0.00"),
                ACC_002, new BigDecimal("0.000"));
    }

    /** E1 through E8, plus E10 (the ACC-002 instalments) — everything except
     *  E9, which requires E7's runtime-assigned entry id. Listed in the exact
     *  order given in the brief: E1..E8, then E10 (dated Day 5), matching the
     *  brief's own event numbering. E9 (Day 6) is applied separately by
     *  {@link ReplayMain} once E7's entry id is known — see AMBIGUITIES.md
     *  ("E9/E10 relative replay order") for why this reordering is safe. */
    public static List<Event> eventsExceptReversal() {
        List<Event> events = new ArrayList<>();

        events.add(new CreditEvent("E1", ACC_001, 1, 1, new BigDecimal("1200.00")));
        events.add(new DebitEvent("E2", ACC_001, 1, 1, new BigDecimal("950.00")));
        events.add(new AuthorizationEvent("E3", ACC_001, 2, 2, "Auth-A", new BigDecimal("200.00")));
        events.add(new CreditEvent("E4", ACC_001, 3, 3, new BigDecimal("400.00")));
        events.add(new SettlementEvent("E5", ACC_001, 4, 4, "Auth-A", new BigDecimal("185.00")));
        events.add(new SettlementEvent("E6", ACC_001, 4, 4, "Auth-Z", new BigDecimal("180.00")));
        events.add(new DebitEvent("E7", ACC_001, 5, 2, new BigDecimal("620.00")));
        events.add(new AuthorizationEvent("E8", ACC_001, 5, 5, "Auth-B", new BigDecimal("90.00")));
        events.addAll(instalmentsE10());

        return events;
    }

    /**
     * E10: "ACC-002 BHD 10.000, posted as three equal instalments". 10.000 / 3
     * is not exact at 3 decimal places (3.333 repeating), so "equal" cannot be
     * taken literally — see NUMBERS.md and REJECTED.md (criterion about all
     * three being 3.334, which is rejected because it overshoots the total).
     * Resolution: largest-remainder method — floor every instalment to
     * BHD 3.333, then hand the single leftover 0.001 to the LAST instalment.
     */
    private static List<Event> instalmentsE10() {
        BigDecimal total = new BigDecimal("10.000");
        BigDecimal base = new BigDecimal("3.333");
        BigDecimal remainder = total.subtract(base.multiply(new BigDecimal(3))); // 0.001
        return List.of(
                new CreditEvent("E10a", ACC_002, 5, 5, base),
                new CreditEvent("E10b", ACC_002, 5, 5, base),
                new CreditEvent("E10c", ACC_002, 5, 5, base.add(remainder)));
    }

    /** E9: "reverses E7" with value_date pinned to Day 2 by the brief. Needs
     *  E7's actual booked entry id, supplied by the caller after processing E7. */
    public static ReversalEvent reversalOfE7(String e7EntryId) {
        return new ReversalEvent("E9", ACC_001, 6, 2, e7EntryId);
    }
}
