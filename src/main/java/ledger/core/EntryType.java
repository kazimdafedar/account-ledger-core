package ledger.core;

/** Classification of a booked {@link LedgerEntry}, for reporting only — the
 *  actual balance effect is always just {@code signedAmount}. */
public enum EntryType {
    OPENING_BALANCE,
    CREDIT,
    DEBIT,
    SETTLEMENT,
    REVERSAL,
    FEE,
    INTEREST_CAPITALIZATION
}
