# AMBIGUITIES.md

Every place the brief left a genuine choice, and exactly how I resolved it.
Each item names the resolution and points at the code/test that encodes it,
so "how I resolved it" is checkable, not just asserted.

## 1. "Closing ledger balance for day D" is not one number — it depends on *when you ask*

This is the central ambiguity of the whole exercise, and criteria 1 and 2 in
the brief only make sense together once you see it.

- **The question:** does "day D's closing balance" mean (a) what the ledger
  showed at the moment day D's own processing finished, using only entries
  posted by then — or (b) what the ledger shows *right now*, restricted to
  entries whose `value_date <= D`, no matter when they were actually posted?
- These give different answers as soon as a back-dated entry exists. E7 posts
  on Day 5 with `value_date = Day 2`. Reading (a), Day 2's balance is a fixed
  historical fact (250.00, decided on Day 2, before E7 existed) that nothing
  can change afterwards. Reading (b) says Day 2's balance, queried today, is
  −370.00, because E7 belongs to Day 2's bucket by value_date and it now
  exists.
- **Resolution:** both readings are real and both are needed, so the API
  takes two separate day arguments instead of collapsing them into one:
  `closingBalance(accountId, bucketDay, asOfDay)`.
  - Daily fee/interest assessment (`runDailyClose(day)`) always uses
    `bucketDay == asOfDay == day`, called exactly once per day, in day order,
    and is never re-run for a day that has already closed. This is reading (a).
  - Retrospective/report queries (e.g. "Day 2 as of Day 5") pass
    `bucketDay=2, asOfDay=5`. This is reading (b).
- This is also why criterion 2 ("E7 causes a fee on Day 2") is wrong even
  though criterion 1 ("Day 2's balance as of Day 5 is −370.00") is right — see
  REJECTED.md.

## 2. Does a plain DEBIT/CREDIT need an available-balance check before booking?

