# View

Owns server-side routing for HTML pages.

## What Lives Here

- Spring MVC routes that return Thymeleaf templates.
- Public page routes such as home, Void, What's For Lunch, VIN Decoder, auth pages, profile, reports, and Back Office.
- Route names used by the frontend navigation.
- Dynamic public profile and post routes attach canonical social preview URLs for link unfurlers.
- The shared frontend nav groups tool pages under the Tools dropdown.
- HTML templates use `templates/fragments/social-preview.html` for Open Graph, Twitter card, canonical URL, and description metadata.
- The default large social card image lives at `static/images/previews/christopherbell-dev.png` and should stay 1200x630.

## Update This Doc

Update this README when page routes, template names, public page access assumptions, or link preview metadata behavior change.
