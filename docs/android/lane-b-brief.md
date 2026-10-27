# Lane B shared brief

## Repository and worktree
- Repository reference: .
- Work only in this existing worktree: .
- Branch: ; do not switch branches.
- Preserve existing WIP and coordinate with lane peers; respect exclusive-file lists. Do not commit, push, or open a PR unless assigned by the lead. Coordinate checkpoint commits/pushes on this branch at least every 20 minutes.
- Current WIP history summary: .

## Required reading
Before implementation, read , , , and , plus relevant source. Do not infer implementation details beyond inspected code/history.

## Scope and forbidden targets
- Do not use the main checkout or any other worktree.
- Do not edit .
- Do not touch production, datasets, models,  files, secrets, or keystores.
- Do not use the real phone with serial .
- Keep changes focused, preserve existing WIP, and do not overlap another lane's exclusive files.

## Acceptance criteria
Implement and verify payment advisory behavior end-to-end:
- Distinguish UPI IDs from locally registered payees.
- Check amount sanity and assess urgency.
- Integrate the advisory into Result and QR Guard.
- Include robust tests, including adversarial inputs.
- Provide localized Hinglish, English, and Hindi (Devanagari), with accessible UI.
- Keep the confirmation disclaimer always last; make no success or fraud claim.
- Never persist or log raw payment content.
- Keep implementation limited to pure JVM modules in .

## Test and device safety
- The lead alone runs pure JVM tests and Gradle. Do not run either yourself.
- Gradle must use  and , under machine lock . Label the lock and remove it when done.
- Use only the shared  /  emulator. Before use, acquire ; never start another emulator.
- Verify your own changes with static checks/review. Report exact changed files and anything not verified.
