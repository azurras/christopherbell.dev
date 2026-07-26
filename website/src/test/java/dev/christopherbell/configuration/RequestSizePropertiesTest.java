package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class RequestSizePropertiesTest {
  @Test
  void missingValueUsesOneMegabyteDefault() {
    assertThat(new RequestSizeProperties(null).defaultMax())
        .isEqualTo(DataSize.ofMegabytes(1));
  }

  @Test
  void explicitTypedValueIsPreserved() {
    assertThat(new RequestSizeProperties(DataSize.ofKilobytes(128)).defaultMax())
        .isEqualTo(DataSize.ofKilobytes(128));
  }

  @Test
  void zeroAndNegativeLimitsAreRejectedAtTheConfigurationBoundary() {
    assertThatThrownBy(() -> new RequestSizeProperties(DataSize.ofBytes(0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.request-size.default-max");
    assertThatThrownBy(() -> new RequestSizeProperties(DataSize.ofBytes(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("app.request-size.default-max");
  }
}
