# WORKLOG

Real, timestamped log of work performed on this assessment. Timestamps are local
machine time (IST, UTC+5:30), taken from `date` at the moment of the entry — not
reconstructed afterward.

## Fri Sep 11 2026, 00:00 IST — Start

- Read the assessment brief in full. Decided on Java 25 (per requester preference)
  as the implementation language, plain Maven project (no Spring, no DB driver, no
  web framework — the pom only pulls in JUnit 5 for the test *scope*, which is a
  test harness, not "persistence/UI/web layer" production code).
- Before writing any code, hand-simulated the entire six-day event stream on paper
  (see `AMBIGUITIES.md` and `REJECTED.md` for the full derivation) to find out
  which acceptance criteria are actually consistent with the non-negotiable rules.
  This surfaced the core design fork this whole exercise hinges on:
  **"closing ledger balance for day D" is not one number — it depends on whether
  you're asking "what did we know at end-of-day-D" (used for fee/interest
  assessment, which is a one-shot batch that can never be re-run) vs "what do we
  know now, filtered to value_date <= D" (a retrospective report query, which DOES
  see later-arriving back-dated entries like E7).** Criteria #1 and #2 in the brief
  are only reconcilable if you make that distinction. Decided to bake it into the
  core API as two explicit day parameters: `bucketDay` (value_date cutoff) and
  `asOfDay` (posting-order cutoff), rather than a single "day" argument.
- Decided on an event-sourced core: an append-only `List<LedgerEntry>` and an
  append-only `List<HoldEvent>` are the only sources of truth. All "current state"
  (balances, hold status, available balance) is a fold/projection over those logs,
  never a mutated field. This is the only way I could satisfy "no event record is
  ever mutated or deleted" while still supporting holds that change status
  (approved → settled) and balances that get corrected retroactively by back-dated
  entries.

## Fri Sep 11 2026, 00:05 IST — Scaffolding

- `git init`, Maven layout (`src/main/java`, `src/test/java`), `pom.xml` targeting
  Java 25, JUnit 5 for tests only.

## Fri Sep 11 2026, 00:05–00:07 IST — Core domain model

- Wrote `Currency`, `Money` (single chokepoint for rounding — HALF_UP, documented
  in NUMBERS.md), the sealed `Event` hierarchy (`CreditEvent`, `DebitEvent`,
  `AuthorizationEvent`, `SettlementEvent`, `ReversalEvent`), and the derived
  append-only records (`LedgerEntry`, `HoldEvent`, `ErrorRecord`,
  `InterestAccrual`).
- Wrote `LedgerEngine`: the append-only entry/hold/error logs, `process(Event)`
  dispatch, `closingBalance(account, bucketDay, asOfDay)`, `activeHoldsTotal`,
  `currentHoldState`, `runDailyClose(day)` (fee + interest, once per day, never
  re-run), `capitalizeInterest(day)`.
- `mvn -q compile` clean on first full pass. One thing I deliberately checked
  rather than assumed: `entryId(prefix, suffix)` reads the mutable `seq` field,
  and it's called as a sibling argument to `nextSeq()` in the same
  `new LedgerEntry(nextSeq(), entryId(...), ...)` call — that only produces a
  matching id/seq pair because Java guarantees left-to-right evaluation of
  argument expressions. Left a comment on `entryId()` calling this out so a
  future refactor (e.g. reordering those two arguments) doesn't silently break
  id/seq correspondence.
- Committed: "Core domain model: event-sourced ledger engine".

## Fri Sep 11 2026, 00:07–00:08 IST — Replay script

- Hand-encoded the exact 10-event scenario in `Scenario.java`. Hit the E9/E10
  ordering wrinkle immediately: the brief lists E10 (Day 5) after E9 (Day 6) in
  event-number order, but groups by `postedDay` for the daily-close batch — so
  I process by (day, then original event-number within that day), which puts
  E10 in the Day 5 batch and E9 in the Day 6 batch. Confirmed this has zero
  effect on any number because E9/E10 touch different accounts. Documented in
  AMBIGUITIES.md.
