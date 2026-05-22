# Location ZIP Coordinate API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store Census ZIP coordinate data in MongoDB, publish a public general Location ZIP lookup API, expose an admin Back Office import/refresh action, and make WFL ZIP radius search use the persisted Location data.

**Architecture:** A new `dev.christopherbell.location` feature owns ZIP coordinate models, import parsing, persistence, and HTTP endpoints. Admin import reads the bundled Census Gazetteer file from a Location-owned resource path, refreshes Census ZIP records in MongoDB, and returns counts. WFL delegates ZIP coordinate resolution to `ZipCoordinateService` and keeps restaurant-radius logic local.

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Data MongoDB, Thymeleaf Back Office shell, vanilla JavaScript ES modules, JUnit 5, Mockito, MockMvc.

---

## File Map

- Create `website/src/main/java/dev/christopherbell/location/**` for Location controller, service, repository, parser, model DTOs, and feature README.
- Move the Census Gazetteer resource from `website/src/main/resources/wfl/` to `website/src/main/resources/location/`.
- Modify WFL service/tests/docs so ZIP origins come from Location persistence.
- Modify `SecurityConfig`, `static/js/lib/api.js`, `back-office.html`, `back-office.js`, and docs for public Location lookup and Back Office import.

### Task 1: Define ZIP Coordinate Persistence And Import Tests

**Files:**
- Create: `website/src/test/java/dev/christopherbell/location/ZipCoordinateGazetteerReaderTest.java`
- Create: `website/src/test/java/dev/christopherbell/location/ZipCoordinateServiceTest.java`
- Create: `website/src/main/java/dev/christopherbell/location/model/ZipCoordinate.java`
- Create: `website/src/main/java/dev/christopherbell/location/ZipCoordinateRepository.java`
- Create: `website/src/main/java/dev/christopherbell/location/ZipCoordinateGazetteerReader.java`
- Create: `website/src/main/java/dev/christopherbell/location/ZipCoordinateService.java`
- Create: `website/src/main/java/dev/christopherbell/location/model/ZipCoordinateDetail.java`
- Create: `website/src/main/java/dev/christopherbell/location/model/ZipCoordinateImportResult.java`

- [ ] **Step 1: Write failing parser and service tests**

```java
@Test
void readsCensusGazetteerRows() {
  var rows = reader.read(resource);
  assertEquals("78701", rows.getFirst().getZipCode());
}

@Test
void importRefreshCreatesUpdatesLeavesUnchangedAndDeletesStaleCensusRows() {
  var result = service.importCensusZipCoordinates();
  assertEquals(3, result.getProcessed());
  assertEquals(1, result.getCreated());
  assertEquals(1, result.getUpdated());
  assertEquals(1, result.getUnchanged());
  assertEquals(1, result.getDeleted());
}
```

- [ ] **Step 2: Run focused red tests**

Run:

```powershell
.\gradlew.bat :website:test --tests dev.christopherbell.location.ZipCoordinateGazetteerReaderTest --tests dev.christopherbell.location.ZipCoordinateServiceTest --console=plain
```

Expected: fail because the Location ZIP classes do not exist.

- [ ] **Step 3: Implement persistence, parser, lookup validation, and import refresh**

```java
@Document("location_zip_coordinates")
public class ZipCoordinate {
  @Id private String zipCode;
  private double latitude;
  private double longitude;
  private String source;
  private int sourceYear;
}
```

The service reads all Census rows before persistence, upserts by ZIP, counts changed/unchanged rows, and deletes stale Census rows only after the complete parse succeeds.

- [ ] **Step 4: Run focused green tests**

Run the focused test command from Step 2.

### Task 2: Expose Location ZIP APIs And Security Coverage

**Files:**
- Create: `website/src/main/java/dev/christopherbell/location/LocationController.java`
- Create: `website/src/test/java/dev/christopherbell/location/LocationControllerTest.java`
- Create: `website/src/test/java/dev/christopherbell/location/LocationControllerSecurityTest.java`
- Modify: `website/src/main/java/dev/christopherbell/configuration/SecurityConfig.java`
- Modify: `website/src/test/java/dev/christopherbell/configuration/SecurityConfigTest.java`

- [ ] **Step 1: Write failing controller and security tests**

```java
@Test
void publicZipLookupReturnsCoordinates() throws Exception {
  mockMvc.perform(get("/api/location/zip/78701"))
      .andExpect(status().isOk());
}

@Test
void anonymousZipImportIsRejected() throws Exception {
  mockMvc.perform(post("/api/location/zip/import/census"))
      .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: Run focused red API tests**

Run:

```powershell
.\gradlew.bat :website:test --tests dev.christopherbell.location.LocationControllerTest --tests dev.christopherbell.location.LocationControllerSecurityTest --tests dev.christopherbell.configuration.SecurityConfigTest --console=plain
```

Expected: fail because controller routes and the public security matcher are missing.

- [ ] **Step 3: Implement public lookup and admin import endpoints**

```java
@GetMapping(value = "/zip/{zipCode}", produces = MediaType.APPLICATION_JSON_VALUE)
public ResponseEntity<Response<ZipCoordinateDetail>> getZipCoordinate(@PathVariable String zipCode) { ... }

