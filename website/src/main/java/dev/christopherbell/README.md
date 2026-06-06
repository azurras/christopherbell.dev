# Website Application

Owns the Spring Boot web application.

## Package Shape

- Feature packages live directly under `dev.christopherbell`: `account`,
  `canesboxtracker`, `location`, `post`, `message`, `notification`, `vehicle`,
  `whatsforlunch`, and similar.
- Each feature package owns its controller, service, repository, mapper, model
  types, and package README when those concepts exist.
- Cross-cutting web infrastructure lives in `configuration`.
- Server-rendered page routing lives in `view`.

## Design Notes

- Prefer feature-first packages over technical layers. A change to a feature
  should usually stay inside that feature package.
- Keep controllers thin. Business rules belong in services; persistence details
  belong behind repositories.
- Keep DTOs close to the feature that owns the API contract.
- Move shared behavior to `cbell-lib` only when more than one feature needs it
  and the abstraction has a stable purpose.

## Update This Doc

Update this README when top-level package ownership or cross-feature dependency
rules change.
