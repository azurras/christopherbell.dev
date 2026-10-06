# Survive

Java browser adaptation of [azurras/survive](https://github.com/azurras/survive),
inspected at `24d50be92e1609e6e983b7dda9231bd77777b3d1` (GPL-3.0).
The ported gameplay in this package is distributed under GPL-3.0; the upstream
license is served at `/licenses/survive-GPL-3.0.txt`. No Python process is used.

## One authoritative world

`SurviveService` owns exactly one `SurviveWorld` per application process. The
world contains separate survivors and shared shelters, boats and recent events.
All joins, reads and actions serialize under the service monitor. Commands and
immutable snapshots are independent of HTTP so a future multiplayer transport
can reuse the same engine. This version already lets browser survivors cooperate
by building and using the same camp; it has no chat or matchmaking.

Survivors retain private health, wood, food, strength, stamina and combat encounters.
The service caps survivors at 1000, expires them after two idle hours on the next
request, caps camp structures at 1000 each and retains only 30 events. Polling
counts as activity. Restarting a survivor replaces only that identity, preserving
the world. The world is ephemeral: a process restart resets everything. Multiple
server replicas would each have their own world; shared persistence and distributed
command serialization are required before scaling this feature across replicas.

## Browser contract

- `GET /api/survive/v1/game`: current survivor snapshot, or 204 before joining/after expiry.
- `POST /api/survive/v1/game`: `{ "name": "Chris" }` joins or replaces this survivor.
- `POST /api/survive/v1/actions`: `{ "action": "GATHER", "revision": 0 }` applies a command.
- `POST /api/survive/v1/gifts`: `{ "recipientId": "public-uuid", "resource": "WOOD", "quantity": 2, "revision": 0 }` gives wood or food to another survivor.

Each snapshot includes a public `survivorId` separate from the private cookie and
eligible `recipients` with public ID and name. The existing `survivors` names list
is retained. Public IDs cannot authorize actions. The giver and recipient must
both be exploring at camp; gifts of 1–10 wood or food require sufficient supplies
and recipient space. All validation precedes the atomic transfer under the world
monitor. Both inventories and revisions change together, each player sees a
message and the journal records the gift. Receiving never extends idle expiry.
Replaced or expired IDs return 404; unavailable gifts return 400, stale sender
revisions 409. The UI retains recipient and amount selections during polling.

These exact method/path combinations are public; existing CSRF and rate limiting
remain enabled. A random opaque HttpOnly, SameSite=Strict session cookie identifies
the survivor, scoped to `/api/survive/v1`; HTTPS makes it Secure. Tokens are never
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
`SurviveWorldTest` covers mechanics; controller tests cover anonymous CSRF-protected
HTTP behavior. UI renders server snapshots with text nodes and polls every five
seconds while visible. JavaScript owns no gameplay rules.
