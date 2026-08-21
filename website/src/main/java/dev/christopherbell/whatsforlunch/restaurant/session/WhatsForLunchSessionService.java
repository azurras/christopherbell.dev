package dev.christopherbell.whatsforlunch.restaurant.session;

import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.security.UsernameSanitizer;
import dev.christopherbell.notification.delivery.NotificationDeliveryService;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantMapper;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantRepository;
import dev.christopherbell.whatsforlunch.restaurant.RestaurantWebsiteUrlPolicy;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVote;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSession;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionVoteRequest;
import dev.christopherbell.whatsforlunch.restaurant.vote.RestaurantVoteRepository;
import java.time.Clock;
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
import org.springframework.security.access.AccessDeniedException;

/** Coordinates shared WFL sessions, invitations, and votes. */
@RequiredArgsConstructor
@Service
public class WhatsForLunchSessionService {
  private static final int SESSION_PICK_COUNT = 3;
  private static final String SESSION_CHANGED = "WFL_SESSION_CHANGED";
  private static final String SESSION_EXPIRED = "WFL_SESSION_EXPIRED";
  private static final String SESSION_FULL = "WFL_SESSION_FULL";

  private final AccountRepository accountRepository;
  private final Clock clock;
  private final NotificationDeliveryService notificationDeliveryService;
  private final PermissionService permissionService;
  private final RestaurantMapper restaurantMapper;
  private final RestaurantFavoriteRepository restaurantFavoriteRepository;
  private final RestaurantVoteRepository restaurantVoteRepository;
  private final RestaurantRepository restaurantRepository;
  private final WhatsForLunchSessionMutationPort mutations;
  private final WhatsForLunchSessionRepository sessionRepository;
  private final WflProperties wflProperties;

  /**
   * Creates a session from exactly three current WFL picks and invites existing users.
   */
  public WhatsForLunchSessionDetail createSession(WhatsForLunchSessionCreateRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    var creator = getSelfAccount();
    var restaurantIds = normalizeRestaurantIds(request == null ? null : request.restaurantIds());
    var restaurants = getRestaurantsInRequestedOrder(restaurantIds);
    var participants = resolveParticipants(creator, request == null ? null : request.invitedUsernames());
    var now = clock.instant();
    var activeUntil = now.plus(wflProperties.getSessions().getActiveLifetime());
    var session = WhatsForLunchSession.builder()
        .id(UUID.randomUUID().toString())
        .createdByAccountId(creator.getId())
        .createdByUsername(creator.getUsername())
        .participantAccountIds(participants.stream().map(Account::getId).toList())
        .participantUsernamesByAccountId(usernamesByAccountId(participants))
        .restaurantIds(restaurantIds)
        .votesByAccountId(new LinkedHashMap<>())
        .revision(0)
        .activeUntil(activeUntil)
        .deleteOn(activeUntil.plus(wflProperties.getSessions().getArchiveLifetime()))
        .restaurantResetCount(0)
        .restaurantResetAudit(List.of())
        .createdOn(now)
        .lastUpdatedOn(now)
        .build();

    var saved = sessionRepository.save(session);
    participants.stream()
        .filter(participant -> !participant.getId().equals(creator.getId()))
        .forEach(participant -> notificationDeliveryService.createWhatsForLunchSessionInvite(saved, creator, participant));
    return toDetail(saved, creator.getId(), restaurants);
  }

