package ledger.core;

import java.math.BigDecimal;

/**
 * An immutable, appended record of one step in an authorization hold's
 * lifecycle (approved / declined / settled / rejected-unknown). The *current*
 * status of an authId is a projection (fold) over all HoldEvents for that
 * authId in append order — never a mutable field anywhere. This is what lets
 * us honor "no event record is ever mutated or deleted" while still supporting
 * holds whose status legitimately changes over time.
 */
public record HoldEvent(
        long seq,
        String authId,
        String accountId,
        HoldStatus status,
        BigDecimal amount,
        int day,
        String note) {
}
