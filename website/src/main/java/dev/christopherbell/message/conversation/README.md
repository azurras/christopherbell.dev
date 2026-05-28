# Message Conversation

Owns direct-message reads.

## What Lives Here

- `ConversationService` loads individual conversations, marks incoming unread messages as read, and builds conversation summaries.

## Design Notes

This subfeature exists so read-side conversation behavior stays separate from send-message validation and notification handoff.
