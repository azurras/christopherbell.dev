package dev.christopherbell.whatsforlunch.restaurant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionVoteRequest;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.WflSessionConflictException;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionMutationStore;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionRepository;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WhatsForLunchSessionServiceTest {
  private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");
  private static final List<String> ORIGINAL_IDS =
      List.of("restaurant-1", "restaurant-2", "restaurant-3");
  private static final List<String> REPLACEMENT_IDS =
      List.of("restaurant-4", "restaurant-5", "restaurant-6");

  @Mock private AccountRepository accountRepository;
  @Mock private Clock clock;
  @Mock private NotificationDeliveryService notificationDeliveryService;
  @Mock private PermissionService permissionService;
  @Mock private RestaurantMapper restaurantMapper;
  @Mock private RestaurantFavoriteRepository restaurantFavoriteRepository;
  @Mock private RestaurantVoteRepository restaurantVoteRepository;
  @Mock private RestaurantRepository restaurantRepository;
  @Mock private WhatsForLunchSessionMutationStore mutations;
  @Mock private WhatsForLunchSessionRepository sessionRepository;
  @Mock private WflProperties wflProperties;
  @Mock private WflProperties.Sessions sessionProperties;
  @InjectMocks private WhatsForLunchSessionService service;

  @BeforeEach
  void setUpPolicy() {
    lenient().when(clock.instant()).thenReturn(NOW);
    lenient().when(wflProperties.getSessions()).thenReturn(sessionProperties);
    lenient().when(sessionProperties.getMaxMembers()).thenReturn(20);
    lenient().when(sessionProperties.getActiveLifetime()).thenReturn(Duration.ofHours(24));
    lenient().when(sessionProperties.getArchiveLifetime()).thenReturn(Duration.ofDays(30));
  }

  @Test
  void createStoresBoundedLifecycleAndNotifiesInvitees() throws Exception {
    var owner = account("owner-id", "owner");
    var friend = account("friend-id", "friend");
    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(owner));
    when(accountRepository.findByUsernameIgnoreCase("friend")).thenReturn(Optional.of(friend));
    stubRestaurants(ORIGINAL_IDS);
    when(sessionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.createSession(
        new WhatsForLunchSessionCreateRequest(ORIGINAL_IDS, List.of("friend")));

    assertThat(result.participantUsernames()).containsExactly("owner", "friend");
    assertThat(result.active()).isTrue();
    assertThat(result.canChangeRestaurants()).isTrue();
    var saved = ArgumentCaptor.forClass(WhatsForLunchSession.class);
    verify(sessionRepository).save(saved.capture());
    assertThat(saved.getValue().getActiveUntil()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    assertThat(saved.getValue().getDeleteOn())
        .isEqualTo(NOW.plus(Duration.ofHours(24)).plus(Duration.ofDays(30)));
    assertThat(saved.getValue().getRevision()).isZero();
    verify(notificationDeliveryService)
        .createWhatsForLunchSessionInvite(any(), eq(owner), eq(friend));
  }

  @Test
  void createRejectsTwentyInviteesBecauseCreatorCountsTowardCap() throws Exception {
    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(account("owner-id", "owner")));
    stubRestaurants(ORIGINAL_IDS);
    var invitees = java.util.stream.IntStream.range(0, 20)
        .mapToObj(index -> "member" + index)
        .toList();

    assertThatThrownBy(() -> service.createSession(
        new WhatsForLunchSessionCreateRequest(ORIGINAL_IDS, invitees)))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void joinUsesAtomicStoreAndReturnsJoinedMember() throws Exception {
    when(permissionService.getSelfId()).thenReturn("friend-id");
    when(accountRepository.findById("friend-id")).thenReturn(Optional.of(account("friend-id", "friend")));
    var joined = session(List.of("owner-id", "friend-id"),
        Map.of("owner-id", "owner", "friend-id", "friend"));
    when(mutations.join("session-1", "friend-id", "friend", NOW, 20))
        .thenReturn(result(WhatsForLunchSessionMutationStore.Status.UPDATED, joined));
    stubRestaurants(ORIGINAL_IDS);

    var result = service.joinSession("session-1");

    assertThat(result.participantUsernames()).containsExactly("owner", "friend");
    verify(mutations).join("session-1", "friend-id", "friend", NOW, 20);
  }

  @Test
  void voteUsesAtomicStoreAndHidesNonParticipant() throws Exception {
    when(permissionService.getSelfId()).thenReturn("other-id");
    when(accountRepository.findById("other-id")).thenReturn(Optional.of(account("other-id", "other")));
    when(mutations.vote("session-1", "other-id", "restaurant-1", NOW))
        .thenReturn(result(WhatsForLunchSessionMutationStore.Status.NOT_PARTICIPANT, session()));

    assertThatThrownBy(() -> service.vote(
        "session-1", new WhatsForLunchSessionVoteRequest("restaurant-1")))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void expiredVoteReturnsStableConflictCode() throws Exception {
    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(account("owner-id", "owner")));
    when(mutations.vote("session-1", "owner-id", "restaurant-1", NOW))
        .thenReturn(result(WhatsForLunchSessionMutationStore.Status.EXPIRED, session()));

    assertThatThrownBy(() -> service.vote(
        "session-1", new WhatsForLunchSessionVoteRequest("restaurant-1")))
        .isInstanceOfSatisfying(WflSessionConflictException.class,
            conflict -> assertThat(conflict.code()).isEqualTo("WFL_SESSION_EXPIRED"));
  }

  @Test
  void archivedSessionPastDeletionDeadlineIsHiddenWhileTtlCleanupCatchesUp() throws Exception {
    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id"))
        .thenReturn(Optional.of(account("owner-id", "owner")));
    var expiredArchive = session();
    expiredArchive.setDeleteOn(NOW);
    when(sessionRepository.findById("session-1")).thenReturn(Optional.of(expiredArchive));

    assertThatThrownBy(() -> service.getSession("session-1"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void hostResetUsesExpectedRevisionAndClearsVotes() throws Exception {
    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(account("owner-id", "owner")));
    stubRestaurants(REPLACEMENT_IDS);
    var reset = session();
    reset.setRestaurantIds(REPLACEMENT_IDS);
    reset.setVotesByAccountId(Map.of());
    reset.setRevision(8);
    var request = new WhatsForLunchSessionRestaurantsRequest(REPLACEMENT_IDS, 7);
    when(mutations.resetRestaurants("session-1", "owner-id", "owner", request, NOW))
        .thenReturn(result(WhatsForLunchSessionMutationStore.Status.UPDATED, reset));

    var detail = service.updateRestaurants("session-1", request);

    assertThat(detail.revision()).isEqualTo(8);
    assertThat(detail.restaurants()).extracting("id").containsExactlyElementsOf(REPLACEMENT_IDS);
    verify(mutations).resetRestaurants("session-1", "owner-id", "owner", request, NOW);
  }

  @Test
  void participantCannotResetRestaurants() throws Exception {
    when(permissionService.getSelfId()).thenReturn("friend-id");
    when(accountRepository.findById("friend-id")).thenReturn(Optional.of(account("friend-id", "friend")));
    stubRestaurants(REPLACEMENT_IDS);
    var request = new WhatsForLunchSessionRestaurantsRequest(REPLACEMENT_IDS, 7);
    when(mutations.resetRestaurants("session-1", "friend-id", "friend", request, NOW))
        .thenReturn(result(WhatsForLunchSessionMutationStore.Status.NOT_HOST, session()));

    assertThatThrownBy(() -> service.updateRestaurants("session-1", request))
        .isInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 25})
  void sessionHistoryUsesOneHydrationQueryGroupAtEveryBoundedPageSize(int sessionCount)
      throws Exception {
    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(account("owner-id", "owner")));
    var sessions = java.util.stream.IntStream.range(0, sessionCount)
        .mapToObj(index -> {
          var value = session();
          value.setId("session-" + index);
          return value;
        })
        .toList();
    when(sessionRepository
        .findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
            eq("owner-id"), eq(NOW), any(Pageable.class))).thenReturn(sessions);
    stubRestaurants(ORIGINAL_IDS);
    when(restaurantVoteRepository.findByRestaurantIdIn(ORIGINAL_IDS)).thenReturn(List.of());
    when(restaurantFavoriteRepository.findByRestaurantIdInAndAccountId(ORIGINAL_IDS, "owner-id"))
        .thenReturn(List.of());

    assertThat(service.getMySessions(sessionCount)).hasSize(sessionCount);

    verify(restaurantRepository, times(1)).findAllById(ORIGINAL_IDS);
    verify(restaurantVoteRepository, times(1)).findByRestaurantIdIn(ORIGINAL_IDS);
    verify(restaurantFavoriteRepository, times(1))
        .findByRestaurantIdInAndAccountId(ORIGINAL_IDS, "owner-id");
  }

  private void stubRestaurants(List<String> ids) {
    var restaurants = ids.stream().map(WhatsForLunchSessionServiceTest::restaurant).toList();
    lenient().when(restaurantRepository.findAllById(eq(ids))).thenReturn(restaurants);
    lenient().when(restaurantMapper.toRestaurantDetail(any(Restaurant.class)))
        .thenAnswer(invocation -> RestaurantStub.getRestaurantDetailStub(
            invocation.<Restaurant>getArgument(0).getId()));
  }

  private static WhatsForLunchSessionMutationStore.Result result(
      WhatsForLunchSessionMutationStore.Status status,
      WhatsForLunchSession session
  ) {
    return new WhatsForLunchSessionMutationStore.Result(status, session);
  }

  private static WhatsForLunchSession session() {
    return session(List.of("owner-id"), Map.of("owner-id", "owner"));
  }

  private static WhatsForLunchSession session(
      List<String> participantIds,
      Map<String, String> usernames
  ) {
    return WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("owner-id")
        .createdByUsername("owner")
        .participantAccountIds(participantIds)
        .participantUsernamesByAccountId(usernames)
        .restaurantIds(ORIGINAL_IDS)
        .votesByAccountId(Map.of())
        .revision(7)
        .activeUntil(NOW.plusSeconds(60))
        .deleteOn(NOW.plus(Duration.ofDays(30)))
        .build();
  }

  private static Account account(String id, String username) {
    return Account.builder().id(id).username(username).build();
  }

  private static Restaurant restaurant(String id) {
    var restaurant = RestaurantStub.getRestaurantStub(id);
    restaurant.setName("Restaurant " + id);
    return restaurant;
  }
}
