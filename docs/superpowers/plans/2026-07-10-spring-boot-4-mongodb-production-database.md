# Spring Boot 4 MongoDB Production Database Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Spring Boot 4.1 select the existing `christopherbell` MongoDB database for local and production profiles and safely restart production after proving account lookup on an alternate port.

**Architecture:** Keep Mongo connection ownership in the profile YAML files, using Spring Boot 4's `spring.mongodb` namespace for URI/database and Spring Data's existing `spring.data.mongodb` namespace for automatic index creation. Protect the configuration contract with a resource-loading test, then validate the real production database on port 8081 before replacing the port 8080 process.

**Tech Stack:** Java 25, Spring Boot 4.1, JUnit 5, AssertJ, Gradle, MongoDB 8.2, PowerShell, WSL2

## Global Constraints

- Preserve all records in the live `christopherbell` database.
- Do not stop port 8080 until the corrected app passes alternate-port verification.
- Keep `spring.data.mongodb.auto-index-creation` in the Spring Data namespace.
- Never use or log the administrator's real password during verification.

---

### Task 1: Lock the Spring Boot 4 Mongo profile contract

**Files:**
- Create: `website/src/test/java/dev/christopherbell/configuration/MongoProfileConfigurationTest.java`
- Modify: `website/src/main/resources/application-local.yml:3-9`
- Modify: `website/src/main/resources/application-prod.yml:3-9`
- Modify: `README.md:52-64`

**Interfaces:**
- Consumes: Spring Boot `YamlPropertySourceLoader` and the two profile resources.
- Produces: effective `spring.mongodb.uri`, `spring.mongodb.database`, and `spring.data.mongodb.auto-index-creation` configuration.

- [ ] **Step 1: Write the failing configuration test**

```java
package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class MongoProfileConfigurationTest {

  @ParameterizedTest
  @ValueSource(strings = {"application-local.yml", "application-prod.yml"})
  void profileUsesSpringBootFourMongoConnectionProperties(String resourceName) throws IOException {
    var sources = new YamlPropertySourceLoader()
        .load(resourceName, new ClassPathResource(resourceName));

    assertThat(sources).isNotEmpty();
    var source = sources.getFirst();
    assertThat(source.getProperty("spring.mongodb.database")).isEqualTo("christopherbell");
    assertThat(source.getProperty("spring.mongodb.uri")).isEqualTo("mongodb://localhost:27017");
    assertThat(source.getProperty("spring.data.mongodb.auto-index-creation")).isEqualTo(true);
    assertThat(Stream.of("spring.data.mongodb.database", "spring.data.mongodb.uri")
        .map(source::getProperty)
        .toList()).containsOnlyNulls();
  }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run from WSL repository root:

```bash
./gradlew --no-daemon :website:test \
  --tests dev.christopherbell.configuration.MongoProfileConfigurationTest
```

Expected: FAIL because `spring.mongodb.database` and `spring.mongodb.uri` are absent.

- [ ] **Step 3: Apply the minimal profile correction**

Use this structure in both profile files, retaining each file's existing server and non-Mongo settings:

```yaml
spring:
  mongodb:
    database: christopherbell
    uri: mongodb://localhost:27017
  data:
    mongodb:
      auto-index-creation: true
```

Update the README environment variable example:

```bash
export SPRING_MONGODB_URI=mongodb://localhost:27017
```

- [ ] **Step 4: Verify GREEN and run the broader build**

```bash
./gradlew --no-daemon :website:test \
  --tests dev.christopherbell.configuration.MongoProfileConfigurationTest
./gradlew --no-daemon :website:build
```

Expected: targeted test and full build both succeed.

- [ ] **Step 5: Commit the durable fix**

```bash
git add README.md \
  website/src/main/resources/application-local.yml \
  website/src/main/resources/application-prod.yml \
  website/src/test/java/dev/christopherbell/configuration/MongoProfileConfigurationTest.java
git commit -m "Fix MongoDB configuration for Spring Boot 4"
```

### Task 2: Validate and restart production safely

**Files:**
- No source files changed.

**Interfaces:**
- Consumes: corrected production profile and live WSL MongoDB at `localhost:27017/christopherbell`.
- Produces: verified alternate-port process followed by a verified production process on port 8080.

- [ ] **Step 1: Start corrected production profile on port 8081**

```bash
SERVER_PORT=8081 ./gradlew --no-daemon :website:bootRun \
  --args='--spring.profiles.active=prod --server.port=8081'
```

Expected: startup completes while existing production remains on port 8080.

- [ ] **Step 2: Verify the account lookup and public page on port 8081**

```bash
curl -i http://localhost:8081/
curl -i -X POST http://localhost:8081/api/accounts/2024-12-15/login \
  -H 'Content-Type: application/json' \
  --data '{"email":"cbell7@icloud.com","password":"diagnostic-invalid-password"}'
```

Expected: home page returns 200. Login returns the invalid-credentials response, not `RESOURCE_NOT_FOUND`, proving the account was loaded from `christopherbell`.

- [ ] **Step 3: Stop the alternate process**

Stop only the process listening on port 8081 and verify port 8080 remains healthy.

- [ ] **Step 4: Restart production with the corrected profile**

Record the PID and command bound to port 8080, stop that Java process only, and restart from `/mnt/a/Projects/christopherbell.dev` with explicit `--spring.profiles.active=prod --server.port=8080` arguments and the existing production environment.

- [ ] **Step 5: Verify production**

```bash
curl -i http://localhost:8080/
curl -i -X POST http://localhost:8080/api/accounts/2024-12-15/login \
  -H 'Content-Type: application/json' \
  --data '{"email":"cbell7@icloud.com","password":"diagnostic-invalid-password"}'
```

Expected: home page returns 200; login finds the account and rejects only the deliberately invalid password; WSL MongoDB still reports 20 account documents.
