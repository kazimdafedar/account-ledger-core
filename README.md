# account-ledger-core

An in-memory account ledger core (Staff Software Engineer take-home). Pure
domain logic in Java 25: **no web layer, no persistence, no database, no UI.**
It is exercised entirely by an in-memory replay script and a JUnit 5 test
suite. The only external dependency in the whole project is JUnit 5, and it
is scoped to `test` only — `src/main/java` has zero dependencies beyond the
JDK (`java.math.BigDecimal`, `java.util`).

## What's in here

```
src/main/java/ledger/core/     the ledger engine itself (event-sourced, append-only)
src/main/java/ledger/replay/   Scenario.java (the exact 10-event brief scenario)
                                ReplayMain.java (runnable script — prints the day-by-day report)
src/test/java/ledger/          JUnit 5 tests, including ScenarioReplayTest (full replay,
                                one assertion per acceptance criterion) and the one
                                required intentionally-failing test
README.md                      this file
NUMBERS.md                     every constant, justified
AMBIGUITIES.md                 every ambiguity found, and how it was resolved
REJECTED.md                    which acceptance criteria are wrong, and why
WORKLOG.md                     real, timestamped build log
```

## Prerequisites

- Java 25 (JDK). Verify with `java -version`.
- Maven 3.9+. Verify with `mvn -version`.

Maven is used only as a build tool (compiling + running plain Java, and
running JUnit) — it is not a web/persistence framework and pulls in nothing
beyond `junit-jupiter` (test scope) and the standard compiler/surefire/exec
plugins.

## How to run the replay script (prints the required day-by-day report)

```bash
mvn -q compile exec:java
```

This replays the exact six-day, ten-event scenario from the brief through
the ledger engine and prints, **for every day, Day 1 through Day 6**:

- **Closing ledger balance** for each account, as of that day (`value_date <=
  day`, using only what had been posted by then).
- **Fee assessments** — which account, if any, was charged the AED 25.00
  overdraft fee that day.
- **Interest accrued that day** (not yet capitalized — shown for
  transparency, since it's central to how the Day 6 capitalization number is
  derived).
- **Authorization state changes** — every hold approved / declined / settled
  / rejected that day, with the reasoning (ledger balance, prior holds,
  available balance) baked into the printed line.
- **Errors** — e.g. E6's settlement against the unknown `Auth-Z`.

After Day 6, it also prints:

- The single capitalized interest credit per account, plus a check that it
  equals the sum of that account's rounded daily accruals (the non-negotiable
  rounding-reconciliation rule).
- The complete append-only entry log, hold log, and error log, in booking
  order — so every number in the daily sections above can be traced back to
  the exact entries that produced it.
- A short "selected acceptance-criteria demonstrations" section that prints
  the live numbers behind criteria 1, 2, 6, and Auth-B's actual outcome — the
  full written argument for every criterion (accepted or rejected) is in
  `REJECTED.md`; this is just the live numbers, produced by the same code.

## How to run the tests

```bash
mvn -q test
```

**This will report `Tests run: 23, Failures: 1` and exit non-zero.** That one
failure is intentional and required by the brief ("one failing test against
your own design, inline-annotated with what it reveals") —
`OverSettlementExceedsHoldFailingTest`. Do not interpret a non-zero exit code
here as a broken build; read the test's own doc comment for what it's
revealing (a genuine, unresolved ambiguity about over-settlement handling —
see `AMBIGUITIES.md` item 7 and `REJECTED.md`'s "approaches abandoned").

To run everything **except** that one test (e.g. to confirm the rest of the
suite is green in isolation):

```bash
mvn -q test -Dtest='!OverSettlementExceedsHoldFailingTest'
```

That will report `Tests run: 22, Failures: 0`.

Test files, if you want to read them directly rather than run them:

- `MoneyRoundingTest` — rounding-mode sanity checks.
- `LedgerEngineBasicTest` — fee/interest daily-close mechanics in isolation.
- `HoldsAndSettlementTest` — authorization approve/decline, settlement
  accept/reject, partial-settlement hold release.
- `ReversalTest` — reversal never mutates the original entry; unknown-id
  reversal is rejected.
- `ScenarioReplayTest` — the full brief scenario, one test method per
  acceptance criterion (accepted or rejected), each pinned to an exact number.
- `OverSettlementExceedsHoldFailingTest` — the required failing test.

## How to read the output / how the numbers are derived

The one design decision worth understanding before reading any output:
**"closing ledger balance for day D" is answered by a function with *two*
day parameters, not one:**

```java
engine.closingBalance(accountId, bucketDay, asOfDay)
```

- `bucketDay` — which accounting day's bucket you're asking about (filters by
  `value_date <= bucketDay`).
- `asOfDay` — how much of the ledger existed yet when you're asking (filters
  by `posted_day <= asOfDay`).

Daily fee/interest assessment always uses `bucketDay == asOfDay == "today"`,
run once per day, never re-run. Retrospective queries (like "what did Day 2
look like once Day 5's back-dated entry had landed") use `bucketDay <
asOfDay`. This single distinction is what makes criterion 1 (a retrospective
query, correct) and criterion 2 (an attempt to retroactively re-run an
already-closed day's fee assessment, wrong) both explicable instead of
contradictory — full reasoning in `REJECTED.md`.

## Design summary (see AMBIGUITIES.md / NUMBERS.md / REJECTED.md for the "why")

- **Event-sourced core.** `LedgerEntry`, `HoldEvent`, and `ErrorRecord` are the
  only sources of truth, held in append-only lists inside `LedgerEngine`. All
  "current state" (balances, hold status, available balance) is a fold over
  those logs at read time — nothing is ever mutated or deleted after being
  appended, per the brief's non-negotiable rule.
- **`BigDecimal` throughout**, rounded to each currency's own scale
  (`Money.round`, `RoundingMode.HALF_UP`) at the single point an amount is
  booked. No floating point, no epsilon constants.
- **Authorization holds** are checked against `ledger balance − active holds
  − new hold >= 0` at the moment they're requested, using the two-parameter
  balance function above with `bucketDay == asOfDay == today`.
- **Plain CREDIT/DEBIT/SETTLEMENT/REVERSAL entries always book
  unconditionally** — only authorizations are gated by the available-balance
  check. This is what lets an account legitimately go negative (which is
  exactly what the overdraft fee rule exists to charge for).
- **Daily close (`runDailyClose(day)`)** assesses the overdraft fee (if
  negative) and accrues interest (if positive) exactly once per day, in day
  order, and is never re-run for a day already closed.
- **Interest capitalization (`capitalizeInterest(day)`)** books a single
  credit per account equal to the *sum of that account's already-rounded
  daily accruals* — which is what guarantees the non-negotiable
  "rounded daily accruals must sum exactly to the capitalized total" rule
  holds by construction, not by luck.

## Git history

Commits are incremental and unsquashed, in the order the design actually
came together (scaffold → core engine → replay + tests → documentation).
`WORKLOG.md` has real timestamps matched to each stage, including a genuine
test-isolation bug hit and fixed along the way: in `ScenarioReplayTest`,
interest capitalization and E9's reversal turned out to share the same
day-granularity `value_date`/`posted_day` (both `6`), so a first-draft
assertion about the post-reversal balance was accidentally polluted by the
unrelated 0.83 interest credit. Fixed by capturing the balance at the right
point in the replay instead of after the fact.
