# Void Messages Redesign Design

## Decision Summary

Redesign the `/messages` page around the approved "Signal Bridge" direction.
Messages should remain a practical two-pane private messenger, but the visual
language should match the Void: dark signal surfaces, teal/gold accents, compact
conversation scanning, and integrated message composition.

The work is presentation-focused. It should not change message persistence,
message permissions, notification behavior, or the message API contract.

## Goals

- Make Messages feel like part of the Void instead of a light Bootstrap panel.
- Preserve the efficient desktop two-pane layout: conversations on the left,
  active thread on the right.
- Improve visual hierarchy for the selected conversation, unread counts, message
  direction, and composer state.
- Keep the page usable on mobile without overlapping controls or clipped text.
- Reuse the existing vanilla JavaScript flow and backend APIs.

## Non-Goals

- No backend model, repository, service, or controller changes.
- No real-time websocket behavior.
- No group messages.
- No message search or filtering.
- No new frontend framework, npm dependency, build step, or bundler.

## Page Structure

The page keeps the current high-level structure:

- `messages.html` remains the server-rendered template for `/messages`.
- `messages.js` remains the page module for loading conversations, opening a
  conversation, sending messages, updating the character counter, and redirecting
  anonymous users to login.
- `main.css` owns the visual treatment for the Messages page.

The existing hero should be removed or replaced by a compact in-page header so
the experience starts at the messenger surface. The main page should use a
Void-style shell class, such as `void-messages-page`, scoped to this page.

## Signal Rail

The left pane becomes a "signal rail" for conversations.

Each conversation row should show:

- Avatar initial.
- Username.
- Latest message preview.
- Last-message time.
- Unread count when present.
- Active state when it is the selected conversation.

Visual behavior:

- Rows use dark surfaces and low-opacity borders.
- The active row gets a teal signal strip and stronger background.
- Unread counts use a compact accent badge.
- Long usernames, previews, and timestamps must truncate without expanding the
  layout.

The new-conversation form remains above the conversation list. It should feel
like a signal lookup: `@username` input plus an Open button, styled with dark
Void controls.

## Private Signal Thread

The right pane becomes the active private signal thread.

The thread header should show:

- Conversation label.
- Active username or "Pick a conversation".
- Profile link when a conversation is selected.

The message list should remain scrollable and show message direction clearly:

- Incoming messages use a cool dark teal/blue bubble.
- Outgoing messages use a muted gold bubble.
- Metadata remains visible but quiet.
- Message text preserves line breaks and wraps long words/links.
- Empty states use Void-toned copy and surfaces instead of white cards.

The composer should live at the bottom of the thread panel and feel integrated:

- Dark textarea.
- Character counter pill.
- Send button.
- Clear disabled/focus states through existing browser behavior and CSS.

## JavaScript Behavior

Keep the existing browser behavior:

- Redirect anonymous visitors through the existing login redirect helper.
- Load conversations from `API.messages.conversations`.
- Open conversations from `API.messages.conversation(username)`.
- Send messages through `API.messages.base`.
- Update the `with` query param for the active conversation.
- Keep `appendTextWithMentionLinks` for user-authored message text.

JavaScript changes should be minimal and only support rendering needs. Acceptable
changes include small class names, status copy, or more specific empty/loading
markup. Avoid changing the API flow unless a rendering bug requires it.

## Responsive Behavior

Desktop:

- Two columns with a narrower signal rail and wider thread pane.
- The thread pane should have a stable minimum height.
- Conversation rows and message bubbles must not overflow their panes.

Mobile:

- Collapse to one column.
- Conversation list appears above the thread.
- Active conversation remains visually obvious.
- Message bubbles can use more width than desktop but still leave direction
  cues.
- Composer controls wrap cleanly.

## Accessibility

- Keep semantic form elements and real buttons/links.
- Preserve `role="alert"` for page errors.
- Focus states must remain visible on conversation rows, buttons, and inputs.
- Do not rely on color alone for active/unread state; active styling should also
  use border/shape/background changes.
- Text contrast must remain readable on dark surfaces.

## Documentation

Update documentation with the implementation:

- `website/src/main/java/dev/christopherbell/message/README.md` should mention
  that the Messages page renders as a Void Signal Bridge and describe the
  frontend/backend boundary.
- `website/src/main/resources/static/js/README.md` should mention the
  `messages.js` page responsibility if behavior or markup ownership changes.
- `website/src/main/resources/static/css/README.md` should mention Messages page
  styling ownership in `main.css`.

## Testing And Verification

Run:

```bash
node --check website/src/main/resources/static/js/messages.js
git diff --check -- website/src/main/resources/templates/messages.html website/src/main/resources/static/js/messages.js website/src/main/resources/static/css/main.css website/src/main/java/dev/christopherbell/message/README.md website/src/main/resources/static/js/README.md website/src/main/resources/static/css/README.md
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest
```

If browser tooling is available, verify `/messages` at desktop and mobile
widths:

- No large old hero dominates the page.
- Signal rail and private thread render as a cohesive dark Void surface.
- Active row, unread state, empty state, and composer are readable.
- Long names/messages wrap or truncate correctly.
- Mobile layout has no overlaps.

## Open Decisions

- The profile link remains in the thread header because it is already part of
  the current page and is useful.
- The page remains login-required because private messages require an account.
