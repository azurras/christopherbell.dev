package dev.christopherbell.music.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MusicCatalogResultSupportTest {
  @Test
  void facetSpellingUsesANormalizedKeyAndStableNaturalTieBreak() {
    assertThat(MusicCatalogResultSupport.strings(List.of(
        "paritycase", "Beta", "Paritycase", "alpha", "Alpha")))
        .containsExactly("Alpha", "Beta", "Paritycase");
  }
}
