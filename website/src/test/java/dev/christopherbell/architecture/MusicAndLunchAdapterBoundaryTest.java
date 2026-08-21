package dev.christopherbell.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import dev.christopherbell.configuration.mongo.domain.DomainCollectionManifest;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Closed Task 4 adapter, kind, target, and index contract. */
class MusicAndLunchAdapterBoundaryTest {
  private static final Map<String, String> ADAPTER_PORTS = Map.ofEntries(
      Map.entry("dev.christopherbell.music.catalog.MongoMusicTrackRepository",
          "dev.christopherbell.music.catalog.MusicTrackRepository"),
      Map.entry("dev.christopherbell.music.catalog.MongoMusicCatalogQueryRepository",
          "dev.christopherbell.music.catalog.MusicCatalogQueryRepository"),
      Map.entry("dev.christopherbell.music.library.MongoMusicPlaylistRepository",
          "dev.christopherbell.music.library.MusicPlaylistRepository"),
      Map.entry("dev.christopherbell.music.metadata.MongoMusicMetadataEditRepository",
          "dev.christopherbell.music.metadata.MusicMetadataEditRepository"),
      Map.entry("dev.christopherbell.music.radio.MongoMusicRadioHistoryRepository",
          "dev.christopherbell.music.radio.MusicRadioHistoryRepository"),
      Map.entry("dev.christopherbell.music.radio.MongoMusicRuntimeStateRepository",
          "dev.christopherbell.music.radio.MusicRuntimeStateRepository"),
      Map.entry("dev.christopherbell.music.security.MongoMusicAccessAttemptRepository",
          "dev.christopherbell.music.security.MusicAccessAttemptRepository"),
      Map.entry("dev.christopherbell.whatsforlunch.restaurant.MongoRestaurantRepository",
          "dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository"),
      Map.entry("dev.christopherbell.whatsforlunch.restaurant.vote.MongoRestaurantVoteRepository",
          "dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository"),
      Map.entry("dev.christopherbell.whatsforlunch.restaurant.favorite.MongoRestaurantFavoriteRepository",
          "dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository"),
      Map.entry("dev.christopherbell.whatsforlunch.restaurant.preference.MongoWhatsForLunchPreferenceRepository",
          "dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository"),
      Map.entry("dev.christopherbell.whatsforlunch.restaurant.session.MongoWhatsForLunchSessionRepository",
          "dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository"),
      Map.entry("dev.christopherbell.whatsforlunch.restaurant.MongoDailyLunchPicksRepository",
          "dev.christopherbell.whatsforlunch.restaurant.DailyLunchPicksRepository"),
      Map.entry("dev.christopherbell.whatsforlunch.restaurant.MongoRestaurantImportStateRepository",
          "dev.christopherbell.whatsforlunch.restaurant.RestaurantImportStateRepository"));

  private static final List<String> MANUAL_OWNERS = List.of(
      "dev.christopherbell.whatsforlunch.restaurant.RestaurantDuplicateQueryRepository",
      "dev.christopherbell.whatsforlunch.restaurant.RestaurantInventoryQueryRepository",
      "dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewStore",
      "dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore",
      "dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryRepository");

  @Test
  void everyRepositoryPortHasOneConcreteManifestBackedAdapter() throws Exception {
    for (var entry : ADAPTER_PORTS.entrySet()) {
      var adapter = Class.forName(entry.getKey());
      var port = Class.forName(entry.getValue());
      assertThat(port.isAssignableFrom(adapter)).as(entry.getKey()).isTrue();
      assertThat(adapter.getDeclaredConstructor(DomainMongoOperationsFactory.class))
          .as(entry.getKey()).isNotNull();
    }
  }

  @Test
  void everyManualOwnerUsesTheFixedFactoryBoundary() throws Exception {
    for (var ownerName : MANUAL_OWNERS) {
      var owner = Class.forName(ownerName);
      assertThat(owner.getDeclaredConstructors())
          .as(ownerName)
          .anySatisfy(constructor -> assertThat(List.of(constructor.getParameterTypes()))
              .contains(DomainMongoOperationsFactory.class));
    }
  }

  @Test
  void manifestFixesAllMusicAndLunchKindsTargetsAndCriticalIndexSemantics() {
    assertThat(DomainCollectionManifest.ALL_KINDS.stream()
        .filter(kind -> kind.collection().equals("music"))
        .map(DomainCollectionManifest.KindDefinition::kind))
        .containsExactlyInAnyOrder(
            "music_track", "music_playlist", "music_metadata_edit", "music_runtime_state",
            "music_radio_history", "music_access_attempt");
    assertThat(DomainCollectionManifest.ALL_KINDS.stream()
        .filter(kind -> kind.collection().equals("whatsforlunch"))
        .map(DomainCollectionManifest.KindDefinition::kind))
        .containsExactlyInAnyOrder(
            "restaurant", "vote", "favorite", "preference", "session", "daily_picks",
            "import_state", "import_preview");

    var restaurantName = index("restaurant", "restaurant__normalizedName_asc");
    assertThat(restaurantName.unique()).isTrue();
    assertThat(restaurantName.sparse()).isFalse();
    assertThat(restaurantName.partialFilterExpression().toString())
        .contains("_kind=restaurant", "payload.normalizedName", "$exists=true");

    var voteIdentity = index("vote", "vote__restaurant_account_unique");
    assertThat(voteIdentity.unique()).isTrue();
    assertThat(voteIdentity.partialFilterExpression()).containsEntry("_kind", "vote");

    var previewTtl = index("import_preview", "import_preview__restaurant_import_preview_expiry");
    assertThat(previewTtl.expireAfterSeconds()).contains(0L);
    assertThat(previewTtl.keys()).extracting(DomainCollectionManifest.IndexKey::path)
        .containsExactly("payload.expiresOn");
  }

  private static DomainCollectionManifest.IndexDefinition index(String kind, String name) {
    return DomainCollectionManifest.forKind(kind).orElseThrow().indexes().stream()
        .filter(index -> index.name().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
