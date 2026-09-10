package ledger.core;

import java.math.BigDecimal;

/**
 * A single day's interest accrual for one account. NOT a LedgerEntry — accruals
 * are only bookkeeping until capitalization. The non-negotiable rule requires
 * "the rounded daily accruals must sum exactly to the capitalized total", so
 * {@code amount} here is always already rounded to the account currency's
 * scale, and the capitalized total is defined as the sum of these — never as
 * an independently-rounded sum of the unrounded daily figures. See NUMBERS.md.
 */
public record InterestAccrual(String accountId, int day, BigDecimal amount) {
}
