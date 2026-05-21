package dev.christopherbell.whatsforlunch.restaurant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.notification.NotificationService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionVoteRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WhatsForLunchSessionServiceTest {
  @Mock private AccountRepository accountRepository;
  @Mock private NotificationService notificationService;
  @Mock private PermissionService permissionService;
  @Mock private RestaurantMapper restaurantMapper;
  @Mock private RestaurantFavoriteRepository restaurantFavoriteRepository;
  @Mock private RestaurantRatingRepository restaurantRatingRepository;
  @Mock private RestaurantRepository restaurantRepository;
  @Mock private WhatsForLunchSessionRepository sessionRepository;
  @InjectMocks private WhatsForLunchSessionService service;

  @Test
  @DisplayName("Create session stores creator, invited members, fixed restaurants, and sends invites")
  void testCreateSession_whenValid_savesSessionAndNotifiesInvitees() throws Exception {
    var creator = account("owner-id", "owner");
    var friend = account("friend-id", "friend");
    var first = restaurant("restaurant-1");
    var second = restaurant("restaurant-2");
    var third = restaurant("restaurant-3");
    var request = new WhatsForLunchSessionCreateRequest(
        List.of("restaurant-1", "restaurant-2", "restaurant-3"),
        List.of("friend"));

    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(creator));
    when(accountRepository.findByUsernameIgnoreCase("friend")).thenReturn(Optional.of(friend));
    when(restaurantRepository.findAllById(eq(List.of("restaurant-1", "restaurant-2", "restaurant-3"))))
        .thenReturn(List.of(first, second, third));
    when(sessionRepository.save(any(WhatsForLunchSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(restaurantMapper.toRestaurantDetail(any(Restaurant.class)))
        .thenAnswer(invocation -> RestaurantStub.getRestaurantDetailStub(invocation.<Restaurant>getArgument(0).getId()));

    var result = service.createSession(request);

    assertEquals(List.of("owner", "friend"), result.participantUsernames());
    assertEquals(3, result.restaurants().size());
    var captor = ArgumentCaptor.forClass(WhatsForLunchSession.class);
    verify(sessionRepository).save(captor.capture());
    assertEquals(List.of("owner-id", "friend-id"), captor.getValue().getParticipantAccountIds());
    assertEquals(List.of("restaurant-1", "restaurant-2", "restaurant-3"), captor.getValue().getRestaurantIds());
    verify(notificationService).createWhatsForLunchSessionInvite(any(WhatsForLunchSession.class), eq(creator), eq(friend));
  }

  @Test
  @DisplayName("Create session can start with only the creator for link sharing")
  void testCreateSession_whenNoInvitees_savesCreatorOnlySession() throws Exception {
    var creator = account("owner-id", "owner");
    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(creator));
    when(restaurantRepository.findAllById(eq(List.of("restaurant-1", "restaurant-2", "restaurant-3"))))
        .thenReturn(List.of(restaurant("restaurant-1"), restaurant("restaurant-2"), restaurant("restaurant-3")));
    when(sessionRepository.save(any(WhatsForLunchSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(restaurantMapper.toRestaurantDetail(any(Restaurant.class)))
        .thenAnswer(invocation -> RestaurantStub.getRestaurantDetailStub(invocation.<Restaurant>getArgument(0).getId()));

    var result = service.createSession(
        new WhatsForLunchSessionCreateRequest(
            List.of("restaurant-1", "restaurant-2", "restaurant-3"),
            List.of()));

    assertEquals(List.of("owner"), result.participantUsernames());
  }

  @Test
  @DisplayName("Join session adds the current user from a shared link")
  void testJoinSession_whenNotParticipant_addsCurrentUser() throws Exception {
    var friend = account("friend-id", "friend");
    var session = WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("owner-id")
        .createdByUsername("owner")
        .participantAccountIds(List.of("owner-id"))
        .participantUsernamesByAccountId(Map.of("owner-id", "owner"))
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .build();

    when(permissionService.getSelfId()).thenReturn("friend-id");
    when(accountRepository.findById("friend-id")).thenReturn(Optional.of(friend));
    when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
    when(sessionRepository.save(any(WhatsForLunchSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(restaurantRepository.findAllById(eq(List.of("restaurant-1", "restaurant-2", "restaurant-3"))))
        .thenReturn(List.of(restaurant("restaurant-1"), restaurant("restaurant-2"), restaurant("restaurant-3")));
    when(restaurantMapper.toRestaurantDetail(any(Restaurant.class)))
        .thenAnswer(invocation -> RestaurantStub.getRestaurantDetailStub(invocation.<Restaurant>getArgument(0).getId()));

    var result = service.joinSession("session-1");

    assertEquals(List.of("owner", "friend"), result.participantUsernames());
    var captor = ArgumentCaptor.forClass(WhatsForLunchSession.class);
    verify(sessionRepository).save(captor.capture());
    assertEquals(List.of("owner-id", "friend-id"), captor.getValue().getParticipantAccountIds());
    assertEquals("friend", captor.getValue().getParticipantUsernamesByAccountId().get("friend-id"));
  }

  @Test
  @DisplayName("Vote updates the caller's restaurant choice and returns vote usernames")
  void testVote_whenParticipant_updatesVote() throws Exception {
    var owner = account("owner-id", "owner");
    var session = WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("owner-id")
        .createdByUsername("owner")
        .participantAccountIds(List.of("owner-id", "friend-id"))
        .participantUsernamesByAccountId(Map.of("owner-id", "owner", "friend-id", "friend"))
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .votesByAccountId(Map.of("friend-id", "restaurant-2"))
        .build();

    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(owner));
    when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
    when(sessionRepository.save(any(WhatsForLunchSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(restaurantRepository.findAllById(eq(List.of("restaurant-1", "restaurant-2", "restaurant-3"))))
        .thenReturn(List.of(restaurant("restaurant-1"), restaurant("restaurant-2"), restaurant("restaurant-3")));
    when(restaurantMapper.toRestaurantDetail(any(Restaurant.class)))
        .thenAnswer(invocation -> RestaurantStub.getRestaurantDetailStub(invocation.<Restaurant>getArgument(0).getId()));

    var result = service.vote("session-1", new WhatsForLunchSessionVoteRequest("restaurant-1"));

    assertEquals("restaurant-1", result.myVoteRestaurantId());
    assertEquals(List.of("owner"), result.votesByRestaurant().get("restaurant-1"));
    assertEquals(List.of("friend"), result.votesByRestaurant().get("restaurant-2"));
  }

  @Test
  @DisplayName("Update restaurants replaces session picks and clears previous votes")
  void testUpdateRestaurants_whenParticipant_replacesRestaurantsAndClearsVotes() throws Exception {
    var owner = account("owner-id", "owner");
    var session = WhatsForLunchSession.builder()
        .id("session-1")
        .createdByAccountId("owner-id")
        .createdByUsername("owner")
        .participantAccountIds(List.of("owner-id", "friend-id"))
        .participantUsernamesByAccountId(Map.of("owner-id", "owner", "friend-id", "friend"))
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .votesByAccountId(Map.of("owner-id", "restaurant-1", "friend-id", "restaurant-2"))
        .build();
    var request = new WhatsForLunchSessionRestaurantsRequest(
        List.of("restaurant-4", "restaurant-5", "restaurant-6"));

    when(permissionService.getSelfId()).thenReturn("owner-id");
    when(accountRepository.findById("owner-id")).thenReturn(Optional.of(owner));
    when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
    when(restaurantRepository.findAllById(eq(List.of("restaurant-4", "restaurant-5", "restaurant-6"))))
        .thenReturn(List.of(restaurant("restaurant-4"), restaurant("restaurant-5"), restaurant("restaurant-6")));
    when(sessionRepository.save(any(WhatsForLunchSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(restaurantMapper.toRestaurantDetail(any(Restaurant.class)))
        .thenAnswer(invocation -> RestaurantStub.getRestaurantDetailStub(invocation.<Restaurant>getArgument(0).getId()));

    var result = service.updateRestaurants("session-1", request);

    assertEquals(List.of("restaurant-4", "restaurant-5", "restaurant-6"),
        result.restaurants().stream().map(detail -> detail.getId()).toList());
    assertEquals(List.of(), result.votesByRestaurant().get("restaurant-4"));
    var captor = ArgumentCaptor.forClass(WhatsForLunchSession.class);
    verify(sessionRepository).save(captor.capture());
    assertEquals(List.of("restaurant-4", "restaurant-5", "restaurant-6"), captor.getValue().getRestaurantIds());
    assertEquals(Map.of(), captor.getValue().getVotesByAccountId());
  }

  @Test
  @DisplayName("Vote hides sessions from non-participants")
  void testVote_whenNotParticipant_throwsNotFound() {
    var account = account("other-id", "other");
    var session = WhatsForLunchSession.builder()
        .id("session-1")
        .participantAccountIds(List.of("owner-id"))
        .restaurantIds(List.of("restaurant-1", "restaurant-2", "restaurant-3"))
        .build();
    when(permissionService.getSelfId()).thenReturn("other-id");
    when(accountRepository.findById("other-id")).thenReturn(Optional.of(account));
    when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));

    assertThrows(ResourceNotFoundException.class,
        () -> service.vote("session-1", new WhatsForLunchSessionVoteRequest("restaurant-1")));
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