@PostMapping(value = "/zip/import/census", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("@permissionService.hasAuthority('ADMIN')")
public ResponseEntity<Response<ZipCoordinateImportResult>> importCensusZipCoordinates() { ... }
```

Add `GET:/api/location/zip/**` to public security coverage and keep the import protected.

- [ ] **Step 4: Run focused green API tests**

Run the focused API test command from Step 2.

### Task 3: Make WFL Use Location ZIP Coordinates

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/RestaurantService.java`
- Modify: `website/src/test/java/dev/christopherbell/whatsforlunch/restaurant/RestaurantServiceTest.java`
- Delete: `website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/ZipCodeCoordinate.java`
- Delete: `website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/ZipCodeCoordinateLookup.java`
- Delete: `website/src/test/java/dev/christopherbell/whatsforlunch/restaurant/ZipCodeCoordinateLookupTest.java`

- [ ] **Step 1: Update the failing WFL service test**

```java
when(zipCoordinateService.getZipCoordinate("78701"))
    .thenReturn(ZipCoordinateDetail.builder()
        .zipCode("78701")
        .latitude(30.2672)
        .longitude(-97.7431)
        .build());
```

- [ ] **Step 2: Run focused red WFL tests**

Run:

```powershell
.\gradlew.bat :website:test --tests dev.christopherbell.whatsforlunch.restaurant.RestaurantServiceTest --console=plain
```

Expected: fail while WFL still depends on the old in-memory ZIP lookup.

- [ ] **Step 3: Inject the Location service and delete WFL ZIP lookup ownership**

Replace the WFL ZIP origin lookup with `ZipCoordinateService`, keep coordinate-bounds restaurant queries, and keep exact distance filtering unchanged.

- [ ] **Step 4: Run focused green WFL tests**

Run the focused WFL test command from Step 2.

### Task 4: Add The Back Office Import Operation

**Files:**
- Modify: `website/src/main/resources/templates/back-office.html`
- Modify: `website/src/main/resources/static/js/lib/api.js`
- Modify: `website/src/main/resources/static/js/back-office.js`
- Modify: `website/src/main/resources/static/js/README.md`

- [ ] **Step 1: Add Location API path and Operations panel**

```javascript
location: {
  importCensusZipCoordinates: '/api/location/zip/import/census',
}
```

Add a `Location Data` operation panel with `data-operation="location-zip-import"` and a `locationOperationStatus` result container.

- [ ] **Step 2: Wire the Back Office import action**

```javascript
async function importZipCoordinates(button) {
  const result = await fetchJson(API.location.importCensusZipCoordinates, {
    method: 'POST',
    headers: authHeaders(),
  });
  renderOperationResult(locationOperationStatus, resultSummary(result, labels), 'success');
}
```

- [ ] **Step 3: Check browser syntax**

Run:

```powershell
node --check website/src/main/resources/static/js/lib/api.js
node --check website/src/main/resources/static/js/back-office.js
```

Expected: syntax checks exit 0.

### Task 5: Move Data Resource, Update Docs, And Verify

**Files:**
- Move: `website/src/main/resources/wfl/2025_Gaz_zcta_national.txt` to `website/src/main/resources/location/2025_Gaz_zcta_national.txt`
- Move or update: `website/src/main/resources/wfl/README.md`
- Create: `website/src/main/java/dev/christopherbell/location/README.md`
- Modify: `README.md`
- Modify: `website/src/main/java/dev/christopherbell/admin/README.md`
- Modify: `website/src/main/java/dev/christopherbell/whatsforlunch/README.md`
- Modify: `website/src/main/java/dev/christopherbell/whatsforlunch/restaurant/README.md`

- [ ] **Step 1: Move the Census resource into Location ownership**

Update the parser resource path and data README to `location/`.

- [ ] **Step 2: Document the public Location API, admin import, and WFL dependency**

Include the source/year boundary and the Back Office import workflow.

- [ ] **Step 3: Run diff hygiene and full build**

Run:

```powershell
git diff --check
.\gradlew.bat :website:build --console=plain
```

Expected: exit 0.

- [ ] **Step 4: Refresh the local JAR and smoke check**

Restart the built JAR on port `8081`, verify:

- `GET /wfl` returns `200`.
- Admin import UI renders in Back Office shell.
- Public ZIP lookup behaves according to imported DB state.
