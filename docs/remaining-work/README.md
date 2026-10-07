# Active task specifications

**No implementation specification is open.** [R09h app-facing authority/offline recovery](../archive/remaining-work/R09h-review.md) is accepted at the owner-confirmed boundary; its [specification](../archive/remaining-work/R09h-task.md) is archived. The finite reduced R09 authority/recovery plan is closed, not a claim of current production account activation.

Next eligible pickup is **R12c — scoped app and timer reconciliation / package closure**. Its R09h authority, R12a/b and Core R10a dependencies are delivered. The owner requires stopping after R09h's scoped commit and durable handoff preparation, with no successor launch; follow the [current handoff](../coordinator-handoff.md), [roadmap](../remaining-work-roadmap.md) and [workflow](../coordinator-workflow.md) when the owner resumes/releases a fresh coordinator.

Current startup/screens/publisher are unchanged. Real scoped rebinding/timer/publication and private-scope/Compose package gates remain R12c; identity/account UI/triggers are R11/R13, live journeys R17. Legacy import/delete/reassignment is unauthorized; affected activation refuses unchanged pending owner disposition.

Create one normative `RNN-task.md` only for an authorized, dependency-ready slice after verified release. Include concrete behavior/APIs, disjoint allowlists, proportional independent tests/gates and exact stop. A partial/failing review may live beside its task but is not accepted. At closure, extract facts into [owning docs](../README.md#single-sources-of-truth), archive the completed specification/review, and replace current roadmap/handoff state. Keep incomplete/deferred outcomes visible.

**Replace current state; append only to historical evidence.** Archive records never grant current authority.
