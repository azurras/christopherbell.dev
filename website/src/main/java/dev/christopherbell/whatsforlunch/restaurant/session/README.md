# WFL Sessions

Owns shared What's For Lunch sessions.

## What Lives Here

- `WhatsForLunchSessionService` creates shareable WFL sessions, joins members from links, records votes, and lets only the creator replace the shared three-restaurant slate.
- `WhatsForLunchSessionMutationStore` applies capped joins, targeted votes, and revision-checked resets as atomic MongoDB updates.
- `WhatsForLunchSessionRepository` stores session documents and recent-session lookups by participant.

## Design Notes

- Session endpoints stay on `RestaurantController` for now so public API paths remain stable during the package refactor.
- The service verifies the signed-in account through `PermissionService` because sessions are member-only collaboration state.
- Session responses include restaurant details, vote counts, participant usernames, and the caller's current vote.
- Invitations are delivered through the notification feature when a creator invites other usernames.
- Membership is capped at 20 total members. Stable mutation conflicts distinguish
  full, expired, and concurrently changed sessions.
- Sessions are active for 24 hours, archived read-only for 30 additional days,
  and removed through the `deleteOn` TTL index. Reset audit history retains the
  latest 100 entries and an all-time count.
- Session list hydration batches restaurant, vote, and favorite reads across
  the entire bounded 25-session page.

## Update This Doc

Update this README when shared-session membership, voting, invitation, persistence, or response-shaping behavior changes.
