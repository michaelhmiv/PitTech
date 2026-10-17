# Online services: approved implementation contract

Issue [#89](https://github.com/michaelhmiv/PitTech/issues/89) and the owner's
October 14 implementation authorization govern this post-1.0 initiative.
The [PR plan](ONLINE_SERVICES_PR_PLAN.md) defines the work; the
[experience specification](online/DESIGN.md) defines the interface.

## Settled amendments

- Meals, Recipes, Discover, Insights, More form the shared navigation.
- Local cooking, editing, saving community recipes and using downloaded guidance
  remain free and available without an account.
- Individual recipes and selected-dish collections are first-class. Editable
  sharing drafts do not mutate recorded meals. Saving creates a private copy;
  publishing a variation creates a public fork intentionally.
- One persisted **Show guide** switch controls source instructions, milestones
  and reference curves across Live, Timeline and Charts. Each dish has its own
  pinned reference and Food on anchor. Reference data never enters actual logs.
- Anonymous publishing is chosen per item and cannot disclose the owner's
  profile through URLs, provenance or public payloads.
- Reporting, filtering, blocking and a monitored contact ship with the first
  public-content release. Comments, DMs and a follow feed are out of scope.
- **Account deletion removes account-owned original publications, their
  revisions/media, private sync data, sessions, likes and profile.** Other users'
  independent copies/forks survive, credited to a removed source without
  retaining the deleted author's identity or original payload. This supersedes
  the original issue's default anonymize-and-retain behavior; the owner
  authorized this amendment on October 14. Downloaded copies cannot be recalled.
- PitTech Pro is monthly only, target US price $3, purchased through StoreKit
  and Play Billing. Existing free Insights remain free. Verified entitlements
  prevent all ad requests, including prefetch.
- `pittech.app` is the owned public domain. Resend replaces the identified
  Sportfolio sending configuration after the replacement works. Existing
  PitTech privacy and feedback services remain in place.

## Boundaries

Local library, private account sync, bearer-code relay and public publications
have separate authorization and storage paths. Public data is an allowlist,
never a whole backup with fields hidden by the UI. Aggregation reads only unique
public measured attempts, never editable instructions or private libraries.

Equipment remains strictly observational. Guide visibility, account state,
reminders and subscriptions cannot write to a grill or activate monitoring.
All five current Beta connections remain available in store builds.

## Delivery

The owner authorized implementation and routine design decisions on October 14.
Concrete designs precede feature coding; self-review against the agreed
hierarchy replaces a blocking design-permission request. Launch work in PRs
#77, #78 and #88 stays separate. The new branches do not change the 1.0 release.
Every feature needs Android and iOS, migration/contract evidence where relevant,
native visual review and GitHub Actions results before it is called verified.

See [platform parity](PLATFORM_PARITY.md), [contracts](../contracts/README.md),
[delivery status](online/STATUS.md), and the existing
[connection parity matrix](CONNECTION_PLATFORM_PARITY.md).
