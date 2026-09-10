# REJECTED.md

The brief says: "Some of the following criteria are wrong. Identify every
incorrect criterion, refuse it, and document your reasoning here."

I evaluated all 8 acceptance criteria by hand-deriving the full six-day ledger
from the event stream and the four non-negotiable rules, then cross-checking
that derivation against the running engine (`ScenarioReplayTest` and
`ReplayMain`'s output are the executable proof for every number below).

**Verdict: criteria 2, 6, 7 and 8 are wrong and are refused below. Criteria 1,
3, 4 and 5 are correct and are accepted (with one clarifying note on 5).**

---

## Accepted (for completeness — not rejections, but shown so the reasoning is auditable)

**Criterion 1** — "The Day 2 closing ledger balance, evaluated at end of Day 5
and before any fee is assessed, is AED −370.00." **Correct.**
`1200.00 (E1) − 950.00 (E2) − 620.00 (E7, value_date Day 2, posted Day 5) =
−370.00`. E3 (Day 2) is a hold, not a booked entry, so it doesn't participate.
This criterion is exactly what motivated the two-day-parameter
`closingBalance(account, bucketDay, asOfDay)` API — see AMBIGUITIES.md.

**Criterion 3** — "The Day 4 settlement of Auth-A must be accepted." **Correct.**
185.00 settled against a 200.00 hold that was still active; nothing about that
is invalid.

**Criterion 4** — "Any settlement referencing an authorization ID not present
in the ledger must be rejected and the funds must not leave the account."
**Correct**, and this is exactly what happens to E6 (Auth-Z).

**Criterion 5** — "If Auth-B is approved, its hold reduces available balance
but not ledger balance." **Correct as a general statement of the rule** — and
it is *literally* what the engine implements (`activeHoldsTotal` never touches
`closingBalance`). One clarifying note, not a rejection: in this specific
scenario **Auth-B is never actually approved.** By the time E8 is processed
(Day 5, after E7 has already posted), the ledger balance is already −155.00;
adding a new 90.00 hold would drive available balance to −245.00, which fails
the non-negotiable authorization rule ("approved only if... available balance
... remains at or above zero"). So Auth-B is **declined**, not "approved but
never settled." The brief's framing ("Auth-B is never settled inside the
window") is consistent with either outcome, so I'm not rejecting criterion 5 —
its "if" is simply never triggered by this data. I flag it here so it isn't
mistaken for something we missed.

---

## Rejected

### Criterion 2 — "E7 causes exactly one overdraft fee, assessed on Day 2."

**Half right, half wrong. Rejected as stated.**

The "exactly one fee" part is correct. The "on Day 2" part is not: the fee is
assessed **on Day 5**, booked with `value_date = Day 5`.

Why: fee assessment is a **one-shot batch that runs once per day, in day
order, using only what has actually been posted to the ledger by that point**
(`runDailyClose(day)` in the engine — documented as "must be called exactly
once, ... strictly in increasing day order"). Day 2's batch already ran, on
Day 2, before E7 existed, and found a positive balance (250.00) — that
determination is final. There is no mechanism, and the brief gives none, for
retroactively re-opening a day that has already closed just because a later,
back-dated entry arrives. That would also directly contradict the brief's own
rule for *how* a fee is booked: "Booked with value_date equal to **the day
assessed**." The day the assessment actually happens is Day 5 (when E7 posts
and Day 5's own closing-balance check, `465.00 − 620.00 = −155.00`, goes
negative) — not Day 2.

Put differently: criterion 2 implicitly assumes "closing ledger balance for
day D" is a single, stable, retroactively-correctable number. It isn't — see
AMBIGUITIES.md's `bucketDay` vs `asOfDay` split. Criterion 1 and criterion 2
cannot both be taken literally: criterion 1 depends on the *retrospective*
reading (Day 2's balance CAN change when queried later, because value_date
back-dating is real), while criterion 2 depends on the *assessment-time*
reading being retroactively mutable (which the brief's own append-only,
"booked with value_date equal to the day assessed" language rules out). I
resolved the tension by accepting 1 and rejecting 2, because rejecting 1
would require pretending E7's value_date doesn't matter at all, which
contradicts the entire premise of the scenario.

Proof: `ScenarioReplayTest.criterion2_isRejected_theOverdraftFeeLandsOnDay5NotDay2`.

### Criterion 6 — "After E9, all balances and fees return to their pre-E7 values."

**Rejected.**

E9 reverses **E7 only** — it books a single offsetting entry for E7's exact
amount. It does not, and structurally cannot (append-only, no cascading
reversals implied anywhere in the brief), reverse the *downstream
consequences* of E7, namely the 25.00 overdraft fee that E7's temporary
negative balance triggered on Day 5. That fee is its own independent,
permanent ledger entry.

Pre-E7, the balance carried forward from Day 4 was 465.00. Post-E9, the
balance is 440.00 — permanently short by exactly the 25.00 fee. `465.00 −
25.00 = 440.00`, not 465.00. "All balances... return to their pre-E7 values"
is simply false by 25.00.

(Day 2's balance, specifically, does return to its pre-E7 value of 250.00
when re-evaluated after E9 — because both E7 and E9 share `value_date = Day
2`, they cancel exactly within that one bucket. But "all balances," plural,
including Day 4/5/6's forward-carried balance, does not, which is what the
criterion actually claims.)

Proof: `ScenarioReplayTest.criterion6_isRejected_balancesDoNotFullyReturnToPreE7ValuesAfterReversal`.

### Criterion 7 — "The three BHD instalments in E10 must each be BHD 3.334."

**Rejected — this is simple arithmetic, not an interpretation call.**

`3.334 × 3 = 10.002`, not `10.000`. BHD has 3 decimal places and 10.000 / 3 =
3.333... is not exactly representable at that precision, so the three
instalments literally cannot all be equal. Some split has to absorb a single
0.001 remainder. We use largest-remainder (floor to 3.333, give the leftover
0.001 to the last instalment: 3.333 / 3.333 / 3.334), which is one defensible
choice among a couple of equally defensible ones — but "all three are 3.334"
was never a candidate, because it doesn't sum to the stated total. See
NUMBERS.md for the full remainder-allocation reasoning.

Proof: `ScenarioReplayTest.criterion7_isRejected_theThreeBhdInstalmentsCannotAllBe3_334`.

### Criterion 8 — "If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded."

**Rejected — this directly contradicts a non-negotiable rule in the same brief.**

The brief states, as one of the four non-negotiable rules: "**The rounded
daily accruals must sum exactly to the capitalized total.**" Criterion 8
describes a *failure mode* of that rule (accruals not summing to the total)
and prescribes discarding the leftover — i.e. it describes a system that
*violates* the non-negotiable rule and then hides the violation by throwing
money away. That can never be correct: either the rule holds (and there is no
remainder to discard), or the rule is broken (and the fix is to reconcile the
remainder, e.g. by construction — see below — or by an explicit adjusting
entry, never by silently discarding it).

Our resolution avoids the failure mode entirely rather than needing to
"handle" it: **the capitalized total is *defined* as the sum of the
already-rounded daily accrual figures**, not as an independently-rounded sum
of the unrounded daily figures. Summing numbers that are already rounded to
the account's own decimal scale can never produce a value with a leftover
remainder at that same scale — the equality in the non-negotiable rule is true
by construction, not by luck. (Concretely: our engine's daily accruals for
ACC-001 are 0.10, 0.10, 0.26, 0.19, 0.00, 0.18 → sum 0.83, capitalized as
0.83. No remainder ever exists to discard.)

Proof: `ScenarioReplayTest.interestCapitalizationSumsExactlyToTheRoundedDailyAccrualsForBothAccounts`.

---

## Approaches abandoned mid-build

- **Mutable "current state" objects for holds/accounts.** First instinct was a
  plain `Map<String, HoldState>` with in-place `.settle()`/`.release()`
  mutation — much less code. Abandoned once I re-read the non-negotiable rule
  literally: "No event record is ever mutated or deleted." A mutable
  in-place hold object is exactly the kind of thing that rule is written to
  forbid, even if it's not itself a "ledger entry" in the strictest sense. I
  switched to a fully event-sourced `List<HoldEvent>` with the *current*
  status derived by folding, so nothing anywhere in the engine is ever
  mutated after being appended.

- **`double`/`float` for money.** Rejected before writing a single line — binary
  floating point cannot exactly represent 0.10, 3.333, etc., and "amounts
  stored and rounded to their own precision" is a hard requirement.
  `BigDecimal` throughout, one single rounding chokepoint (`Money.round`).

- **A single `closingBalance(account, day)` method.** This was the actual
  first draft. It fell apart the moment I tried to reconcile criterion 1
  against criterion 2: one number can't simultaneously be "what we knew at
  the time" (needed for fee assessment, which must never be retroactive) and
  "what we know now, filtered by value_date" (needed for the Day-2-as-of-Day-5
  retrospective query). Split into the two-parameter
  `closingBalance(account, bucketDay, asOfDay)` documented in AMBIGUITIES.md.

- **Rejecting over-settlement outright in the main design.** I strongly
  considered making `processSettlement` reject any settlement whose amount
  exceeds the original hold, by analogy with the unknown-auth-id rejection.
  I backed off making that the shipped behavior because the brief's own E5
  never tests the boundary and card-settlement semantics in the real world
  legitimately allow final settlement to differ from (including exceed) the
  original estimated hold. Rather than silently pick a side, I left the
  permissive behavior in the shipped engine and encoded the stricter
  alternative as the one required failing test
  (`OverSettlementExceedsHoldFailingTest`) instead of guessing.
