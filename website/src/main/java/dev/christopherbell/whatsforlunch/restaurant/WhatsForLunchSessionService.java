package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.notification.NotificationService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRating;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionVoteRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Coordinates shared WFL sessions, invitations, and votes. */
@RequiredArgsConstructor
@Service
public class WhatsForLunchSessionService {
  private static final int SESSION_PICK_COUNT = 3;
  private static final int MAX_INVITEES = 20;

  private final AccountRepository accountRepository;
  private final NotificationService notificationService;
  private final PermissionService permissionService;
  private final RestaurantMapper restaurantMapper;
  private final RestaurantFavoriteRepository restaurantFavoriteRepository;
  private final RestaurantRatingRepository restaurantRatingRepository;
  private final RestaurantRepository restaurantRepository;
  private final WhatsForLunchSessionRepository sessionRepository;

  /**
   * Creates a session from exactly three current WFL picks and invites existing users.
   */
  public WhatsForLunchSessionDetail createSession(WhatsForLunchSessionCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    var creator = getSelfAccount();
    var restaurantIds = normalizeRestaurantIds(request == null ? null : request.restaurantIds());
    var restaurants = getRestaurantsInRequestedOrder(restaurantIds);
    var participants = resolveParticipants(creator, request == null ? null : request.invitedUsernames());
    var now = Instant.now();
    var session = WhatsForLunchSession.builder()
        .id(UUID.randomUUID().toString())
        .createdByAccountId(creator.getId())
        .createdByUsername(creator.getUsername())
        .participantAccountIds(participants.stream().map(Account::getId).toList())
        .participantUsernamesByAccountId(usernamesByAccountId(participants))
        .restaurantIds(restaurantIds)
        .votesByAccountId(new LinkedHashMap<>())
        .createdOn(now)
        .lastUpdatedOn(now)
        .build();

    var saved = sessionRepository.save(session);
    participants.stream()
        .filter(participant -> !participant.getId().equals(creator.getId()))
        .forEach(participant -> notificationService.createWhatsForLunchSessionInvite(saved, creator, participant));
    return toDetail(saved, creator.getId(), restaurants);
  }

  /**
   * Lists recent sessions that include the caller.
   */
  public List<WhatsForLunchSessionDetail> getMySessions(int limit)
      throws ResourceNotFoundException {
    var self = getSelfAccount();
    var pageSize = Math.max(1, Math.min(limit, 25));
    return sessionRepository
        .findByParticipantAccountIdsContainingOrderByCreatedOnDesc(self.getId(), PageRequest.of(0, pageSize))
        .stream()
        .map(session -> toDetail(session, self.getId()))
        .toList();
  }

  /**
   * Gets a session only when the caller is a participant.
   */
  public WhatsForLunchSessionDetail getSession(String sessionId)
      throws InvalidRequestException, ResourceNotFoundException {
    var self = getSelfAccount();
    var session = getSessionForParticipant(sessionId, self.getId());
    return toDetail(session, self.getId());
  }

  /**
   * Adds the current user to a shared-link session, then returns the session.
   */
  public WhatsForLunchSessionDetail joinSession(String sessionId)
      throws InvalidRequestException, ResourceNotFoundException {
    var self = getSelfAccount();
    var session = getSessionById(sessionId);
    var participantIds = new ArrayList<>(session.getParticipantAccountIds() == null
        ? List.of()
        : session.getParticipantAccountIds());
    var usernamesByAccountId = new LinkedHashMap<>(session.getParticipantUsernamesByAccountId() == null
        ? Map.<String, String>of()
        : session.getParticipantUsernamesByAccountId());

    if (!participantIds.contains(self.getId())) {
      participantIds.add(self.getId());
      usernamesByAccountId.put(self.getId(), self.getUsername());
      session.setParticipantAccountIds(List.copyOf(participantIds));
      session.setParticipantUsernamesByAccountId(usernamesByAccountId);
      session.setLastUpdatedOn(Instant.now());
      session = sessionRepository.save(session);
    }
    return toDetail(session, self.getId());
  }

  /**
   * Casts or updates the caller's vote for one of the session restaurants.
   */
  public WhatsForLunchSessionDetail vote(String sessionId, WhatsForLunchSessionVoteRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    if (request == null || request.restaurantId() == null || request.restaurantId().isBlank()) {
      throw new InvalidRequestException("Restaurant id cannot be null or blank.");
    }
    var self = getSelfAccount();
    var session = getSessionForParticipant(sessionId, self.getId());
    if (session.getRestaurantIds() == null || !session.getRestaurantIds().contains(request.restaurantId())) {
      throw new InvalidRequestException("Vote must be for one of this session's restaurants.");
    }

    var votes = new LinkedHashMap<>(session.getVotesByAccountId() == null
        ? Map.of()
        : session.getVotesByAccountId());
    votes.put(self.getId(), request.restaurantId());
    session.setVotesByAccountId(votes);
    session.setLastUpdatedOn(Instant.now());
    return toDetail(sessionRepository.save(session), self.getId());
  }

