# Message

Owns direct user-to-user messaging.

## What Lives Here

- `MessageService`, a thin facade that keeps the controller-facing service surface stable.
- Sending messages between accounts under `delivery`.
- Message API behavior that backs the `/messages` Signal Bridge page, while
  browser assets own the conversation rail, recipient autocomplete, and private
  thread rendering.
- Conversation listing, individual conversation retrieval, and read-state updates under `conversation`.
- Mongo indexes cover conversation ordering, participant inbox ordering, and
  unread sender/recipient counts. Production rollout should account for index
  creation on existing message data.
- Send permissions reject suspended sender accounts, so suspended users cannot
  continue sending direct messages with an existing session.
- Send permissions reject messages when either participant has blocked the other
  through the account trust package.
- Message notification creation through the notification package.
- Message DTOs and persistence models under `model`.

## Update This Doc

Update this README when message permissions, conversation shape, read behavior, notification behavior, or message API contracts change.
