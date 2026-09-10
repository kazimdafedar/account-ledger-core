package ledger.core;

/**
 * Reverses a previously booked ledger entry, identified by its entry id (not
 * its originating event id — see {@link LedgerEngine} for how event ids map to
 * booked entry ids). The reversal books a brand-new offsetting entry; it never
 * touches, mutates, or removes the original entry (append-only rule).
 *
 * {@code valueDate} here is taken literally from the input (the brief pins E9's
 * value_date to Day 2, matching the reversed entry) rather than defaulting it
 * ourselves — see AMBIGUITIES.md ("reversal value_date default policy").
 */
public record ReversalEvent(
        String id, String accountId, int postedDay, int valueDate, String reversesEntryId)
        implements Event {
}
