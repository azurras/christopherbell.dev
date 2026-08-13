package dev.christopherbell.configuration.persistence;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed persistence selection that rejects an absent or unsupported transition backend at startup. */
@Validated
@ConfigurationProperties("app.persistence")
public record PersistenceBackendProperties(@NotNull PersistenceBackend backend) {}
