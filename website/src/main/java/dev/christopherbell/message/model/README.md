# Message Models

Owns direct-message persistence and API records.

## What Lives Here

- `Message` is the Mongo-backed direct message entity.
- `MessageCreateRequest` carries send-message input.
- `MessageDetail` is the per-message response shape.
- `ConversationSummary` is the inbox row response shape.

## Design Notes

- Messages store sender and recipient account ids because usernames can change.
- Conversation summaries are read models for inbox rendering and should stay
  small enough for list views.
- Read state belongs on messages so opening a conversation can mark incoming
  messages read without a separate join table.

## Update This Doc

Update this README when message fields, read-state behavior, or conversation
summary fields change.
