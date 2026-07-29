package dev.christopherbell.configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SharedFolderCatalogPropertiesTest {
  @Test
  void rejectsNonPositiveOrUnboundedCatalogBudgets() {
    assertThatThrownBy(() -> new SharedFolderCatalogProperties(
        0, 10, 4, Duration.ofSeconds(1), Duration.ofSeconds(1), 25))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderCatalogProperties(
        100, 10, 4, Duration.ZERO, Duration.ofSeconds(1), 25))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SharedFolderCatalogProperties(
        100, 10, 4, Duration.ofSeconds(1), Duration.ofSeconds(1), 101))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
