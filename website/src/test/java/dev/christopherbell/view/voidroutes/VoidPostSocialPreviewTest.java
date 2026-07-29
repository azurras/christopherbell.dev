package dev.christopherbell.view.voidroutes;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.post.model.PostFeedItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VoidPostSocialPreviewTest {
  private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

  @Test
  void from_normalizesTextAndDescribesRemainingTemporaryLife() {
    var post = PostFeedItem.builder()
        .username("alice")
        .text("  Hello\n\tfrom   the Void  ")
        .expiresOn(Instant.parse("2026-07-29T12:00:00Z"))
        .build();

    var preview = VoidPostSocialPreview.from(post, NOW);

    assertThat(preview.title()).isEqualTo("@alice in the Void");
    assertThat(preview.description())
        .isEqualTo("Hello from the Void · Temporary thread with 24h remaining. "
            + "Keep it alive or let it disappear.");
  }

  @Test
  void from_boundsExcerptByUnicodeCodePointsWithoutSplittingEmoji() {
    var post = PostFeedItem.builder()
        .username("alice")
        .text("😀".repeat(200))
        .expiresOn(Instant.parse("2026-07-28T12:01:00Z"))
        .build();

    var preview = VoidPostSocialPreview.from(post, NOW);

    assertThat(preview.description())
        .startsWith("😀".repeat(159) + "… · Temporary thread with 1m remaining.");
  }

  @Test
  void from_usesSafeFallbacksForBlankPersistedFields() {
    var post = PostFeedItem.builder().username(" ").text("\n\t").build();

    var preview = VoidPostSocialPreview.from(post, NOW);

    assertThat(preview.title()).isEqualTo("A post in the Void");
    assertThat(preview.description())
        .isEqualTo("A temporary Void thread · Temporary thread. "
            + "Keep it alive or let it disappear.");
  }
}
