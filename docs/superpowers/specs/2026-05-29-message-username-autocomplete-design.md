# Message Username Autocomplete Design

## Goal

Make the Messages page suggest possible recipients as a signed-in user types in
the "Open a private signal" handle field.

## Behavior

- The page fetches username suggestions after the handle input changes.
- Suggestions update after each typed character, debounced to avoid one request
  per keypress burst.
- Empty input clears the suggestion list.
- Selecting a suggestion opens that conversation.
- Submitted free-form handles still work, so users can type a full username and
  press Open without using the suggestion list.

## Backend Contract

Add a protected endpoint:

`GET /api/accounts/2025-09-14/search?username=<prefix>&limit=<n>`

The endpoint requires `USER` authority and returns public-safe suggestion DTOs:

```json
[
  { "username": "alice" }
]
```

The service searches active accounts by username prefix, sorts by username, caps
the result size, and excludes the current authenticated account.

## Frontend Contract

`messages.js` owns the message-page DOM behavior. It will:

- Use `API.accounts.search(username, limit)` for suggestions.
- Render suggestions in a listbox below the handle input.
- Keep rendering helpers exported for Node tests.
- Sanitize every username before inserting HTML.

## Testing

- Controller tests cover authorized search and unauthenticated rejection.
- Service tests cover prefix search, active-user filtering, self exclusion, and
  invalid/blank prefixes.
- JavaScript tests cover suggestion markup and the template autocomplete slot.
