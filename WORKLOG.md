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
