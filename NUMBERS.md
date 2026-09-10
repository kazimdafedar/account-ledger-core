# NUMBERS.md

Every constant in this codebase, where it came from, and — for the ones I
actually chose rather than copied verbatim from the brief — why that value and
not some other one (including "half it").

## Given verbatim by the brief (not my choice — listed for completeness)

| Constant | Value | Where |
|---|---|---|
| Overdraft fee (AED) | `25.00` | `LedgerEngine.OVERDRAFT_FEE_BY_CURRENCY` |
| Daily interest rate | `0.04%` = `0.0004` | `LedgerEngine.DAILY_INTEREST_RATE` |
| AED decimal places | `2` | `Currency.AED` |
| BHD decimal places | `3` | `Currency.BHD` |
| Window length | `6` days | `ReplayMain` loop bound |
| Opening balances | `0.00` AED / `0.000` BHD | `Scenario.openingBalances()` |

Nothing to justify here — these are inputs, not decisions.

## Constants I actually chose

### Rounding mode: `RoundingMode.HALF_UP`

The brief says amounts are "stored and rounded to their own precision" but
never names a rounding mode. I chose `HALF_UP` (round half away from zero)
over the alternatives:

- **Why not `HALF_EVEN` (banker's rounding)?** `HALF_EVEN` is what you'd reach
  for if the goal were minimizing long-run statistical bias across millions
  of roundings (e.g. floating-point-heavy scientific computing, or a bank
  rounding billions of daily interest postings where a systematic 0.5-cent
  bias could compound into real money over years). That's a real
  consideration in general ledger systems, but it's solving a problem this
  exercise doesn't have: six days, two accounts, a handful of roundings. Its
  benefit here is exactly zero. `HALF_UP` is also what "rounded" means to a
  human reading the printed report without a footnote (0.186 → 0.19, not a
  coin-flip depending on whether the second-to-last kept digit happens to be
  even) — I optimized for that legibility given the brief explicitly wants
  output a person reads day-by-day.
- **Why not truncation (`RoundingMode.DOWN`)?** Truncation would silently
  and systematically *underpay* interest and *undercharge* nothing (fees are
  fixed, not computed), every single day, in the bank's favor, without ever
  correcting for it. That's a defensible policy some real systems do choose
  deliberately, but it's a strictly worse default for an assessment whose own
  non-negotiable rule is that rounding must reconcile *exactly* to the
  capitalized total — truncation makes that reconciliation trivial by
  starving the customer, which felt like gaming the requirement rather than
  meeting it honestly.
- **"Why that value and not half it":** there's no "half of HALF_UP" — it's a
  categorical choice (which of a fixed small set of rounding *policies*),
  not a magnitude. The closest analogous question is "why HALF_UP and not
  HALF_DOWN (round half *toward* zero)?" — I want ties to round in the
  customer's favor when accruing interest (a credit) and I want that same
  rule applied uniformly everywhere rather than switching sign-by-sign, so
  one consistent away-from-zero rule, applied everywhere including the fee
  debit, was simpler to reason about and audit than a rule that flips
  direction depending on whether the number happens to be a credit or debit.

### No epsilon / fudge-factor constant, anywhere

Many ledger codebases have some `EPSILON = 0.005` or similar for "close
enough" floating-point comparisons. **This codebase has none, deliberately.**
`BigDecimal` arithmetic on already-rounded, fixed-scale values is exact —
`0.10 + 0.10 + 0.26 + 0.19 + 0.00 + 0.18` really does equal `0.83`, bit for
bit, every time. Introducing a tolerance constant would have been solving a
problem specific to binary floating point that switching to `BigDecimal`
already eliminates at the source. "Why not half it" doesn't apply — there's
no epsilon to begin with, at any magnitude, because the representation was
chosen to not need one.

### E10 instalment remainder: floor to 3.333, give the 0.001 leftover to the *last* instalment

`10.000 / 3 = 3.333...`; at 3dp you must floor every instalment to `3.333`
and then place a single undistributable `0.001` remainder somewhere (this is
the "largest remainder" allocation method). I put it on the **last**
instalment rather than the first, or splitting it (which is impossible at
this precision — see below), or spreading it evenly across all three (also
impossible: `0.001` can't be divided by 3 and stay at 3dp).

- **Why last, not first?** No functional difference for this exercise (all
  three post on the same day, to the same account, with the same value_date —
  order among them is not observable in any output). I picked "last" purely
  as a stable, boring convention (it's the same rule you'd use rounding a
  bill split three ways: the person who'd otherwise be shortchanged by
  flooring gets made whole last), and documented it here rather than leaving
  it as an unstated accident of iteration order.
- **"Why not half it" — could the remainder be split, e.g. two instalments
  get `+0.0005` each?** No: BHD's minor unit is `0.001`; `0.0005` is not a
  representable BHD amount. The remainder is indivisible at this currency's
  precision, so "give it to exactly one instalment" isn't a stylistic choice
  among many close alternatives — it's the *only* precision-legal option
  once you've decided not to make all three amounts different from the
  naive floor. (Criterion 7's "all three are 3.334" fails for the same
  underlying reason: `0.002` extra total is not a valid way to distribute a
  `0.001` remainder either.)

### Interest capitalization total = sum of already-rounded daily accruals, never re-rounded independently

Given the non-negotiable rule ("rounded daily accruals must sum exactly to
the capitalized total"), the only value that can satisfy that rule *by
construction* (rather than by coincidence) is: capitalized total := Σ
(daily accrual, each already rounded to the account's own scale). I did
**not** compute Σ(unrounded daily accrual) and then round that sum once at
the end — that alternative can (and in general will) disagree with Σ(rounded
daily figures) by a cent/fils, which is exactly the discrepancy criterion 8
describes and wrongly tells you to paper over by discarding. There is no
"half it" version of this: it's not a tunable magnitude, it's a choice of
*which of two different numbers* to call "the total," and only one of them is
guaranteed equal to the sum the brief requires.

### Fee currency scope: only AED is defined, no invented BHD figure

I was explicitly asked to justify every constant "why that value and not half
it" — the honest answer for a hypothetical BHD overdraft fee is: **I didn't
invent one, at any value, half or otherwise**, because the brief gives me no
basis to pick BHD 12.500 over BHD 25.000 over anything else. Inventing a
number here would have been indistinguishable from guessing. See
AMBIGUITIES.md item 3 for what the engine does instead (logs the gap rather
than guessing).
