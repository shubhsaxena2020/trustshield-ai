# Two-day execution and one-day buffer

**Start:** after Claude Code accepts Phase 1. Use elapsed blocks from that approval, not invented clock times. Three Hermes sessions (`agent1`, `agent2`, `agent3`) have nine planned child slots each. A slot is a file-owned work package, not a command to launch all 27 simultaneously. Start only dependency-ready packages; Codex reviews every merge. If `delegation.max_concurrent_children` rises to 18, the same A/B/C package IDs and ownership stay valid; additional capacity only pulls ready P1/QA packages forward. No duplicate writers.

| Elapsed | agent1: core (`A`) | agent2: inputs (`B`) | agent3: UI/QA (`C`) | Gate |
|---|---|---|---|---|
| Day 1, 0–4 h | A01 contracts, A02 normalizer | B01 share, B02 QR payload parser | C01 navigation, C02 design tokens | compile + contract review |
| Day 1, 4–8 h | A03 stage rules, A04 legit/negation | B03 QR camera, B04 mic capability probe | C03 result UI, C04 settings UI | deterministic fixtures and permission states |
| Day 1, 8–12 h | A05 fusion/confidence, A06 backend adapter | B05 active Call Shield, B06 QR watcher | C05 Call screen, C06 QR screen | first vertical slice, APK build |
| Day 2, 12–18 h | A07 privacy audit, A08 API tests | B07 share image bounds, B08 ASR fallback trial | C07 accessibility, C08 end-to-end flows | integration tests, phone/emulator if present |
| Day 2, 18–24 h | A09 payment advisory only if P0 green | B09 notification spike only if P0 green | C09 demo/assets only after device proof | freeze new features, APK candidate |
| Day 3 buffer, 24–36 h | release blockers only | phone retest and permission recovery | polish, 10 rehearsals, artifact check | full suite, build, size/start/memory, handoff |

The first 18 slots (A01–A06, B01–B06, C01–C06) are P0. A07/A08/B07/B08/C07/C08 are hardening and remain active on Day 2. A09/B09/C09 are conditional P1/P2/packaging. In practice dependencies may leave a child idle; do not assign the same file to fill a slot. Codex owns Gradle/manifest merges and cross-package contract changes after workers' branches are reviewed. Keep the base APK buildable at every block and preserve the whole final day for real-device fixes. P0 completion, especially Hinglish and Call Shield, is more important than nominal occupancy of 27 slots.

## Merge order

Contract and local logic (A01–A05) precede live inputs. UI can use synthetic fixtures until contracts are stable. Camera/mic components must never change verdict code. A06 backend adapter is optional enrichment. At block boundaries: each worker sends changed paths, tests, screenshots/logs and known gaps; Codex reruns relevant checks and merges one PR at a time. If a dependency fails, run disjoint tests/docs packages and leave feature claims open. No website, production, holdout, model or `.env` modifications.

A06 may be implemented on Day 1 but may not be enabled for Android scans until A07 audits both signed-in history and server memory retention. If that audit cannot meet N01, ship only local analysis and label backend enrichment unavailable for the privacy gate.
