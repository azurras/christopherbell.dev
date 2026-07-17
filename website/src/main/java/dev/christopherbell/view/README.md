# View

Owns server-side routing for HTML pages.

## What Lives Here

- Spring MVC routes that return Thymeleaf templates.
- `account` serves login, signup, password reset, and Void auth pages.
- `content` serves the home gateway, blog, photos, reports, Back Office, and The Bell pages.
- `content` also serves `/command-center` as a public, data-free HTML shell. The
  template contains no telemetry, logs, credentials, challenges, or action data;
  its browser module reveals and populates the console only after a fresh admin
  role check, while the private API independently enforces authorization.
- `content` serves `/shared` as another public, data-free shell. Its module redirects unauthenticated
  visitors and obtains listing data only from the separately protected shared-folder read API after
  a fresh effective-read check.
- `tools` serves Raising Canes Box Index, VIN Decoder, and ZIP Coordinates pages.
- `voidroutes` serves Void, profile, messages, notifications, public user feeds, and post pages.
- `wfl` serves What's For Lunch, WFL restaurant profiles, favorites, and top-rated lists.
- Route names used by the frontend navigation.
- Dynamic public profile and post routes attach canonical social preview URLs for link unfurlers.
- The `/` home route renders `index.html` as a Void gateway, with `/void` as the primary action and a live Signal Rail showing the five most active posts from the public feed.
- The shared frontend nav groups tool pages under the Tools dropdown.
- HTML templates use `templates/fragments/social-preview.html` for Open Graph, Twitter card, canonical URL, and description metadata.
- The default large social card image lives at `static/images/previews/christopherbell-dev.png` and should stay 1200x630.

## Update This Doc

Update this README when page routes, template names, public page access assumptions, or link preview metadata behavior change.
