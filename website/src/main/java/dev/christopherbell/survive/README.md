# Survive

Java browser adaptation of [azurras/survive](https://github.com/azurras/survive),
inspected at `24d50be92e1609e6e983b7dda9231bd77777b3d1` (GPL-3.0).
The ported gameplay in this package is distributed under GPL-3.0; the upstream
license is served at `/licenses/survive-GPL-3.0.txt`. No Python process is used.

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
- `POST /api/survive/v1/gifts`: `{ "recipientId": "public-uuid", "resource": "WOOD", "quantity": 2, "revision": 0 }` gives wood or food to another survivor.

Each snapshot includes a public `survivorId` separate from the private owner identity and
eligible `recipients` with public ID and name. The existing `survivors` names list
is retained. Public IDs cannot authorize actions. The giver and recipient must
both be exploring at camp; gifts of 1–10 wood or food require sufficient supplies
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
GET refresh never advances gameplay. Clients must reconcile an uncertain command
with GET and must never automatically resend mutations.

## Mechanics

Original starting health 10, strength 2, stamina 10, inventory capacity 10,
50% wood gathering, exponential skill XP, five-wood shelters, ten-wood boats,
61% hog discovery, hog health 5 and simultaneous attack exchanges are retained.
Strength/stamina progress together through gathering; strength caps at 20 to
bound arithmetic. Shelter/boat items become shared camp structures outside private
inventories. Defend blocks one bite; flee costs one health. Victory awards food if
inventory has room. Food is consumed to recover up to five health; shelters permit
two-health rests. Boats are consumed by one survivor's escape. Dead/escaped
survivors cannot act until replaced. These complete the Python game's unfinished
defend/run, food acquisition/consumption and boat goal.

Tests inject clock and percent rolls. `SurviveServiceTest` verifies shared camp,
private survivor state, expiry, capacity, validation and stale command rejection;
`SurviveWorldTest` covers mechanics; controller tests cover anonymous CSRF-protected HTTP behavior and the real bearer authentication filter. Saved-state tests cover restart, failed saves, competing processes and account/guest isolation. UI renders server snapshots with text nodes and polls every five
seconds while visible. JavaScript owns no gameplay rules.
