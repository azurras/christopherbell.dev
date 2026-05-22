# Location ZIP Coordinate API Design

## Goal

Create a general Location feature that stores Census ZIP Code Tabulation Area
coordinates in MongoDB, exposes a public ZIP coordinate lookup API, and gives
admins a Back Office action to import or refresh the bundled Census dataset.
What's For Lunch will use this Location data as the ZIP radius origin instead of
owning ZIP-coordinate reference data.

## Decision Summary

- Location is the owner of ZIP/ZCTA coordinate reference data and the Census
  Gazetteer parser.
- The first public Location operation is a single ZIP coordinate lookup, not a
  general geocoder or Location radius search API.
- WFL continues to own restaurant selection, radius filtering, filters, and
  user-facing lunch behavior. It only delegates ZIP-origin coordinate lookup.
- Census data is imported explicitly through an admin operation so dataset
  refreshes are observable and repeatable.
- ZIP coordinates are persisted as reference data before WFL uses them; WFL does
  not keep a second file-backed fallback once it switches to Location.

## Scope

In scope:

- A new `dev.christopherbell.location` feature package.
- Mongo-backed Census ZIP coordinate persistence for any ZIP/ZCTA present in the
  bundled Census dataset.
- An admin import/refresh endpoint that reads the bundled Census Gazetteer file
  and refreshes persisted Census ZIP coordinate rows.
- A public general-purpose ZIP coordinate lookup endpoint.
- A Back Office Location Data operation panel for the admin import/refresh.
- WFL ZIP radius search using the Location ZIP coordinate lookup.

Out of scope:

- Request-time external ZIP or geocoding API calls.
- USPS delivery-area geometry or ZIP+4 delivery point lookups.
- Public bulk ZIP coordinate exports or radius-search APIs in the Location
  feature.
- Admin editing of individual ZIP coordinates.

## Data Source

The initial source is the bundled 2025 Census Gazetteer ZCTA national file:

- `website/src/main/resources/wfl/2025_Gaz_zcta_national.txt`

The import uses:

- `GEOID` as the five-digit ZIP/ZCTA key.
- `INTPTLAT` as latitude.
- `INTPTLONG` as longitude.

The API and documentation must describe this as Census ZIP Code Tabulation Area
coordinate data, not authoritative USPS delivery geometry. The response exposes
the source and source year so consumers can understand that boundary.

The bundled resource is the import source, not a request-time lookup cache.
Public lookups read MongoDB after the admin import has populated it.

## Architecture

### Location Feature

The new Location package owns ZIP coordinate persistence, import parsing, public
lookup behavior, and admin import behavior.

Core units:

- `ZipCoordinate` persistence model keyed by ZIP code.
- `ZipCoordinateRepository` for Mongo persistence.
- `ZipCoordinateGazetteerReader` or equivalent parser for the bundled Census
  file.
- `ZipCoordinateService` for public lookup and import/refresh rules.
- `LocationController` for public and admin Location endpoints.
- Location API detail/import result models in the feature `model` package.

The Location implementation moves the bundled Gazetteer resource from the WFL
resource path into a Location-owned resource path so the new feature owns its
reference data.

The public endpoint and Back Office operation use the same service rules. That
keeps ZIP validation, source metadata, and persistence refresh behavior out of
controllers and browser code.

### WFL Integration

WFL keeps responsibility for restaurant radius search. For ZIP searches it asks
the Location service for one ZIP coordinate, then runs the existing Mongo
coordinate-bounds candidate query and exact service-side distance check for
restaurants.

WFL no longer parses Census ZIP coordinate files or persists ZIP coordinates
itself.

Browser-coordinate WFL requests do not depend on Location data. ZIP-based WFL
requests do depend on Location rows being imported because the Location service
is their only ZIP-origin resolver.

### Back Office Integration

The Back Office `Operations` tab gains a separate Location Data panel. It is not
embedded inside the WFL restaurant panel because the dataset and public endpoint
are general Location behavior.

The panel includes:

- `Import ZIP Coordinates` admin action.
- Result/status area that shows import counts and source year after success.
- Existing Back Office alert/status treatment for failures.

## Persistence Model

The ZIP coordinate Mongo record stores:

- ZIP code identifier.
- Latitude.
- Longitude.
- Source name, set to Census Gazetteer ZCTA for imported Census rows.
- Source year, initially `2025`.
- Created and last-modified audit fields where the local feature patterns make
  those fields useful.

ZIP code is the stable record key for the Census import path so a refresh updates
existing rows instead of creating duplicates.

The import refresh only deletes stale persisted rows that belong to the Census
ZIP coordinate source family. This leaves room for future Location coordinate
sources without deleting unrelated records.

## ZIP Input Rules

ZIP codes stay strings so leading zeroes are preserved.

The lookup and WFL integration share these rules:

- Strip surrounding whitespace before validation.
- Accept `NNNNN`.
- Accept `NNNNN-NNNN` and normalize it to the first five digits.
- Reject blank values, non-numeric five-digit inputs, partial ZIPs, and
  arbitrary suffixes with a `400` path.

