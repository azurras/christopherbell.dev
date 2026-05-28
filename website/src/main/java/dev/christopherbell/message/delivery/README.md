# Message Delivery

Owns direct-message creation.

## What Lives Here

- `MessageDeliveryService` validates send requests, checks sender status, creates messages, and hands notification creation to the notification package.

## Design Notes

This subfeature exists so write-side messaging rules stay separate from conversation reads and read-state updates.

