# Survive

Java browser adaptation of [azurras/survive](https://github.com/azurras/survive),
inspected at `24d50be92e1609e6e983b7dda9231bd77777b3d1`.
Survive and the ported gameplay in this package are distributed under the MIT
license, which is served at `/licenses/survive-MIT.txt`. No Python process is used.

## One authoritative world

`SurviveService` loads one shared durable world for every operation and commits a
versioned replacement after successful commands. Mongo stores the bounded world
as the additive `survive_world` kind in `application_runtime`, separate from the
historical cutover manifest. One replacement atomically saves both gift
inventories, all character progress, camp structures and the last 30 events.
Storage conflicts return 409 without replay; storage errors are never reported
as successful saves. Detached restoration prevents failed saves from leaking
speculative state into later responses. The same version check prevents multiple
application processes from silently overwriting each other's commands.

Logged-in accounts own one character by their stable authenticated account ID;
guest cookies cannot select an account character. GET resumes an existing
character across sessions and server restarts. Joining again resumes a living
account character; after death or escape it starts a replacement. Guest progress
is separate and is never automatically imported into an account. The snapshot's
`saved` flag identifies durable account ownership without exposing account IDs.

Temporary guests retain opaque session cookies and expire after two idle hours.
Inactive account characters remain saved but leave the active camp and recipient
list until resumed. Receiving supplies does not refresh presence. The world caps
all saved and guest characters at 1000 and structures at 1000 each. Account
deletion removes its embedded character with an optimistic version advance.
Public nicknames and journal events retain their existing public semantics.
Guests also share the durable camp; closing their browser can end their session.

## Browser contract

- `GET /api/survive/v1/game`: current survivor snapshot, or 204 before joining/after expiry.
- `POST /api/survive/v1/game`: `{ "name": "Chris" }` creates a guest or replaces it; creates an account character, resumes a living one, or replaces a terminal one.
- `POST /api/survive/v1/actions`: `{ "action": "GATHER", "revision": 0 }` applies a command.
- `POST /api/survive/v1/gifts`: `{ "recipientId": "public-uuid", "resource": "WOOD", "quantity": 2, "revision": 0 }` gives wood, food or stone to another survivor.

Each snapshot includes a public `survivorId` separate from the private owner identity and
eligible `recipients` with public ID and name. The existing `survivors` names list
is retained. Public IDs cannot authorize actions. The giver and recipient must
both be exploring at camp; gifts of 1–10 wood, food or stone require sufficient supplies
and recipient space. All validation precedes the atomic transfer under the world
monitor. Both inventories and revisions change together, each player sees a
message and the journal records the gift. Receiving never extends idle expiry.
Replaced or expired IDs return 404; unavailable gifts return 400, stale sender
revisions 409. The UI retains recipient and amount selections during polling.

These exact method/path combinations permit guests and authenticated accounts; existing CSRF and rate limiting
remain enabled. For guests, a random opaque HttpOnly, SameSite=Strict session cookie identifies
the survivor, scoped to `/api/survive/v1`; HTTPS makes it Secure. Account identity overrides cookies. Tokens and account IDs are never
included in JSON or journal entries. Names and journal entries are public, so the
page asks players to use a nickname. Successful responses are no-store. Stale
survivor revisions return 409 without mutation; missing identities return 404,
unavailable actions and invalid names return 400, and full capacity returns 503.
GET refresh never applies a survivor command, but every request settles world
time first (see below), and a GET that commits presence also persists that
settlement. Clients must reconcile an uncertain command with GET and must never
automatically resend mutations.

## World time

`SurviveClock` derives a shared phase from the wall clock against a fixed epoch:
each 24-minute cycle has 14 minutes of day, 4 of dusk and 6 of night. Snapshots
carry `phase`, `day` and `secondsToNextPhase`; the browser counts down locally and
reads the world when the phase ends. No background job runs. Instead each service
entry point loads the world and calls `SurviveWorld.settleUntil(now)`, which applies
every nightfall raid and dawn since `settledBoundaries`, oldest first, and records
the new count in the same versioned document. The first settlement only starts the
count, so a world saved before the clock existed never replays its past. After a
long quiet spell only the latest three nights are applied and the journal notes the
gap. Settlement is deterministic, so a request that fails before committing simply
settles again next time with the same result.

## Mechanics

**Raids.** At each nightfall, raiders of strength 4 rising by 2 each night of a
seven-night week (to 16, then back to 4) meet the camp's defense: the sum of
`1 + strength + weapon bonus` for every survivor who chose **Guard** at dusk. A
repelled raid rewards each guard with 1 food, or experience when full. Otherwise
raiders destroy one structure plus one per 3 points of shortfall, taking boats
first only while they outnumber shelters. Guard duty ends at dawn or on leaving camp.
Absent survivors are never harmed; only shared structures are at risk.

**Enemies and ambushes.** Hogs (5 health, bite 1, 1 food), wolves (8, 2, 1) and
bears (14, 3, 3) exist. Hunting keeps its 61% discovery; by day it finds hogs, at
dusk hogs or wolves, at night hogs, wolves or bears (50/35/15). Gathering and
hunting are first ambushed at 5%, 12% or 25% by phase. Defend blocks one bite and
fleeing costs the enemy's bite. Camp actions are never ambushed.

**Stone and weapons.** Gather stone succeeds 40% of the time and shares the
10-slot inventory. One private weapon slot holds a club (2 wood, +1, 8 uses),
stone axe (2 wood and 2 stone, +2, 12 uses, wood gathering 70%) or spear (3 wood
and 3 stone, +3, 12 uses, no bite on the first attack of each fight). Crafting
replaces the held weapon; each attack spends a use and the weapon breaks at 0.

**Sleep.** Each shelter has two beds. Sleep is available at dusk or night when a
bed is free; a sleeper can only Wake, recovering 1 health per 2 minutes asleep.
Dawn wakes every sleeper with full health. If a raid burns shelters, the latest
sleepers beyond the remaining beds wake with 2 damage. Rest still gives +2 health.

**Hunger.** Fullness runs from 10 to 0. Each action outside camp, combat move and
guard shift costs 1, and each night's sleep costs 2 on waking. At 0 those efforts
cost 1 health instead and sleep does not heal. Eating gives +5 fullness and
+2 health.

Saved survivors from before these mechanics load with full hunger, no stone or
weapon, a hog for any fight in progress, awake and not guarding. New saved fields
are boxed so missing values decode as null and take those defaults.

### Original mechanics

Original starting health 10, strength 2, stamina 10, inventory capacity 10,
50% wood gathering, exponential skill XP, five-wood shelters, ten-wood boats,
61% hog discovery, hog health 5 and simultaneous attack exchanges are retained.
Strength/stamina progress together through gathering; strength caps at 20 to
bound arithmetic. Shelter/boat items become shared camp structures outside private
inventories. Victory awards food if inventory has room; shelters permit two-health
rests. Boats are consumed by one survivor's escape. Dead/escaped
survivors cannot act until replaced. These complete the Python game's unfinished
defend/run, food acquisition/consumption and boat goal.

Tests inject clock and percent rolls. `SurviveServiceTest` verifies shared camp,
private survivor state, expiry, capacity, validation and stale command rejection;
`SurviveWorldTest` covers mechanics; controller tests cover anonymous CSRF-protected HTTP behavior and the real bearer authentication filter. Saved-state tests cover restart, failed saves, competing processes and account/guest isolation. UI renders server snapshots with text nodes and polls every five
seconds while visible. JavaScript owns no gameplay rules.
