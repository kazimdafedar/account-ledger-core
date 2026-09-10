package ledger.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The in-memory account ledger core.
 *
 * Everything is event-sourced: {@link #entries}, {@link #holdEvents} and
 * {@link #errors} are the only sources of truth, and they are strictly
 * append-only (package-private mutable {@link ArrayList}s, but nothing outside
 * this class ever gets a mutable reference to them — see the {@code ...View()}
 * accessors, which return {@link Collections#unmodifiableList}). Every other
 * question ("what's the balance", "is this hold active", "what happened today")
 * is answered by folding over those logs, never by reading a cached mutable
 * field.
 *
 * Two-day-parameter balance query — the central design decision of this
 * whole exercise (see AMBIGUITIES.md and REJECTED.md for the full reasoning):
 *
 *   closingBalance(account, bucketDay, asOfDay)
 *
 *   - bucketDay: the accounting day whose balance we want (filters by
 *     value_date <= bucketDay).
 *   - asOfDay: how much of the ledger existed yet (filters by
 *     posted_day <= asOfDay).
 *
 *   Daily fee/interest assessment always calls this with bucketDay == asOfDay
 *   == "today", run exactly once per day, in day order, and NEVER re-run for a
 *   past day. Retrospective reporting queries (e.g. "what did Day 2 look like
 *   as of Day 5") call it with bucketDay < asOfDay. Same formula, two different
 *   questions.
 */
public final class LedgerEngine {

    /** AED 25.00 — given verbatim by the brief. BHD deliberately has no entry:
     *  the brief never defines an overdraft fee for BHD, and no BHD account
     *  ever goes negative in this scenario, so we don't invent one. If a BHD
     *  account did go negative, {@link #runDailyClose} logs an ErrorRecord
     *  instead of silently skipping or silently guessing at a number. See
     *  AMBIGUITIES.md ("overdraft fee currency scope"). */
    public static final Map<Currency, BigDecimal> OVERDRAFT_FEE_BY_CURRENCY =
            Map.of(Currency.AED, new BigDecimal("25.00"));

    /** 0.04% per day, given verbatim by the brief. */
    public static final BigDecimal DAILY_INTEREST_RATE = new BigDecimal("0.0004");

    private final Map<String, AccountMeta> accounts = new LinkedHashMap<>();
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final List<HoldEvent> holdEvents = new ArrayList<>();
    private final List<ErrorRecord> errors = new ArrayList<>();
    private final List<InterestAccrual> accruals = new ArrayList<>();

    private long seq = 0;

    public LedgerEngine(List<AccountMeta> accountMetas, Map<String, BigDecimal> openingBalances) {
        for (AccountMeta meta : accountMetas) {
            accounts.put(meta.accountId(), meta);
            BigDecimal opening = openingBalances.getOrDefault(meta.accountId(), BigDecimal.ZERO);
            BigDecimal rounded = Money.round(opening, meta.currency());
            if (rounded.compareTo(BigDecimal.ZERO) != 0) {
                append(new LedgerEntry(
                        nextSeq(), entryId("OPEN", meta.accountId()), "OPENING", meta.accountId(),
                        EntryType.OPENING_BALANCE, rounded, 0, 0, null, "opening balance"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Event processing (append-only mutation happens only through here)
    // ------------------------------------------------------------------

    /** Process one input event. Must be called in the exact order the events
     *  were replayed — the engine has no notion of "re-processing" an event. */
    public void process(Event event) {
        switch (event) {
            case CreditEvent c -> bookUnconditional(c.id(), c.accountId(), EntryType.CREDIT,
                    c.amount(), c.postedDay(), c.valueDate(), null, "credit");
            case DebitEvent d -> bookUnconditional(d.id(), d.accountId(), EntryType.DEBIT,
                    d.amount().negate(), d.postedDay(), d.valueDate(), null, "debit");
            case AuthorizationEvent a -> processAuthorization(a);
            case SettlementEvent s -> processSettlement(s);
            case ReversalEvent r -> processReversal(r);
        }
    }

    private void bookUnconditional(String eventId, String accountId, EntryType type,
                                    BigDecimal signedAmount, int postedDay, int valueDate,
                                    String relatedId, String note) {
        AccountMeta meta = requireAccount(accountId);
        BigDecimal rounded = Money.round(signedAmount, meta.currency());
        append(new LedgerEntry(nextSeq(), entryId(type.name(), eventId), eventId, accountId,
                type, rounded, postedDay, valueDate, relatedId, note));
    }

    private void processAuthorization(AuthorizationEvent a) {
        AccountMeta meta = requireAccount(a.accountId());
        BigDecimal holdAmount = Money.round(a.holdAmount(), meta.currency());

        BigDecimal ledgerBalanceNow = closingBalance(a.accountId(), a.postedDay(), a.postedDay());
        BigDecimal activeHolds = activeHoldsTotal(a.accountId(), a.postedDay());
        BigDecimal available = ledgerBalanceNow.subtract(activeHolds);
        BigDecimal availableAfterHold = available.subtract(holdAmount);

        if (availableAfterHold.compareTo(BigDecimal.ZERO) >= 0) {
            holdEvents.add(new HoldEvent(nextSeq(), a.authId(), a.accountId(), HoldStatus.APPROVED,
                    holdAmount, a.postedDay(),
                    "approved: ledger=%s, priorHolds=%s, available=%s, availableAfterHold=%s"
                            .formatted(ledgerBalanceNow, activeHolds, available, availableAfterHold)));
        } else {
            holdEvents.add(new HoldEvent(nextSeq(), a.authId(), a.accountId(), HoldStatus.DECLINED,
                    holdAmount, a.postedDay(),
                    "declined: ledger=%s, priorHolds=%s, available=%s, availableAfterHold=%s (< 0)"
                            .formatted(ledgerBalanceNow, activeHolds, available, availableAfterHold)));
        }
    }

    private void processSettlement(SettlementEvent s) {
        AccountMeta meta = requireAccount(s.accountId());
        Optional<HoldEvent> current = currentHoldState(s.authId(), s.postedDay());

        if (current.isEmpty()) {
            errors.add(new ErrorRecord(nextSeq(), s.postedDay(), s.id(),
                    "settlement references unknown authorization id '%s' — rejected, funds not moved"
                            .formatted(s.authId())));
            holdEvents.add(new HoldEvent(nextSeq(), s.authId(), s.accountId(),
                    HoldStatus.REJECTED_UNKNOWN_AUTH, Money.round(s.settleAmount(), meta.currency()),
                    s.postedDay(), "no prior authorization event for this id"));
            return;
        }

        HoldEvent priorState = current.get();
        if (priorState.status() != HoldStatus.APPROVED) {
            errors.add(new ErrorRecord(nextSeq(), s.postedDay(), s.id(),
                    "settlement references authorization '%s' which is not active (status=%s) — rejected"
                            .formatted(s.authId(), priorState.status())));
            return;
        }

        BigDecimal settleAmount = Money.round(s.settleAmount(), meta.currency());
        // NOTE: no check that settleAmount <= priorState.amount() here — see the
        // documented gap in SettlementEvent's javadoc and the failing test.
        bookUnconditional(s.id(), s.accountId(), EntryType.SETTLEMENT, settleAmount.negate(),
                s.postedDay(), s.valueDate(), s.authId(),
                "settles " + s.authId() + " (held " + priorState.amount() + ")");
        holdEvents.add(new HoldEvent(nextSeq(), s.authId(), s.accountId(), HoldStatus.SETTLED,
                settleAmount, s.postedDay(), "settled for " + settleAmount));
    }

    private void processReversal(ReversalEvent r) {
        Optional<LedgerEntry> original = entries.stream()
                .filter(e -> e.entryId().equals(r.reversesEntryId()))
                .findFirst();

        if (original.isEmpty()) {
            errors.add(new ErrorRecord(nextSeq(), r.postedDay(), r.id(),
                    "reversal references unknown ledger entry id '%s' — rejected".formatted(r.reversesEntryId())));
            return;
        }

        LedgerEntry orig = original.get();
        append(new LedgerEntry(nextSeq(), entryId("REV", r.id()), r.id(), r.accountId(),
                EntryType.REVERSAL, orig.signedAmount().negate(), r.postedDay(), r.valueDate(),
                orig.entryId(), "reverses " + orig.entryId()));
    }

    // ------------------------------------------------------------------
    // Daily batch: fee assessment + interest accrual (run once per day, in
    // order, and never re-run for a day already closed).
    // ------------------------------------------------------------------

    public record DayCloseResult(int day, Map<String, BigDecimal> closingBalance,
                                  Map<String, BigDecimal> feeAssessed,
                                  Map<String, BigDecimal> interestAccrued) {
    }

    /** Must be called exactly once, immediately after all events whose
     *  postedDay == day have been {@link #process}ed, and strictly in
     *  increasing day order. This is what makes fee/interest assessment a
     *  one-shot batch rather than a retroactively-recomputable query. */
    public DayCloseResult runDailyClose(int day) {
        Map<String, BigDecimal> closing = new LinkedHashMap<>();
        Map<String, BigDecimal> feeAssessed = new LinkedHashMap<>();
        Map<String, BigDecimal> interestAccrued = new LinkedHashMap<>();

        for (AccountMeta meta : accounts.values()) {
            BigDecimal preFeeBalance = closingBalance(meta.accountId(), day, day);

            if (preFeeBalance.compareTo(BigDecimal.ZERO) < 0) {
                BigDecimal fee = OVERDRAFT_FEE_BY_CURRENCY.get(meta.currency());
                if (fee == null) {
                    errors.add(new ErrorRecord(nextSeq(), day, "DAY-" + day + "-CLOSE-" + meta.accountId(),
                            "account is overdrawn (balance=" + preFeeBalance + ") but no overdraft fee is "
                                    + "defined for currency " + meta.currency() + " — no fee assessed"));
                } else {
                    bookUnconditional("FEE-DAY" + day, meta.accountId(), EntryType.FEE, fee.negate(),
                            day, day, null,
                            "overdraft fee: closing balance on day " + day + " was " + preFeeBalance);
                    feeAssessed.put(meta.accountId(), fee);
                }
            }

            // Interest basis: the closing balance AFTER any fee just booked today.
            // This is equivalent to using the pre-fee balance for the purposes of
            // this exercise, because fee only fires when the balance is negative
            // and interest only fires when it's positive — they are mutually
            // exclusive on any given day. See AMBIGUITIES.md.
            BigDecimal basis = closingBalance(meta.accountId(), day, day);
            closing.put(meta.accountId(), basis);

            if (basis.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal raw = basis.multiply(DAILY_INTEREST_RATE);
                BigDecimal rounded = Money.round(raw, meta.currency());
                if (rounded.compareTo(BigDecimal.ZERO) != 0) {
                    accruals.add(new InterestAccrual(meta.accountId(), day, rounded));
                    interestAccrued.put(meta.accountId(), rounded);
                }
            }
        }

        return new DayCloseResult(day, closing, feeAssessed, interestAccrued);
    }

    /** Capitalizes all accrued interest for every account as a single credit,
     *  booked with value_date == postedDay == {@code day} (intended to be
     *  called once, at the end of the window, i.e. day 6). The capitalized
     *  amount is defined as the sum of the already-rounded daily accruals —
     *  never as a fresh, independent rounding of the sum of unrounded daily
     *  figures — which is exactly what guarantees "rounded daily accruals sum
     *  exactly to the capitalized total" per the non-negotiable rule. */
    public Map<String, BigDecimal> capitalizeInterest(int day) {
        Map<String, BigDecimal> capitalized = new LinkedHashMap<>();
        for (AccountMeta meta : accounts.values()) {
            BigDecimal total = accruals.stream()
                    .filter(acc -> acc.accountId().equals(meta.accountId()))
                    .map(InterestAccrual::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            total = Money.round(total, meta.currency()); // no-op: already same scale; documents intent
            if (total.compareTo(BigDecimal.ZERO) != 0) {
                bookUnconditional("INTEREST-CAP-DAY" + day, meta.accountId(),
                        EntryType.INTEREST_CAPITALIZATION, total, day, day, null,
                        "capitalization of " + accruals.stream()
                                .filter(acc -> acc.accountId().equals(meta.accountId())).count()
                                + " daily accrual(s)");
                capitalized.put(meta.accountId(), total);
            }
        }
        return capitalized;
    }

    // ------------------------------------------------------------------
    // Projections (pure reads — fold over the append-only logs)
    // ------------------------------------------------------------------

    /** Closing ledger balance for {@code accountId}, restricted to entries with
     *  {@code valueDate <= bucketDay}, considering only entries that had been
     *  posted by {@code asOfDay}. See the class javadoc for what this means. */
    public BigDecimal closingBalance(String accountId, int bucketDay, int asOfDay) {
        AccountMeta meta = requireAccount(accountId);
        BigDecimal total = entries.stream()
                .filter(e -> e.accountId().equals(accountId))
                .filter(e -> e.valueDate() <= bucketDay)
                .filter(e -> e.postedDay() <= asOfDay)
                .map(LedgerEntry::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Money.round(total, meta.currency());
    }

    /** Sum of hold amounts currently APPROVED (i.e. active, not yet settled /
     *  never declined) for {@code accountId}, as of {@code asOfDay}. */
    public BigDecimal activeHoldsTotal(String accountId, int asOfDay) {
        AccountMeta meta = requireAccount(accountId);
        Map<String, HoldEvent> latestByAuthId = new LinkedHashMap<>();
        for (HoldEvent he : holdEvents) {
            if (!he.accountId().equals(accountId) || he.day() > asOfDay) {
                continue;
            }
            latestByAuthId.put(he.authId(), he); // append order => last write wins
        }
        BigDecimal total = latestByAuthId.values().stream()
                .filter(he -> he.status() == HoldStatus.APPROVED)
                .map(HoldEvent::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Money.round(total, meta.currency());
    }

    /** Latest hold-lifecycle state for {@code authId} as of {@code asOfDay},
     *  or empty if that authId has never been seen by then. */
    public Optional<HoldEvent> currentHoldState(String authId, int asOfDay) {
        HoldEvent latest = null;
        for (HoldEvent he : holdEvents) {
            if (he.authId().equals(authId) && he.day() <= asOfDay) {
                latest = he;
            }
        }
        return Optional.ofNullable(latest);
    }

    public List<LedgerEntry> entriesView() {
        return Collections.unmodifiableList(entries);
    }

    public List<HoldEvent> holdEventsView() {
        return Collections.unmodifiableList(holdEvents);
    }

    public List<ErrorRecord> errorsView() {
        return Collections.unmodifiableList(errors);
    }

    public List<InterestAccrual> accrualsView() {
        return Collections.unmodifiableList(accruals);
    }

    public Map<String, AccountMeta> accountsView() {
        return Collections.unmodifiableMap(accounts);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void append(LedgerEntry entry) {
        entries.add(entry); // the ONLY place entries is mutated; never removed, never edited in place
    }

    private long nextSeq() {
        return ++seq;
    }

    private String entryId(String prefix, String suffix) {
        return prefix + "-" + suffix + "-" + seq;
    }

    private AccountMeta requireAccount(String accountId) {
        AccountMeta meta = accounts.get(accountId);
        if (meta == null) {
            throw new IllegalArgumentException("unknown account: " + accountId);
        }
        return meta;
    }
}