  /**
   * Replaces the restaurants in a participant's shared session and starts a fresh vote.
   */
  public WhatsForLunchSessionDetail updateRestaurants(
      String sessionId,
      WhatsForLunchSessionRestaurantsRequest request
  ) throws InvalidRequestException, ResourceNotFoundException {
    var self = getSelfAccount();
    var restaurantIds = normalizeRestaurantIds(request == null ? null : request.restaurantIds());
    var restaurants = getRestaurantsInRequestedOrder(restaurantIds);
    var session = getSessionForParticipant(sessionId, self.getId());
    session.setRestaurantIds(restaurantIds);
    session.setVotesByAccountId(new LinkedHashMap<>());
    session.setLastUpdatedOn(Instant.now());
    return toDetail(sessionRepository.save(session), self.getId(), restaurants);
  }

  private Account getSelfAccount() throws ResourceNotFoundException {
    var selfId = permissionService.getSelfId();
    return accountRepository.findById(selfId)
        .orElseThrow(() -> new ResourceNotFoundException("Account not found: " + selfId));
  }

  private WhatsForLunchSession getSessionForParticipant(String sessionId, String accountId)
      throws InvalidRequestException, ResourceNotFoundException {
    var session = getSessionById(sessionId);
    if (session.getParticipantAccountIds() == null || !session.getParticipantAccountIds().contains(accountId)) {
      throw new ResourceNotFoundException("WFL session not found: " + sessionId);
    }
    return session;
  }

  private List<String> normalizeRestaurantIds(List<String> restaurantIds)
      throws InvalidRequestException {
    if (restaurantIds == null) {
      throw new InvalidRequestException("A WFL session requires exactly three restaurants.");
    }
    var normalized = restaurantIds.stream()
        .filter(id -> id != null && !id.isBlank())
        .map(String::strip)
        .distinct()
        .toList();
    if (normalized.size() != SESSION_PICK_COUNT) {
      throw new InvalidRequestException("A WFL session requires exactly three restaurants.");
    }
    return normalized;
  }

  private List<Restaurant> getRestaurantsInRequestedOrder(List<String> restaurantIds)
      throws ResourceNotFoundException {
    var restaurantsById = new LinkedHashMap<String, Restaurant>();
    restaurantRepository.findAllById(restaurantIds)
        .forEach(restaurant -> restaurantsById.put(restaurant.getId(), restaurant));
    if (restaurantsById.size() != restaurantIds.size()) {
      throw new ResourceNotFoundException("One or more session restaurants were not found.");
    }
    return restaurantIds.stream().map(restaurantsById::get).toList();
  }

  private List<Account> resolveParticipants(Account creator, List<String> invitedUsernames)
      throws InvalidRequestException, ResourceNotFoundException {
    var requested = normalizeInvitees(invitedUsernames);
    var participantsById = new LinkedHashMap<String, Account>();
    participantsById.put(creator.getId(), creator);
    for (var username : requested) {
      var account = accountRepository.findByUsernameIgnoreCase(username)
          .orElseThrow(() -> new ResourceNotFoundException("Account with username " + username + " not found."));
      if (!account.getId().equals(creator.getId())) {
        participantsById.put(account.getId(), account);
      }
    }
    return new ArrayList<>(participantsById.values());
  }

  private WhatsForLunchSession getSessionById(String sessionId)
      throws InvalidRequestException, ResourceNotFoundException {
    if (sessionId == null || sessionId.isBlank()) {
      throw new InvalidRequestException("Session id cannot be null or blank.");
    }
    return sessionRepository.findById(sessionId)
        .orElseThrow(() -> new ResourceNotFoundException("WFL session not found: " + sessionId));
  }

  private List<String> normalizeInvitees(List<String> invitedUsernames)
      throws InvalidRequestException {
    if (invitedUsernames == null) {
      return List.of();
    }
    var usernames = new LinkedHashSet<String>();
    for (var username : invitedUsernames) {
      if (username == null || username.isBlank()) {
        continue;
      }
      try {
        usernames.add(UsernameSanitizer.sanitize(username.strip()));
      } catch (IllegalArgumentException e) {
        throw new InvalidRequestException("Invited usernames must be valid usernames.");
      }
    }
    if (usernames.size() > MAX_INVITEES) {
      throw new InvalidRequestException("No more than 20 members can be invited at once.");
    }
    return List.copyOf(usernames);
  }

