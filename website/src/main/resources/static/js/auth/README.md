# Auth JavaScript

Owns browser behavior for account access pages.

## What Lives Here

- `login.js` submits credentials and stores the returned auth state.
- `signup.js` creates a new account from the public signup form.
- `forgot-password.js` starts the password reset flow.
- `reset-password.js` completes the password reset flow from a tokenized link.

## How It Works

- Each file is a page entry module loaded by its matching Thymeleaf template.
- Modules read only their page's form fields and alert container.
- API requests use shared helpers from `../lib/api.js` so endpoint paths and
  response parsing stay consistent with the rest of the app.
- Successful auth actions update local auth state and redirect through normal
  browser navigation. Login and signup preserve safe local `redirect` query
  targets so protected pages, feed actions, reports, messages, profiles, and
  shared WFL session links can return users to the page that requested login.

## Design Notes

- Keep auth page scripts explicit rather than building a generic form framework.
  There are only a few flows, and each has different success behavior.
- Error text is rendered into the page alert element so failures are visible
  without relying on modal or browser alert state.

## Update This Doc

Update this README when auth page behavior, redirect handling, reset-token
handling, stored auth state, or auth API contracts change.
