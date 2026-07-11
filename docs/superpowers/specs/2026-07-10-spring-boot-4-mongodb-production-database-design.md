# Spring Boot 4 MongoDB Production Database Fix

## Problem

After the Spring Boot 4.1 upgrade, the application continues to configure the MongoDB URI and database with the replaced `spring.data.mongodb.*` property keys. The running production application therefore defaults to the `test` database, while the actual production records remain in `christopherbell`.

## Design

Move connection properties to the Spring Boot 4 keys in the local and production profile files:

- `spring.mongodb.uri`
- `spring.mongodb.database`

Keep Spring Data's index setting at `spring.data.mongodb.auto-index-creation`.

Add a regression test that loads each profile configuration and proves the effective MongoDB database is `christopherbell`. Update configuration documentation to show the Spring Boot 4 environment variable name.

## Production Safety

Run focused and broad automated verification before runtime testing. Start the corrected application with the production profile on a non-production port and send a login request for the existing administrator with a deliberately invalid password. The expected response is an authentication failure rather than `RESOURCE_NOT_FOUND`, proving the application found the production account without using a real password.

Only after alternate-port verification passes, stop the Java process bound to port 8080, restart with the production profile and corrected configuration, and repeat the home-page and diagnostic login checks.

## Success Criteria

- Both local and production profiles select the `christopherbell` database under Spring Boot 4.1.
- Automated tests and the broader build pass.
- Alternate-port login lookup finds the administrator account.
- Production restarts successfully on port 8080 and finds the same account.
- Existing MongoDB data remains unchanged except for ordinary application audit or login fields; the diagnostic request uses an invalid password and must not update the account.
