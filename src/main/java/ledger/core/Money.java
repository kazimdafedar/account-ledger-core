package ledger.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding helper. Deliberately the only place in the codebase that calls
 * {@link BigDecimal#setScale}. Two decisions live here, both explained in
 * NUMBERS.md:
 *
 *   1. We use {@link BigDecimal} exclusively — never {@code double}/{@code float}
 *      — so there is no binary floating-point representation error to begin
 *      with, and therefore no "epsilon" fudge-constant anywhere in this codebase.
 *   2. Rounding mode is {@link RoundingMode#HALF_UP}. Not specified by the brief,
 *      so this is a chosen constant — see NUMBERS.md for why HALF_UP and not
 *      HALF_EVEN or truncation.
 */
public final class Money {

    private Money() {
    }

    public static BigDecimal round(BigDecimal amount, Currency currency) {
        return amount.setScale(currency.scale(), RoundingMode.HALF_UP);
    }
}
