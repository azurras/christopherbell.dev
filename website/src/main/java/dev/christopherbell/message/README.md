# Message

Owns direct user-to-user messaging.

## What Lives Here

- Sending messages between accounts.
- Message API behavior that backs the `/messages` Signal Bridge page, while
  browser assets own the conversation rail and private thread rendering.
- Conversation listing and individual conversation retrieval.
- Read-state updates for incoming messages.
- Message notification creation through the notification package.
- Message DTOs and persistence models under `model`.

## Update This Doc

Update this README when message permissions, conversation shape, read behavior, notification behavior, or message API contracts change.
