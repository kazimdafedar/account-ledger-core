package ledger;

import ledger.core.AccountMeta;
import ledger.core.CreditEvent;
import ledger.core.Currency;
import ledger.core.DebitEvent;
import ledger.core.LedgerEngine;
import ledger.core.ReversalEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReversalTest {

    @Test
    void reversalAppendsAnOffsettingEntryAndNeverMutatesTheOriginal() {
        LedgerEngine engine = new LedgerEngine(
                List.of(new AccountMeta("ACC-X", Currency.AED)),
                Map.of("ACC-X", new BigDecimal("0.00")));
        engine.process(new CreditEvent("E1", "ACC-X", 1, 1, new BigDecimal("100.00")));
        engine.process(new DebitEvent("E2", "ACC-X", 2, 2, new BigDecimal("60.00")));

        String e2EntryId = engine.entriesView().stream()
                .filter(e -> "E2".equals(e.sourceEventId())).findFirst().orElseThrow().entryId();
        var originalBeforeReversal = engine.entriesView().stream()
                .filter(e -> e.entryId().equals(e2EntryId)).findFirst().orElseThrow();

        engine.process(new ReversalEvent("E3", "ACC-X", 3, 2, e2EntryId));

        var originalAfterReversal = engine.entriesView().stream()
                .filter(e -> e.entryId().equals(e2EntryId)).findFirst().orElseThrow();
        assertEquals(originalBeforeReversal, originalAfterReversal, "original entry must be byte-for-byte unchanged");

        // 2 booked entries + 1 reversal entry = 3, nothing removed.
        assertEquals(3, engine.entriesView().size());
        assertEquals(new BigDecimal("100.00"), engine.closingBalance("ACC-X", 3, 3));
    }

    @Test
    void reversalOfUnknownEntryIdIsRejectedWithAnError() {
        LedgerEngine engine = new LedgerEngine(
                List.of(new AccountMeta("ACC-X", Currency.AED)),
                Map.of("ACC-X", new BigDecimal("0.00")));
        engine.process(new ReversalEvent("E1", "ACC-X", 1, 1, "NO-SUCH-ENTRY"));

        assertTrue(engine.entriesView().isEmpty());
        assertEquals(1, engine.errorsView().size());
    }
}
