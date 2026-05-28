# WFL Sessions

Owns shared What's For Lunch sessions.

## What Lives Here

- `WhatsForLunchSessionService` creates shareable WFL sessions, joins members from links, records votes, and replaces the shared three-restaurant slate.
- `WhatsForLunchSessionRepository` stores session documents and recent-session lookups by participant.

## Design Notes

- Session endpoints stay on `RestaurantController` for now so public API paths remain stable during the package refactor.
- The service verifies the signed-in account through `PermissionService` because sessions are member-only collaboration state.
- Session responses include restaurant details, vote counts, participant usernames, and the caller's current vote.
- Invitations are delivered through the notification feature when a creator invites other usernames.

## Update This Doc

Update this README when shared-session membership, voting, invitation, persistence, or response-shaping behavior changes.
