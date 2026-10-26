# Demo script (5 minutes maximum) and recording checklist

No demo video has been recorded in this repository. This is the plan for recording one. All data on screen must be synthetic.

## Script
| Time | Show | Say (summary) |
|---|---|---|
| 0:00 to 0:30 | Title, then the new-bill page | The problem: an aadat agent sells a farmer's lots to several purchasers and settles after expenses; hand arithmetic is slow and error-prone. User: the firm owner and counter staff. |
| 0:30 to 2:15 | Sign in; new bill: pick the **village first**, then a farmer of that village; add a village in the dialog without losing the draft; add two lots with lot marks; add purchasers per lot (actual weight and rate, 5 % commission calculated); add a per-piece expense and a typed expense; point at the live totals | The journey. Rate is per 100 kg; amounts shown live are mirrored from the server rules, and the server stores its own result (`BillCalculator`). Purchaser rows are what each purchaser really bought; nothing is split automatically. |
| 2:15 to 3:00 | Save; the bill number; search the bill list; reopen it; open the folded buyer-side block | Continuous bill number per firm across financial years; the farmer side and buyer side are never added together. |
| 3:00 to 3:40 | Switch to Hindi; open the print view in English, then Hindi; narrow the window to 390 px | Both languages end to end; the print layout is a draft and was checked in browser print preview only (no physical printer). |
| 3:40 to 4:30 | Briefly: `docs/ARCHITECTURE.md`; the test summary in `docs/SUBMISSION.md`; the route-enumerating security tests | Technical decisions: server-side calculation in one class, composite foreign keys for firm isolation, restricted runtime database role, fail-closed start-up, append-only audit, invitation-only onboarding. |
| 4:30 to 5:00 | `docs/ai-log.md` and `docs/PROVENANCE.md` | AI-assisted development with human decisions and honest failure records; verification by independent test layers; disclosed reference-informed concepts and unresolved rules. |

## Recording checklist
- [ ] Use the disposable stack or the demo profile with the synthetic seed; never a database with real records.
- [ ] Browser window 1440 px wide for the main flow, then 390 px for the mobile part; zoom 100 %.
- [ ] Close other tabs, mail, chat and anything with names or notifications; check the address bar and bookmarks.
- [ ] No terminal showing passwords, tokens or `.env.local`.
- [ ] Run the journey once beforehand; prepare synthetic names ("Demo Farmer", "Demo Village", "Demo Buyer").
- [ ] Have the English and Hindi print views ready.
- [ ] Stay under 5:00; export as MP4; play it back once with sound.
- [ ] Do not claim physical-printer testing, native-speaker Hindi review or CI results that did not happen.
