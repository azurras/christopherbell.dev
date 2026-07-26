package dev.christopherbell.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Typed ordinary-request body limit; feature-owned streaming limits remain separate. */
@ConfigurationProperties("app.request-size")
@Validated
public record RequestSizeProperties(DataSize defaultMax) {
  private static final DataSize DEFAULT_MAX = DataSize.ofMegabytes(1);

  public RequestSizeProperties {
    defaultMax = defaultMax == null ? DEFAULT_MAX : defaultMax;
    if (defaultMax.toBytes() <= 0) {
      throw new IllegalArgumentException("app.request-size.default-max must be positive");
    }
  }
}
