package ledger.core;

/**
 * A raw input event, exactly as it arrives off the wire / from the replay script.
 * Events are immutable records — once constructed they are never changed. The
 * engine turns a subset of them into {@link LedgerEntry} bookings; some are
 * rejected outright and produce only an {@link ErrorRecord} (e.g. a settlement
 * against an unknown authorization).
 *
 * {@code postedDay} is the day the event physically enters the ledger (the day
 * it is replayed). {@code valueDate} is the accounting/effective day it belongs
 * to. They differ for back-dated events (E7, E9 in the scenario) — that gap is
 * the whole reason {@link LedgerEngine#closingBalance} takes two day arguments
 * instead of one. See AMBIGUITIES.md.
 */
public sealed interface Event
        permits CreditEvent, DebitEvent, AuthorizationEvent, SettlementEvent, ReversalEvent {

    String id();

    String accountId();

    int postedDay();

    int valueDate();
}
