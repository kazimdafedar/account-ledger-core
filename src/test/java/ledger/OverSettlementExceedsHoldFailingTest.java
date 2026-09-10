package ledger;

import ledger.core.AccountMeta;
import ledger.core.AuthorizationEvent;
import ledger.core.CreditEvent;
import ledger.core.Currency;
import ledger.core.LedgerEngine;
import ledger.core.SettlementEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ============================== INTENTIONALLY FAILING TEST ==============================
 *
 * This test is left FAILING on purpose (do not "fix" the assertion to match
 * current behavior — that would defeat the point). It is the one deliverable
 * required by the brief: "one failing test against your own design,
 * inline-annotated with what it reveals."
 *
 * WHAT IT TESTS
 *   E5 in the scenario settles Auth-A (held AED 200.00) for AED 185.00 — an
 *   UNDER-settlement, which is clearly fine (185 <= 200) and is exactly what
 *   the brief exercises. But the brief never specifies what should happen for
 *   an OVER-settlement: a SETTLEMENT event whose amount exceeds the amount
 *   that was actually held for that authId.
 *
 * WHAT MY CURRENT DESIGN ACTUALLY DOES
 *   LedgerEngine.processSettlement() (see SettlementEvent's javadoc for the
 *   same admission) validates only that the authId exists and is still
 *   APPROVED. It does NOT compare settleAmount against the hold's amount, so
 *   it books the full settleAmount unconditionally — even if that's more than
 *   was ever held. In card-network terms this is the permissive "settlement is
 *   authoritative" interpretation (the acquirer/network's later settlement
 *   figure always wins over the earlier estimate placed at authorization
 *   time), which is a legitimate real-world model.
 *
 * WHAT THIS TEST ASSERTS INSTEAD
 *   The stricter, arguably more defensible interpretation for an in-memory
 *   ledger CORE that has no downstream network to trust blindly: a settlement
 *   can never move more money than was actually held, so an over-settlement
 *   should be REJECTED (like the unknown-auth-id case in E6), with an
 *   ErrorRecord and zero ledger impact.
 *
 * WHY IT'S LEFT FAILING RATHER THAN "FIXED"
 *   Both interpretations are defensible and the brief is silent on this exact
 *   point (see AMBIGUITIES.md, "settlement amount vs hold amount mismatch").
 *   Picking one silently and hiding the disagreement felt worse than
 *   surfacing it as a red test: this is the one place in the design where I
 *   was not confident enough to commit an opinion, and a failing test is an
 *   honest way to say so instead of a comment nobody will read. See also
 *   REJECTED.md, "approaches abandoned mid-build".
 */
class OverSettlementExceedsHoldFailingTest {

    @Test
    void settlementForMoreThanTheHeldAmountShouldBeRejected_butCurrentlyIsNot() {
        LedgerEngine engine = new LedgerEngine(
                List.of(new AccountMeta("ACC-X", Currency.AED)),
                Map.of("ACC-X", new BigDecimal("0.00")));
        engine.process(new CreditEvent("E1", "ACC-X", 1, 1, new BigDecimal("1000.00")));
        engine.process(new AuthorizationEvent("E2", "ACC-X", 1, 1, "Auth-1", new BigDecimal("200.00")));

        // Settle for MORE than the 200.00 that was actually held.
        engine.process(new SettlementEvent("E3", "ACC-X", 1, 1, "Auth-1", new BigDecimal("250.00")));

        // EXPECTED (the behavior this test argues for): rejected, no funds move,
        // balance stays at 1000.00, and an error is recorded — exactly like E6's
        // unknown-auth rejection.
        assertEquals(new BigDecimal("1000.00"), engine.closingBalance("ACC-X", 1, 1),
                "an over-settlement should be rejected and move no funds, but the current "
                        + "engine books it anyway, producing 750.00 instead of 1000.00");
        assertEquals(1, engine.errorsView().size(),
                "an over-settlement should be recorded as an error, but the current engine "
                        + "accepts it silently and logs nothing");
    }
}
