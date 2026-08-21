# Message Conversation

Owns direct-message reads.

## What Lives Here

- `ConversationService` loads individual conversations, marks incoming unread messages as read, and builds conversation summaries.
- `ConversationQueryRepository` batches unread counts for every visible
  conversation into one backend-native grouped query before summaries are mapped.

## Design Notes

This subfeature exists so read-side conversation behavior stays separate from send-message validation and notification handoff.
Conversation-list query groups stay constant as the returned list grows: one visible-thread
aggregation, one signed-in account lookup, one peer-account batch lookup, and one grouped
unread-count aggregation.