  /**
   * Lists recent sessions that include the caller.
   */
  public List<WhatsForLunchSessionDetail> getMySessions(int limit)
      throws ResourceNotFoundException {
    var self = getSelfAccount();
    var pageSize = Math.max(1, Math.min(limit, 25));
    var sessions = sessionRepository
        .findByParticipantAccountIdsContainingAndDeleteOnAfterOrderByCreatedOnDesc(
            self.getId(), clock.instant(), PageRequest.of(0, pageSize));
    return toDetails(sessions, self.getId());
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
    var result = mutations.join(
        sessionId,
        self.getId(),
        self.getUsername(),
        clock.instant(),
        wflProperties.getSessions().getMaxMembers());
    return toDetail(requireJoin(result, sessionId), self.getId());
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
    var result = mutations.vote(
        sessionId, self.getId(), request.restaurantId().strip(), clock.instant());
    return toDetail(requireVote(result, sessionId), self.getId());
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
    var normalizedRequest = new WhatsForLunchSessionRestaurantsRequest(
        restaurantIds, request.expectedRevision());
    var result = mutations.resetRestaurants(
        sessionId, self.getId(), self.getUsername(), normalizedRequest, clock.instant());
    return toDetail(requireReset(result, sessionId), self.getId(), restaurants);
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
    var session = sessionRepository.findById(sessionId)
        .orElseThrow(() -> new ResourceNotFoundException("WFL session not found: " + sessionId));
    if (session.getDeleteOn() != null && !session.getDeleteOn().isAfter(clock.instant())) {
      throw new ResourceNotFoundException("WFL session not found: " + sessionId);
    }
    return session;
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
    var maxInvitees = Math.max(0, wflProperties.getSessions().getMaxMembers() - 1);
    if (usernames.size() > maxInvitees) {
      throw new InvalidRequestException("A WFL session cannot have more than 20 total members.");
    }
    return List.copyOf(usernames);
  }

  private Map<String, String> usernamesByAccountId(List<Account> participants) {
    var usernames = new LinkedHashMap<String, String>();
    participants.forEach(participant -> usernames.put(participant.getId(), participant.getUsername()));
    return usernames;
  }

  private WhatsForLunchSessionDetail toDetail(WhatsForLunchSession session, String selfId) {
    return toDetails(List.of(session), selfId).getFirst();
  }

  private WhatsForLunchSessionDetail toDetail(
      WhatsForLunchSession session,
      String selfId,
      List<Restaurant> restaurants
  ) {
    return toDetailFromHydratedRestaurants(session, selfId, toVoteDetails(restaurants, selfId));
  }

  private List<WhatsForLunchSessionDetail> toDetails(
      List<WhatsForLunchSession> sessions,
      String selfId
  ) {
    if (sessions == null || sessions.isEmpty()) {
      return List.of();
    }
    var restaurantIds = sessions.stream()
        .flatMap(session -> Optional.ofNullable(session.getRestaurantIds())
            .orElseGet(List::of).stream())
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .toList();
    var restaurantsById = new LinkedHashMap<String, Restaurant>();
    restaurantRepository.findAllById(restaurantIds)
        .forEach(restaurant -> restaurantsById.put(restaurant.getId(), restaurant));
    var detailsById = toVoteDetails(restaurantIds.stream()
        .map(restaurantsById::get)
        .filter(java.util.Objects::nonNull)
        .toList(), selfId).stream()
        .collect(Collectors.toMap(
            RestaurantDetail::getId,
            java.util.function.Function.identity(),
            (left, right) -> left,
            LinkedHashMap::new));
    return sessions.stream()
        .map(session -> toDetailFromHydratedRestaurants(
            session,
            selfId,
            Optional.ofNullable(session.getRestaurantIds()).orElseGet(List::of).stream()
                .map(detailsById::get)
                .filter(java.util.Objects::nonNull)
                .toList()))
        .toList();
  }

  private WhatsForLunchSessionDetail toDetailFromHydratedRestaurants(
      WhatsForLunchSession session,
      String selfId,
      List<RestaurantDetail> restaurants
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
        .canManage(isActive(session, clock.instant())
            && selfId.equals(session.getCreatedByAccountId()))
        .participantUsernames(Optional.ofNullable(session.getParticipantAccountIds())
            .orElseGet(List::of)
            .stream()
            .map(usernames::get)
            .filter(java.util.Objects::nonNull)
            .toList())
        .restaurants(restaurants)
        .votesByRestaurant(votesByRestaurant)
        .myVoteRestaurantId(votes.get(selfId))
        .revision(session.getRevision())
        .active(isActive(session, clock.instant()))
        .canChangeRestaurants(isActive(session, clock.instant())
            && selfId.equals(session.getCreatedByAccountId()))
        .activeUntil(session.getActiveUntil())
        .createdOn(session.getCreatedOn())
        .lastUpdatedOn(session.getLastUpdatedOn())
        .build();
  }