- Wrote `ReplayMain`, ran it with `mvn -q compile exec:java`. First run's
  numbers matched my hand-derivation exactly on the first try:
  Day1/2 close 250.00, Day3 close 650.00, Day4 close 465.00, Day5 close
  -180.00 (post-fee; -155.00 pre-fee) with exactly one 25.00 fee on Day 5 (not
  Day 2 — see criterion 2), Day6 close 440.00, Auth-B DECLINED, interest
  capitalized 0.83 AED / 0.008 BHD. This was the single most load-bearing
  moment of the whole build: if the hand-derivation and the code had disagreed
  here I would have had to re-derive by hand to find out which one was wrong.
  They agreed, so I moved on with real confidence in the engine.

## Fri Sep 11 2026, 00:08–00:10 IST — Test suite

- `MoneyRoundingTest`, `LedgerEngineBasicTest`, `HoldsAndSettlementTest`,
  `ReversalTest`, `ScenarioReplayTest` (full 6-day replay, one assertion per
  acceptance criterion, accepted or rejected).
- Wrote the required intentionally-failing test,
  `OverSettlementExceedsHoldFailingTest`, for the one real gap I found in my
  own design: settlement amount is never checked against the original hold
  amount. Ran `mvn -q test`: first run surfaced a SECOND, unintended failure —
  `criterion6_...` was comparing Day 6's closing balance to 440.00 but got
  440.83, because by the time that assertion ran, `capitalizeInterest(6)` had
  already booked its 0.83 credit with the same `valueDate`/`postedDay` (6) as
  E9's reversal, and my day-granularity balance query can't distinguish them.
  Fixed by capturing the Day-6 balance immediately after `runDailyClose(6)`
  and before `capitalizeInterest(6)` in the test setup, rather than querying it
  after the fact. Real bug, real fix, timestamped as it happened — not
  cleaned up after the fact to look tidier.
- Final: `mvn -q test` → 23 run, 1 failure (the intentional one), 22 green.
  Exit code 1, as expected for a build with a deliberately failing test —
  documented in README.md so this isn't mistaken for a broken build.

## Fri Sep 11 2026, 00:10–00:14 IST — Documentation

- Wrote `REJECTED.md` first, since it forced me to state, in writing, exactly
  which of the 8 acceptance criteria are wrong and why, before writing README
  copy that might otherwise gloss over the disagreements. Verdict: criteria
  2, 6, 7, 8 rejected; 1, 3, 4 accepted; 5 accepted with a clarifying note
  (Auth-B is actually declined, not merely "unsettled").
- Wrote `AMBIGUITIES.md` — 11 items, each with a concrete resolution and a
  pointer to the code/test that encodes it. Deliberately did not pad this
  with trivial non-ambiguities just to look thorough; every item here changed
  an actual line of code.
- Wrote `NUMBERS.md`, going through every constant and, for the ones I
  actually chose (rounding mode, no-epsilon, remainder allocation,
  capitalization-total definition, fee-currency-scope), explicitly answering
  "why this and not half it" per the brief's instruction.
- Wrote `README.md` last, once the other three existed to link to. Verified
  every command in it actually runs as written (`mvn -q compile exec:java`,
  `mvn -q test`, `mvn -q test -Dtest='!OverSettlementExceedsHoldFailingTest'`)
  before committing, rather than describing intended behavior from memory.
- Corrected one inaccuracy caught during this pass: an earlier draft of this
  WORKLOG and README claimed a compile-order bug in `LedgerEngine.entryId()`
  had to be "fixed." On review that never actually happened — it was designed
  correctly from the start by relying on Java's left-to-right argument
  evaluation. Rewrote both to describe what was actually verified (added a
  guarding code comment) instead of a fix that didn't occur — this worklog is
  supposed to be real, not padded with invented drama.

## Fri Sep 11 2026, 00:14 IST — Done

- Final state: 3 commits (scaffold, core engine, replay+tests), plus this
  documentation commit. `mvn -q compile exec:java` prints the full six-day
  report; `mvn -q test` runs 23 tests, 22 green + 1 intentionally red.
