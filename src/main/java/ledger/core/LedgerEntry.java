package ledger.core;

import java.math.BigDecimal;

/**
 * An immutable, booked ledger entry. This is THE source of truth for balances.
 * Once appended to {@link LedgerEngine}'s entry log it is never mutated or
 * removed — corrections happen exclusively by appending a new offsetting entry
 * (see {@link ReversalEvent}), never by editing this record in place.
 *
 * @param seq          global monotonically increasing append-order sequence number
 * @param entryId      stable id for this booked entry (used as a reversal target)
 * @param sourceEventId the input event id that caused this booking (for traceability)
 * @param accountId    account this entry belongs to
 * @param type         classification, for reporting
 * @param signedAmount already rounded to the account currency's scale; credit-positive
 * @param postedDay    the day this entry was appended to the ledger (real time)
 * @param valueDate    the accounting day this entry is effective for
 * @param relatedId    authId (for SETTLEMENT) or reversed entryId (for REVERSAL); else null
 * @param note         free-text explanation, for the printed report
 */
public record LedgerEntry(
        long seq,
        String entryId,
        String sourceEventId,
        String accountId,
        EntryType type,
        BigDecimal signedAmount,
        int postedDay,
        int valueDate,
        String relatedId,
        String note) {
}