  private List<RestaurantDetail> toVoteDetails(List<Restaurant> restaurants, String selfId) {
    var details = restaurants.stream()
        .map(restaurantMapper::toRestaurantDetail)
        .toList();
    details.forEach(detail -> detail.setWebsite(
        RestaurantWebsiteUrlPolicy.safeOrNull(detail.getWebsite())));
    var restaurantIds = details.stream()
        .map(RestaurantDetail::getId)
        .filter(id -> id != null && !id.isBlank())
        .toList();
    if (restaurantIds.isEmpty() || restaurantVoteRepository == null) {
      return details;
    }
    details.forEach(detail -> {
      detail.setUpVotes(0);
      detail.setDownVotes(0);
      detail.setVoteCount(0);
      detail.setMyVote(null);
      detail.setMyFavorite(false);
    });
    var votesByRestaurantId = Optional.ofNullable(restaurantVoteRepository.findByRestaurantIdIn(restaurantIds))
        .orElseGet(List::of)
        .stream()
        .collect(Collectors.groupingBy(RestaurantVote::getRestaurantId));
    var favoriteIds = restaurantFavoriteRepository == null
        ? java.util.Set.<String>of()
        : Optional.ofNullable(restaurantFavoriteRepository.findByRestaurantIdInAndAccountId(restaurantIds, selfId))
            .orElseGet(List::of)
            .stream()
            .map(RestaurantFavorite::getRestaurantId)
            .collect(Collectors.toSet());
    details.forEach(detail -> {
      var votes = votesByRestaurantId.getOrDefault(detail.getId(), List.of());
      int upVotes = (int) votes.stream().filter(vote -> vote.getVote() == RestaurantVoteValue.UP).count();
      int downVotes = (int) votes.stream().filter(vote -> vote.getVote() == RestaurantVoteValue.DOWN).count();
      detail.setUpVotes(upVotes);
      detail.setDownVotes(downVotes);
      detail.setVoteCount(upVotes + downVotes);
      votes.stream()
          .filter(vote -> selfId.equals(vote.getAccountId()))
          .findFirst()
          .map(RestaurantVote::getVote)
          .ifPresent(detail::setMyVote);
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

  private WhatsForLunchSession requireJoin(
      WhatsForLunchSessionMutationStore.Result result,
      String sessionId
  ) throws ResourceNotFoundException {
    return switch (result.status()) {
      case UPDATED, UNCHANGED -> result.session();
      case FULL -> throw new WflSessionConflictException(SESSION_FULL);
      case EXPIRED -> throw new WflSessionConflictException(SESSION_EXPIRED);
      case MISSING -> throw new ResourceNotFoundException("WFL session not found: " + sessionId);
      default -> throw new WflSessionConflictException(SESSION_CHANGED);
    };
  }

  private WhatsForLunchSession requireVote(
      WhatsForLunchSessionMutationStore.Result result,
      String sessionId
  ) throws InvalidRequestException, ResourceNotFoundException {
    return switch (result.status()) {
      case UPDATED, UNCHANGED -> result.session();
      case EXPIRED -> throw new WflSessionConflictException(SESSION_EXPIRED);
      case MISSING, NOT_PARTICIPANT ->
          throw new ResourceNotFoundException("WFL session not found: " + sessionId);
      case INVALID_RESTAURANT ->
          throw new InvalidRequestException("Vote must be for one of this session's restaurants.");
      default -> throw new WflSessionConflictException(SESSION_CHANGED);
    };
  }

  private WhatsForLunchSession requireReset(
      WhatsForLunchSessionMutationStore.Result result,
      String sessionId
  ) throws ResourceNotFoundException {
    return switch (result.status()) {
      case UPDATED -> result.session();
      case EXPIRED -> throw new WflSessionConflictException(SESSION_EXPIRED);
      case MISSING -> throw new ResourceNotFoundException("WFL session not found: " + sessionId);
      case NOT_HOST -> throw new AccessDeniedException("Only the WFL session creator can change restaurants.");
      default -> throw new WflSessionConflictException(SESSION_CHANGED);
    };
  }

  private boolean isActive(WhatsForLunchSession session, Instant now) {
    return session.getActiveUntil() == null || now.isBefore(session.getActiveUntil());
  }
}
