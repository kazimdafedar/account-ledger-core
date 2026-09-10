package ledger;

import ledger.core.AccountMeta;
import ledger.core.AuthorizationEvent;
import ledger.core.CreditEvent;
import ledger.core.Currency;
import ledger.core.HoldStatus;
import ledger.core.LedgerEngine;
import ledger.core.SettlementEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HoldsAndSettlementTest {

    private LedgerEngine newFundedEngine(String openingCredit) {
        LedgerEngine engine = new LedgerEngine(
                List.of(new AccountMeta("ACC-X", Currency.AED)),
                Map.of("ACC-X", new BigDecimal("0.00")));
        engine.process(new CreditEvent("E1", "ACC-X", 1, 1, new BigDecimal(openingCredit)));
        return engine;
    }

    @Test
    void authorizationApprovedWhenAvailableBalanceStaysNonNegative() {
        LedgerEngine engine = newFundedEngine("100.00");
        engine.process(new AuthorizationEvent("E2", "ACC-X", 1, 1, "Auth-1", new BigDecimal("100.00")));

        assertEquals(HoldStatus.APPROVED, engine.currentHoldState("Auth-1", 1).orElseThrow().status());
        // Ledger balance is untouched by a hold; only available balance changes.
        assertEquals(new BigDecimal("100.00"), engine.closingBalance("ACC-X", 1, 1));
    }

    @Test
    void authorizationDeclinedWhenItWouldPushAvailableBalanceNegative() {
        LedgerEngine engine = newFundedEngine("100.00");
        engine.process(new AuthorizationEvent("E2", "ACC-X", 1, 1, "Auth-1", new BigDecimal("100.01")));

        assertEquals(HoldStatus.DECLINED, engine.currentHoldState("Auth-1", 1).orElseThrow().status());
        assertEquals(BigDecimal.ZERO.setScale(2), engine.activeHoldsTotal("ACC-X", 1));
    }

    @Test
    void settlementAgainstUnknownAuthorizationIsRejectedAndMovesNoFunds() {
        LedgerEngine engine = newFundedEngine("100.00");
        engine.process(new SettlementEvent("E2", "ACC-X", 1, 1, "Auth-NEVER-EXISTED", new BigDecimal("10.00")));

        assertEquals(new BigDecimal("100.00"), engine.closingBalance("ACC-X", 1, 1));
        assertEquals(1, engine.errorsView().size());
        assertEquals(HoldStatus.REJECTED_UNKNOWN_AUTH,
                engine.currentHoldState("Auth-NEVER-EXISTED", 1).orElseThrow().status());
    }

    @Test
    void partialSettlementBelowHoldAmountReleasesTheFullHold() {
        LedgerEngine engine = newFundedEngine("100.00");
        engine.process(new AuthorizationEvent("E2", "ACC-X", 1, 1, "Auth-1", new BigDecimal("50.00")));
        engine.process(new SettlementEvent("E3", "ACC-X", 1, 1, "Auth-1", new BigDecimal("30.00")));

        assertEquals(new BigDecimal("70.00"), engine.closingBalance("ACC-X", 1, 1));
        assertEquals(HoldStatus.SETTLED, engine.currentHoldState("Auth-1", 1).orElseThrow().status());
        // Hold fully released, even though only 30 of the 50 held was settled.
        assertEquals(new BigDecimal("0.00"), engine.activeHoldsTotal("ACC-X", 1));
    }
}
