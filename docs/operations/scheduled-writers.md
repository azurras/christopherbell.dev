# Scheduled Writer Ownership

| Job | Classification | Durable owner | Retry after loss |
|---|---|---|---|
| Canes weekly collection | Mongo lease | `ScheduledCollectorCoordinator` | next schedule/manual run |
| WFL OSM import | Mongo lease | import lease/coordinator | next schedule/startup catch-up |
| WFL daily picks | Mongo lease | `ScheduledCollectorCoordinator` | next request/schedule |
| NHTSA enrichment | Mongo lease | `ScheduledCollectorCoordinator` | next fixed delay |
| Random VIN import | Mongo lease | `ScheduledCollectorCoordinator` | next fixed delay |
| Music radio tick | Mongo lease | `MongoLeaseService` | next tick |
| Music catalog scan | Mongo lease | `ScheduledCollectorCoordinator` | next scan |
| Music metadata cleanup | Mongo lease | `ScheduledCollectorCoordinator` | next cleanup |
| Federation reconcile/deliver | atomic cursor and per-job claims | federation store | next scan/claim expiry |
| Shared Folder maintenance | host lock plus Mongo lease | maintenance service | next pass |
| Post expiration cleanup | tracked scaling work | issues #1278 and #1279 | issue contract |
| Shared Folder media admission/retention | tracked scaling work | issues #1294, #1296, and #1297 | issue contract |

The federation row covers its separate reconcile and delivery schedules. Every other row maps to
one current `@Scheduled` method. Candidate production-profile validation keeps all scheduled
writers disabled through `app.scheduling.enabled=false`.
