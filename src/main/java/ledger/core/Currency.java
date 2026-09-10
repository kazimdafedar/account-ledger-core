package ledger.core;

/**
 * The only two currencies in scope for this exercise. Each currency carries its
 * own minor-unit scale (decimal places) — this is a non-negotiable rule from the
 * brief ("AED is 2 decimal places, BHD is 3. Amounts stored and rounded to their
 * own precision.").
 *
 * A currency-keyed map (see {@link LedgerEngine#OVERDRAFT_FEE_BY_CURRENCY}) is used
 * anywhere a rule is currency-specific, rather than hard-coding "the" fee/precision
 * as a global. See AMBIGUITIES.md ("overdraft fee currency scope") for why this
 * matters even though only one currency ever goes negative in this scenario.
 */
public enum Currency {
    AED(2),
    BHD(3);

    private final int scale;

    Currency(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }
}