Normalization happens before repository lookup. A syntactically valid normalized
ZIP with no imported row is a missing resource, not invalid input.

## APIs

### Public Lookup

`GET /api/location/zip/{zipCode}`

Behavior:

- Public route.
- Accept a five-digit ZIP input.
- Accept ZIP+4 input and normalize it to the first five digits, matching the
  current WFL ZIP input behavior.
- Return `200` when the ZIP exists in imported Location data.
- Return `400` for malformed ZIP input.
- Return `404` for valid ZIP input absent from imported Location data.

Payload shape:

```json
{
  "zipCode": "78701",
  "latitude": 30.271128,
  "longitude": -97.743699,
  "source": "Census Gazetteer ZCTA",
  "sourceYear": 2025
}
```

The app-level response wrapper stays consistent with existing controllers.

The route is intentionally Location-scoped and unversioned for this first
reference-data operation:

- It is not nested under WFL.
- It does not expose import internals to public callers.
- If the response contract later needs a breaking change, that change should add
  a new route rather than silently changing this payload.

### Admin Import And Refresh

`POST /api/location/zip/import/census`

Behavior:

- Admin-only endpoint.
- Parse the bundled Census file before mutating Mongo records.
- Upsert the parsed Census ZIP coordinate rows.
- Count processed, created, updated, and unchanged rows.
- Delete stale persisted Census ZIP coordinate rows not present in the bundled
  source and count deleted rows.
- Return source and source-year metadata with import counts.

Expected import-result fields:

- `processed`
- `created`
- `updated`
- `unchanged`
- `deleted`
- `source`
- `sourceYear`

The import is safe to run repeatedly. Running it again against unchanged bundled
data should report unchanged rows rather than create duplicates.

## Import Lifecycle

The importer is a refresh workflow:

1. Read and validate the complete bundled Gazetteer file.
2. Build the imported Census ZIP coordinate set keyed by normalized five-digit
   ZIP/ZCTA.
3. Compare imported Census rows with persisted Census rows.
4. Save new and changed rows by ZIP key.
5. Delete stale persisted Census rows absent from the bundled source.
6. Return result counts and source metadata.

Parser failure happens before any repository mutation. Parse validation should
fail for missing required columns, malformed coordinate values, malformed
ZIP/ZCTA keys, or an unusable/empty imported dataset.

The refresh is idempotent and rerunnable. If Mongo persistence fails after parse
validation, the admin operation returns failure and a later rerun converges the
Census records to the bundled dataset. The design does not require a Mongo
multi-document transaction for the initial import path.

## Error Handling

The public Location lookup returns:

- `400` when ZIP syntax is invalid.
- `404` when the ZIP syntax is valid but no imported row exists.

The admin import returns failure without partial persistence when the bundled
Gazetteer file cannot be parsed into a complete ZIP coordinate dataset.

WFL ZIP search uses the Location lookup and keeps a client-facing ZIP-coordinate
error when a valid ZIP lacks imported Location data. Browser-coordinate WFL
search is unaffected.

Back Office status should surface import failure without presenting a stale
success summary.

## Security

- The public lookup path is added to public security coverage.
- The import endpoint is protected with the existing admin permission pattern.
- Back Office import calls send the existing admin auth headers.

The Location feature does not store public lookup history or user-provided
locations as part of this design.

## Operational Rollout

The resource move and code deploy do not themselves populate MongoDB. After the
Location importer is available in the deployed app, an admin runs `Import ZIP
Coordinates` from Back Office to seed or refresh the Census rows.

Before that import:

- the public Location lookup returns `404` for syntactically valid ZIPs with no
  persisted row;
- WFL ZIP lookup reports that the ZIP coordinate is unavailable;
- WFL browser geolocation search continues to work.

The Back Office import result is the operator check that rows were processed and
which source year is live.

## Testing

Location service/import tests cover:

- Parsing Census rows.
- Creating rows on first import.
- Updating changed rows.
- Counting unchanged rows.
- Deleting stale Census rows on refresh.
- Avoiding persistence when parsing fails.

Location controller/security tests cover:

- Public successful lookup.
- Public malformed ZIP response.
- Public valid-but-missing ZIP response.
- Admin import success for an admin user.
- Rejection of anonymous and non-admin import attempts.

WFL tests cover:

- ZIP radius search using Location ZIP coordinates.
- Nearby restaurant lookup remaining bounded instead of loading the full
  restaurant collection.

Back Office verification covers:

- The operations panel wiring for the Location import action.
- JavaScript syntax checks for touched browser modules.

## Documentation

Documentation updates include:

- New Location package README.
- Root README for the general Location ZIP API/data overview.
- WFL docs describing Location-owned ZIP radius origins.
- Admin/Back Office docs describing the Location import operation.
- Static JavaScript docs if shared Back Office API wiring changes.
