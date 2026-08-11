package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedAggregation;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Query;

class RestaurantBoundedQueryRepositoryTest {
  private KindScopedMongoOperations<Restaurant> restaurants;
  private RestaurantInventoryQueryRepository inventory;
  private RestaurantDuplicateQueryRepository duplicates;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    var factory = mock(DomainMongoOperationsFactory.class);
    restaurants = (KindScopedMongoOperations<Restaurant>) mock(KindScopedMongoOperations.class);
    when(factory.forType(Restaurant.class)).thenReturn(restaurants);
    inventory = new RestaurantInventoryQueryRepository(factory);
    duplicates = new RestaurantDuplicateQueryRepository(factory);
  }

  @Test
  void inventoryUsesNormalizedFiltersStableSortAndOneExtraRow() {
    var first = restaurant("1", "alpha", "austin", "tx");
    var second = restaurant("2", "beta", "austin", "tx");
    var extra = restaurant("3", "gamma", "austin", "tx");
    var query = ArgumentCaptor.forClass(Query.class);
    when(restaurants.find(query.capture(), any(Pageable.class)))
        .thenReturn(List.of(first, second, extra));
    when(restaurants.count(any(Query.class))).thenReturn(3L);

    var page = inventory.find(" a ", " Austin ", " TX ", null, 2);

    assertThat(page.items()).containsExactly(first, second);
    assertThat(page.nextCursor()).isNotBlank();
    assertThat(page.total()).isEqualTo(3);
    assertThat(query.getValue().getLimit()).isEqualTo(3);
    assertThat(query.getValue().getQueryObject().toString())
        .contains("dedupeKey", "searchCity=austin", "searchState=tx");
  }

  @Test
  void boundedRepositoriesRejectInvalidPageSizesBeforeMongoAccess() {
    assertThatThrownBy(() -> inventory.find(null, null, null, null, 0))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(() -> duplicates.find(null, 101))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    verifyNoInteractions(restaurants);
  }

  @Test
  void duplicatePreviewAggregatesKeysThenFetchesOnlyPagedMembers() {
    when(restaurants.aggregate(any(KindScopedAggregation.class),
        org.mockito.ArgumentMatchers.eq(Document.class)))
        .thenReturn(List.of(
            new Document("_id", "alpha").append("count", 2),
            new Document("_id", "beta").append("count", 3),
            new Document("_id", "gamma").append("count", 2)));
    var alphaOne = restaurant("1", "alpha", "austin", "tx");
    var alphaTwo = restaurant("2", "alpha", "dallas", "tx");
    var betaOne = restaurant("3", "beta", "austin", "tx");
    var query = ArgumentCaptor.forClass(Query.class);
    when(restaurants.find(query.capture(), any(Pageable.class)))
        .thenReturn(List.of(alphaOne, alphaTwo, betaOne));

    var page = duplicates.find(null, 2);

    assertThat(page.keys()).containsExactly("alpha", "beta");
    assertThat(page.nextCursor()).isEqualTo("beta");
    assertThat(page.members()).containsExactly(alphaOne, alphaTwo, betaOne);
    assertThat(query.getValue().getQueryObject().toString())
        .contains("dedupeKey", "alpha", "beta")
        .doesNotContain("gamma");
    verify(restaurants).aggregate(any(KindScopedAggregation.class),
        org.mockito.ArgumentMatchers.eq(Document.class));
  }

  private static Restaurant restaurant(
      String id, String normalizedName, String city, String state) {
    return Restaurant.builder()
        .id(id)
        .name(normalizedName)
        .normalizedName(normalizedName)
        .dedupeKey(normalizedName)
        .searchCity(city)
        .searchState(state)
        .build();
  }
}
