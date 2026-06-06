# Notification Preference

Owns per-account notification category settings.

## How It Works

- `NotificationPreference` stores one Mongo document per account in
  `notification_preferences`.
- Missing preference documents behave as all categories enabled so existing users
  continue receiving notifications until they opt out.
- `NotificationPreferenceService` resolves the current user's settings for the
  preferences API and exposes `shouldDeliver` so delivery code can skip disabled
  categories before storing in-app notifications.

## Update This Doc

Update this README when notification preference categories, persistence shape, or
delivery rules change.
