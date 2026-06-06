# Account Trust

Owns user-level mute and block relationships.

## How It Works

- `MUTE` hides the target account from the current user's feeds.
- `BLOCK` hides the target account and prevents direct messages in either
  direction.
- Relationships are unique per owner, target, and type.
- Self mute/block requests are rejected.

## Update This Doc

Update this README when trust relationship types, visibility rules, or messaging
rules change.
