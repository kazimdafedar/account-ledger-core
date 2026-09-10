package ledger.core;

/** Static metadata for an account: which currency it's denominated in. Opening
 *  balance is handled by {@link LedgerEngine} at construction (booked as an
 *  {@code OPENING_BALANCE} entry at day 0, only if non-zero). */
public record AccountMeta(String accountId, Currency currency) {
}
