package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.location.zip.ZipCoordinateService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.selection.ApprovalWeightedRestaurantSelector;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteQueryRepository;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RestaurantVoteServiceContractTest {
  @Mock private DailyLunchPicksRepository dailyLunchPicksRepository;
  @Mock private Clock clock;
  @Mock private OpenStreetMapRestaurantClient openStreetMapRestaurantClient;
  @Mock private PermissionService permissionService;
  @Mock private RestaurantMapper restaurantMapper;
  @Mock private RestaurantFavoriteRepository restaurantFavoriteRepository;
  @Mock private RestaurantDuplicateQueryRepository restaurantDuplicateQueries;
  @Mock private RestaurantInventoryQueryRepository restaurantInventoryQueries;
  @Mock private RestaurantVoteRepository restaurantVoteRepository;
  @Mock private RestaurantVoteQueryRepository restaurantVoteQueryRepository;
  @Mock private ApprovalWeightedRestaurantSelector restaurantSelector;
  @Mock private RestaurantRepository restaurantRepository;
  @Mock private dev.christopherbell.libs.lease.ScheduledCollectorCoordinator scheduledCollectors;
  @Mock private WhatsForLunchPreferenceRepository whatsForLunchPreferenceRepository;
  @Mock private ZipCoordinateService zipCoordinateService;
  @Mock private WflProperties wflProperties;
  @InjectMocks private RestaurantService service;

  @Test
  void setsAnUpVoteAndPreservesTheExistingCreationTimestamp() throws Exception {
    var restaurant = Restaurant.builder().id("restaurant-1").build();
    var detail = RestaurantDetail.builder().id("restaurant-1").build();
    var existing = RestaurantVote.builder().id("vote-1").restaurantId("restaurant-1")
        .accountId("account-1").vote(RestaurantVoteValue.DOWN)
        .createdOn(Instant.parse("2026-08-02T12:00:00Z")).build();
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(clock.instant()).thenReturn(Instant.parse("2026-08-03T12:00:00Z"));
    when(restaurantRepository.findById("restaurant-1")).thenReturn(Optional.of(restaurant));
    when(restaurantVoteRepository.findByRestaurantIdAndAccountId("restaurant-1", "account-1"))
        .thenReturn(Optional.of(existing));
    when(restaurantMapper.toRestaurantDetail(restaurant)).thenReturn(detail);
    when(restaurantVoteRepository.findByRestaurantIdIn(List.of("restaurant-1")))
        .thenReturn(List.of(existing));

    service.voteRestaurant("restaurant-1", new RestaurantVoteRequest("UP"));

    var saved = ArgumentCaptor.forClass(RestaurantVote.class);
    verify(restaurantVoteRepository).save(saved.capture());
    assertThat(saved.getValue().getVote()).isEqualTo(RestaurantVoteValue.UP);
    assertThat(saved.getValue().getCreatedOn()).isEqualTo(Instant.parse("2026-08-02T12:00:00Z"));
    assertThat(saved.getValue().getLastUpdatedOn()).isEqualTo(Instant.parse("2026-08-03T12:00:00Z"));
  }

  @Test
  void rejectsNumericAndUnknownVotesBeforeARepositoryWrite() {
    assertThatThrownBy(() -> service.voteRestaurant("restaurant-1", new RestaurantVoteRequest(5)))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Restaurant vote must be UP or DOWN.");
    assertThatThrownBy(() -> service.voteRestaurant("restaurant-1", new RestaurantVoteRequest("MAYBE")))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Restaurant vote must be UP or DOWN.");
  }
}
