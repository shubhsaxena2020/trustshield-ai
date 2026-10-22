# Items needing Shubh

No decision blocks Phase 0. The team already supplied the product and safety decisions in `MISSION.md`; do not reopen them.

## Approval-dependent items

- If W4 produces a new model candidate: review the frozen candidate, data sources/licences, criteria, and hash of a newly sealed holdout before anyone opens/evaluates it. The holdout must remain unopened during this run.
- Supply/authorize a test account or mailbox only if real Supabase email-confirmation/password-reset flows cannot be verified with existing local test fixtures. Do not place credentials in this file; provide them through the approved secret mechanism.
- Decide whether to pursue any acceptance gate that requires a missing emulator/device or external account after the exact local build/test gap is documented.

## Opened during execution

Append concrete item, why Shubh is required, evidence already gathered, and the exact decision/action needed. Continue independent work while waiting; do not use this file for routine implementation choices.
