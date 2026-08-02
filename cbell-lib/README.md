# cbell-lib Module

Reusable Java library that contains shared domain and utility code.

## Building
```bash
./gradlew :cbell-lib:build
```

## Running tests
```bash
./gradlew :cbell-lib:test
```

## Bounded HTTP response bodies

`BoundedResponseBodyHandlers` is the shared JDK-only boundary for HTTP response
bodies whose callers declare a maximum encoded byte count. Its completion stage
stays tied to the full body so the JDK request timeout remains effective after
headers arrive, and it cancels a response before returning oversized content.
`BoundedResponseBodyReader` applies the same return-size contract to an owned
stream, including bounded decompression. Feature clients continue to own status,
timeout, parsing, and domain-error translation.

## Shared pagination and scheduling

Stable cursor encoding lives under `dev.christopherbell.libs.pagination`, while
the reusable Mongo lease and scheduled-collector coordination types live under
`dev.christopherbell.libs.mongo.lease`.

`TestUtil` is published only through the module's Gradle test-fixtures variant;
it is not part of the production library artifact.
