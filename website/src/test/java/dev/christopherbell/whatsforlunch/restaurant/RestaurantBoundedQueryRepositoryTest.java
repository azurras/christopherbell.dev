package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class RestaurantBoundedQueryRepositoryTest {
  @Mock private MongoTemplate mongo;
  @Mock private MongoCollection<Document> collection;
  @Mock private AggregateIterable<Document> aggregate;
  private RestaurantInventoryQueryRepository inventory;
  private RestaurantDuplicateQueryRepository duplicates;

  @BeforeEach
  void setUp() {
    inventory = new RestaurantInventoryQueryRepository(mongo);
    duplicates = new RestaurantDuplicateQueryRepository(mongo);
  }

  @Test
  void inventoryUsesNormalizedFiltersStableSortAndOneExtraRow() {
    var first = restaurant("1", "alpha", "austin", "tx");
    var second = restaurant("2", "beta", "austin", "tx");
    var extra = restaurant("3", "gamma", "austin", "tx");
    when(mongo.find(any(Query.class), eq(Restaurant.class), eq("whatsforlunch")))
        .thenReturn(List.of(first, second, extra));
    when(mongo.count(any(Query.class), eq("whatsforlunch"))).thenReturn(3L);

    var page = inventory.find(" a ", " Austin ", " TX ", null, 2);

    assertThat(page.items()).containsExactly(first, second);
    assertThat(page.nextCursor()).isNotBlank();
    assertThat(page.total()).isEqualTo(3);
    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Restaurant.class), eq("whatsforlunch"));
    assertThat(query.getValue().getLimit()).isEqualTo(3);
    assertThat(query.getValue().getQueryObject().toString())
        .contains("dedupeKey", "searchCity=austin", "searchState=tx");
    assertThat(query.getValue().getSortObject().toString())
        .contains("dedupeKey=1", "_id=1");
  }

  @Test
  void boundedRepositoriesRejectInvalidPageSizesBeforeMongoAccess() {
    assertThatThrownBy(() -> inventory.find(null, null, null, null, 0))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThatThrownBy(() -> duplicates.find(null, 101))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    org.mockito.Mockito.verifyNoInteractions(mongo);
  }

  @Test
  void duplicatePreviewAggregatesKeysThenFetchesOnlyPagedMembers() {
    when(mongo.getCollection("whatsforlunch")).thenReturn(collection);
    when(collection.aggregate(any(List.class))).thenReturn(aggregate);
    doAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      var target = (java.util.Collection<Document>) invocation.getArgument(0);
      target.add(new Document("_id", "alpha").append("count", 2));
      target.add(new Document("_id", "beta").append("count", 3));
      target.add(new Document("_id", "gamma").append("count", 2));
      return target;
    }).when(aggregate).into(any(ArrayList.class));
    var alphaOne = restaurant("1", "alpha", "austin", "tx");
    var alphaTwo = restaurant("2", "alpha", "dallas", "tx");
    var betaOne = restaurant("3", "beta", "austin", "tx");
    when(mongo.find(any(Query.class), eq(Restaurant.class), eq("whatsforlunch")))
        .thenReturn(List.of(alphaOne, alphaTwo, betaOne));

    var page = duplicates.find(null, 2);

    assertThat(page.keys()).containsExactly("alpha", "beta");
    assertThat(page.nextCursor()).isEqualTo("beta");
    assertThat(page.members()).containsExactly(alphaOne, alphaTwo, betaOne);
    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(query.capture(), eq(Restaurant.class), eq("whatsforlunch"));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("dedupeKey", "alpha", "beta")
        .doesNotContain("gamma");
  }

  private static Restaurant restaurant(
      String id,
      String normalizedName,
      String city,
      String state
  ) {
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