  private Map<String, String> usernamesByAccountId(List<Account> participants) {
    var usernames = new LinkedHashMap<String, String>();
    participants.forEach(participant -> usernames.put(participant.getId(), participant.getUsername()));
    return usernames;
  }

  private WhatsForLunchSessionDetail toDetail(WhatsForLunchSession session, String selfId) {
    return toDetail(session, selfId, getRestaurantsInRequestedOrderUnchecked(session.getRestaurantIds()));
  }

  private WhatsForLunchSessionDetail toDetail(
      WhatsForLunchSession session,
      String selfId,
      List<Restaurant> restaurants
  ) {
    var votes = session.getVotesByAccountId() == null ? Map.<String, String>of() : session.getVotesByAccountId();
    var usernames = session.getParticipantUsernamesByAccountId() == null
        ? Map.<String, String>of()
        : session.getParticipantUsernamesByAccountId();
    var votesByRestaurant = new LinkedHashMap<String, List<String>>();
    restaurants.forEach(restaurant -> votesByRestaurant.put(restaurant.getId(), new ArrayList<>()));
    votes.forEach((accountId, restaurantId) -> {
      var username = usernames.get(accountId);
      if (username != null && votesByRestaurant.containsKey(restaurantId)) {
        votesByRestaurant.get(restaurantId).add(username);
      }
    });

    return WhatsForLunchSessionDetail.builder()
        .id(session.getId())
        .createdByUsername(session.getCreatedByUsername())
        .participantUsernames(new ArrayList<>(usernames.values()))
        .restaurants(toRatedDetails(restaurants, selfId))
        .votesByRestaurant(votesByRestaurant)
        .myVoteRestaurantId(votes.get(selfId))
        .createdOn(session.getCreatedOn())
        .lastUpdatedOn(session.getLastUpdatedOn())
        .build();
  }

  private List<RestaurantDetail> toRatedDetails(List<Restaurant> restaurants, String selfId) {
    var details = restaurants.stream()
        .map(restaurantMapper::toRestaurantDetail)
        .toList();
    var restaurantIds = details.stream()
        .map(RestaurantDetail::getId)
        .filter(id -> id != null && !id.isBlank())
        .toList();
    if (restaurantIds.isEmpty() || restaurantRatingRepository == null) {
      return details;
    }
    details.forEach(detail -> {
      detail.setRatingCount(0);
      detail.setRatingSum(0);
      detail.setMyFavorite(false);
    });
    var ratingsByRestaurantId = Optional.ofNullable(restaurantRatingRepository.findByRestaurantIdIn(restaurantIds))
        .orElseGet(List::of)
        .stream()
        .collect(Collectors.groupingBy(RestaurantRating::getRestaurantId));
    var favoriteIds = restaurantFavoriteRepository == null
        ? java.util.Set.<String>of()
        : Optional.ofNullable(restaurantFavoriteRepository.findByRestaurantIdInAndAccountId(restaurantIds, selfId))
            .orElseGet(List::of)
            .stream()
            .map(RestaurantFavorite::getRestaurantId)
            .collect(Collectors.toSet());
    details.forEach(detail -> {
      var ratings = ratingsByRestaurantId.getOrDefault(detail.getId(), List.of());
      detail.setRatingCount(ratings.size());
      detail.setRatingSum(ratings.stream()
          .map(RestaurantRating::getRating)
          .filter(rating -> rating != null)
          .mapToInt(Integer::intValue)
          .sum());
      ratings.stream()
          .filter(rating -> selfId.equals(rating.getAccountId()))
          .findFirst()
          .map(RestaurantRating::getRating)
          .ifPresent(detail::setMyRating);
      detail.setMyFavorite(favoriteIds.contains(detail.getId()));
    });
    return details;
  }

  private List<Restaurant> getRestaurantsInRequestedOrderUnchecked(List<String> restaurantIds) {
    if (restaurantIds == null || restaurantIds.isEmpty()) {
      return List.of();
    }
    var restaurantsById = new LinkedHashMap<String, Restaurant>();
    restaurantRepository.findAllById(restaurantIds)
        .forEach(restaurant -> restaurantsById.put(restaurant.getId(), restaurant));
    return restaurantIds.stream()
        .map(restaurantsById::get)
        .filter(restaurant -> restaurant != null)
        .toList();
  }
}
