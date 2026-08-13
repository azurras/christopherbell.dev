package dev.christopherbell.post.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Identical bounded-cleanup assertions run against both persistence engines. */
interface PostLinkPreviewCleanupParityContract {
  Instant CUTOFF = Instant.parse("2026-08-13T15:00:00Z");

  PostLinkPreviewCacheRepository parityRepository();

  @BeforeEach
  default void removeParityFixtures() {
    parityRepository().deleteExpired(Instant.parse("9999-12-31T23:59:59Z"), 100);
  }

  @Test
  default void parityCleanupIsObservableBatchLimitedAndIdempotent() {
    parityRepository().save(PostLinkPreviewCacheEntry.failure(
        "https://cleanup-parity-a.example", "FAILED", CUTOFF.minusSeconds(10),
        CUTOFF.minusSeconds(2)));
    parityRepository().save(PostLinkPreviewCacheEntry.failure(
        "https://cleanup-parity-b.example", "FAILED", CUTOFF.minusSeconds(10),
        CUTOFF.minusSeconds(1)));
    parityRepository().save(PostLinkPreviewCacheEntry.failure(
        "https://cleanup-parity-fresh.example", "FAILED", CUTOFF,
        CUTOFF.plusSeconds(60)));

    assertThat(parityRepository().deleteExpired(CUTOFF, 1)).isOne();
    assertThat(parityRepository().deleteExpired(CUTOFF, 1)).isOne();
    assertThat(parityRepository().deleteExpired(CUTOFF, 1)).isZero();
    assertThat(parityRepository().findById("https://cleanup-parity-fresh.example")).isPresent();
  }

  @Test
  default void parityCleanupRejectsAnUnboundedBatch() {
    assertThatThrownBy(() -> parityRepository().deleteExpired(CUTOFF, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }
}
