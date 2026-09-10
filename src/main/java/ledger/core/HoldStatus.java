package ledger.core;

/** Lifecycle states for an authorization hold. There is deliberately no
 *  "RELEASED" state distinct from SETTLED: in this design, settlement (even a
 *  partial one) fully releases whatever hold amount remains — see
 *  AMBIGUITIES.md ("under-settlement release semantics"). */
public enum HoldStatus {
    APPROVED,
    DECLINED,
    SETTLED,
    /** Settlement was attempted against an authId the ledger has never seen. */
    REJECTED_UNKNOWN_AUTH
}
