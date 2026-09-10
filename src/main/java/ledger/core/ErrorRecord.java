package ledger.core;

/** An immutable, appended record of a rejected event. Rejection never mutates
 *  or deletes anything that already exists in the ledger — it simply means no
 *  new LedgerEntry gets appended for that input event. */
public record ErrorRecord(long seq, int day, String eventId, String reason) {
}