The only rule about balance checks in the brief is specifically about
authorizations ("An authorization is approved only if the account's available
balance ... remains at or above zero"). It says nothing about plain
credits/debits/settlements.

**Resolution:** no — plain ledger movements (CREDIT, DEBIT, SETTLEMENT,
REVERSAL) are always booked unconditionally, even if they drive the balance
negative. This is consistent with the fact that the brief defines an
*overdraft fee* at all: if plain debits were gated the same way as
authorizations, an account could never legitimately go negative, and the
overdraft fee rule would be dead code. E7 (a plain DEBIT) is exactly this
case: it's allowed to post and does drive the account negative, which is what
triggers the Day 5 fee.

## 3. Overdraft fee is only specified for AED — what about BHD?

The brief gives one number: "Overdraft fee: AED 25.00." It never defines a
BHD figure, and I was told not to invent constants I can't justify.

**Resolution:** the fee is modeled as a `Map<Currency, BigDecimal>`
(`LedgerEngine.OVERDRAFT_FEE_BY_CURRENCY`) with **only AED populated**. If a
BHD account (or any future currency) ever closes a day negative, the engine
does *not* guess at a fee — it logs an `ErrorRecord` explaining that no fee is
defined for that currency, rather than silently applying AED's number,
silently applying zero, or crashing. In this scenario ACC-002 (BHD) never
goes negative, so this path is never exercised by the main replay — it exists
so the gap is visible rather than hidden. (Not the failing test — that one is
reserved for the over-settlement gap — but the same spirit: surface unknowns
instead of quietly resolving them.)

## 4. Order of fee assessment vs. interest accrual within the same day-close

The brief doesn't say whether fee should be checked before or after interest
within a single day's batch.

**Resolution:** fee is checked first (using the pre-fee closing balance), then
interest is computed off the closing balance *after* any fee just booked. In
practice this ordering is inconsequential for every case in this scenario,
and provably so in general: fee only fires when the balance is negative,
interest only fires when the (resulting) balance is positive — the two are
mutually exclusive on any given day, so whichever basis you pick for
interest, the answer is identical. I still had to pick one to write the code,
so I documented which.

## 5. Reversal `value_date` — literal from the event, or defaulted?

E9 is given an explicit `value_date = Day 2` ("reverses E7 — value_date Day
2") — matching E7's own value_date, not the day the reversal itself was
posted (Day 6).

**Resolution:** `ReversalEvent.valueDate` is taken literally from the input,
not computed/defaulted by the engine. For this scenario that's unambiguous
because the brief spells it out explicitly. As a general design note for any
*future* reversal that doesn't come with an explicit value_date: I would
default it to match the *original* entry's value_date (so a reversal always
lands in the same accounting bucket it's undoing), not the day the reversal
itself is posted — but this wasn't needed here since the brief was explicit,
so it's a documented intention rather than tested behavior.

## 6. Under-settlement: does it release the *entire* remaining hold?

E5 settles Auth-A (held 200.00) for 185.00 — 15.00 less than the hold.

**Resolution:** yes, settlement (even partial) fully releases the hold; there
is no separate "15.00 still held" residue left dangling. This is the standard
card-authorization model (a settlement is the *final* word on an
authorization; whatever wasn't captured is simply released, not partially
re-held) and is the only reading that doesn't require the brief to define
some new mechanism for "partial holds" that it never mentions. Modeled as a
single `HoldStatus.SETTLED` transition that ends the hold's active status
outright, regardless of the settled amount vs. the original hold amount.

## 7. Over-settlement: does settling for *more* than was held get capped, rejected, or passed through?

The mirror image of #6: what if a settlement amount exceeds the original hold?
The brief's E5 never tests this (185 < 200), so there's no example to anchor
on. **This is the one ambiguity I did not resolve with confidence** — see
REJECTED.md ("approaches abandoned") and the required failing test,
`OverSettlementExceedsHoldFailingTest`, for the full writeup. Shipped behavior
is permissive (book whatever amount is given, as long as the authId is known
and active); the failing test argues for the stricter alternative
(reject, like an unknown authId).

## 8. E10's "three equal instalments" of BHD 10.000 — equal is impossible at 3dp

`10.000 / 3 = 3.3333...`, not representable exactly at BHD's 3-decimal scale.
**Resolution:** largest-remainder method — floor every instalment to 3.333,
then give the single 0.001 leftover to the *last* instalment: 3.333 / 3.333 /
3.334, summing to exactly 10.000. See NUMBERS.md for why the leftover goes to
the last instalment specifically (and why "give each instalment 3.334" —
criterion 7 — is simply wrong, not a matter of taste).

## 9. Is "day" an abstract counter or a calendar date?

The brief only ever says "Day 1" through "Day 6," never a calendar date.
**Resolution:** days are plain `int`s, 1 through 6, with 0 reserved internally
for "before the window" (used only for a non-zero opening balance, which
doesn't occur in this scenario since both accounts open at 0). No calendar
arithmetic, timezones, or `LocalDate` anywhere — there was nothing in the
brief to hang that on, and inventing it would have added machinery the
scenario never exercises.

## 10. Does a *declined* authorization deserve a permanent record, or is it just a no-op?

The brief's append-only rule is about ledger entries; it doesn't say whether
"nothing happened" business outcomes (a declined auth, a rejected settlement)
need their own permanent audit record.

**Resolution:** yes — every authorization/settlement outcome, including
declines and rejections, is appended as its own immutable `HoldEvent` /
`ErrorRecord`. Nothing is ever "silently a no-op." This is why Auth-B's
decline and Auth-Z's rejected settlement both show up in the printed report
and are queryable exactly like an approved/settled hold would be.

## 11. Replay-order mismatch between E9 and E10

The brief lists events in the order E1...E10 and calls that "the order
replayed in", but E9 is labeled Day 6 and E10 (listed *after* E9) is labeled
Day 5. Taken completely literally, E10 would replay after E9 despite being
dated two days earlier.

**Resolution:** processed by `(day, then original event-number within that
day)`, which is the grouping the whole rest of the design (day-by-day
batches) depends on — so E10 is treated as part of the Day 5 batch and E9 as
part of the Day 6 batch, contrary to the raw E-numbering but consistent with
their stated days. This is safe here specifically because E9 and E10 touch
*different accounts* (ACC-001 vs ACC-002) — there is no scenario in which
their relative order changes any number for either account. I would not make
this same call if the two events shared an account.
