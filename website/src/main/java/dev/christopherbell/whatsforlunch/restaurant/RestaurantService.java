package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import dev.christopherbell.libs.mongo.lease.CollectorLeaseGuard;
import dev.christopherbell.libs.mongo.lease.ScheduledCollectorCoordinator;
import dev.christopherbell.location.zip.ZipCoordinateService;
import dev.christopherbell.location.model.ZipCoordinateDetail;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.config.WflProperties;
import dev.christopherbell.whatsforlunch.restaurant.favorite.RestaurantFavoriteRepository;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewCounts;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportLeaseGuard;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportSnapshot;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeApplyRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeCandidate;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeGroupPreview;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupePreview;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantInventoryPage;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavoriteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRating;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRatingRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantUpdateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceRequest;
import dev.christopherbell.whatsforlunch.restaurant.preference.WhatsForLunchPreferenceRepository;
import dev.christopherbell.whatsforlunch.restaurant.rating.RestaurantRatingRepository;
import dev.christopherbell.whatsforlunch.restaurant.rating.RestaurantRatingQueryRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Service
@Slf4j
public class RestaurantService {
  private static final double EARTH_RADIUS_MILES = 3958.7613;
  private static final int NEARBY_LUNCH_PICK_COUNT = 3;
  private static final int DEFAULT_NEARBY_LUNCH_RADIUS_MILES = 15;
  private static final List<Integer> ALLOWED_NEARBY_LUNCH_RADII_MILES = List.of(1, 5, 10, 15, 20);
  private static final int MAX_CUISINE_FILTERS = 20;
  private static final Duration DAILY_PICK_LEASE_DURATION = Duration.ofMinutes(10);

  private final Clock clock;
  private final DailyLunchPicksRepository dailyLunchPicksRepository;
  private final OpenStreetMapRestaurantClient openStreetMapRestaurantClient;
  private final PermissionService permissionService;
  private final RestaurantMapper restaurantMapper;
  private final RestaurantFavoriteRepository restaurantFavoriteRepository;
  private final RestaurantDuplicateQueryRepository restaurantDuplicateQueries;
  private final RestaurantInventoryQueryRepository restaurantInventoryQueries;
  private final RestaurantRatingRepository restaurantRatingRepository;
  private final RestaurantRatingQueryRepository restaurantRatingQueryRepository;
  private final RestaurantRepository restaurantRepository;
  private final ScheduledCollectorCoordinator scheduledCollectors;
  private final WhatsForLunchPreferenceRepository whatsForLunchPreferenceRepository;
  private final ZipCoordinateService zipCoordinateService;
  private final WflProperties wflProperties;

  /**
   * Creates a new restaurant based on the provided request.
   *
   * @param request containing the details of the restaurant to be created.
   * @return a WhatsForLunchResponse containing the created restaurant details.
   * @throws Exception if there is an error during the creation process.
   */
  public RestaurantDetail createRestaurant(RestaurantCreateRequest request) throws Exception {
    var restaurant = restaurantMapper.toRestaurant(request);
    restaurant.setWebsite(RestaurantWebsiteUrlPolicy.requireSafe(restaurant.getWebsite()));
    applyNormalizedName(restaurant);
    ensureRestaurantNameUnique(restaurant.getNormalizedName(), null);

    try {
      var savedRestaurant = restaurantRepository.save(restaurant);
      return toRatedDetail(savedRestaurant);
    } catch (DuplicateKeyException e) {
      throw new ResourceExistsException("Restaurant already exists", e);
    } catch (DataAccessException e) {
      throw new ServiceUnavailableException("Failed to save restaurant", e);
    }
  }

  /**
   * Deletes a restaurant by the provided id.
   *
   * @param id the restaurant id to delete; must not be null or blank
   * @return the details of the deleted restaurant (snapshot of its state before deletion)
   * @throws InvalidRequestException if the id is null or blank
   * @throws ResourceNotFoundException if no restaurant with the provided id exists
   * @throws ServiceUnavailableException if deletion fails due to persistence errors
   */
  public RestaurantDetail deleteRestaurantById(
      String id
  ) throws InvalidRequestException, ResourceNotFoundException {
    if (id == null || id.isBlank()) {
      throw new InvalidRequestException("Restaurant id cannot be null or blank.");
    }

    var restaurant = restaurantRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));

    try {
      restaurantRepository.delete(restaurant);
    } catch (DataAccessException e) {
      throw new ServiceUnavailableException("Failed to delete restaurant with id: " + id, e);
    }
    return toRatedDetail(restaurant);
  }

  /**
   * Gets all existing restaurants.
   *
   * @return a WhatsForLunchResponse containing a list of all existing restaurants
   */
  public List<RestaurantDetail> getRestaurants() {
    var restaurants = restaurantRepository.findAll(PageRequest.of(
        0,
        100,
        Sort.by(Sort.Order.asc("normalizedName"), Sort.Order.asc("_id")))).getContent();
    return toRatedDetails(restaurants);
  }

  /** Returns one stable searchable admin inventory page. */
  public RestaurantInventoryPage getRestaurantInventory(
      String name,
      String city,
      String state,
      String cursor,
      int size
  ) {
    var page = restaurantInventoryQueries.find(name, city, state, cursor, size);
    return new RestaurantInventoryPage(
        toRatedDetails(page.items()), page.nextCursor(), page.total());
  }

  /**
   * Gets today's lunch picks, generating them on demand if midnight scheduling has not run yet.
   *
   * @return up to the configured number of supported metro restaurant picks
   */
  public List<RestaurantDetail> getTodaysLunchPicks() {
    var today = LocalDate.now(getRestaurantOfTheDayZone());
    var existing = dailyLunchPicksRepository.findById(today.toString())
        .orElseGet(() -> refreshDailyLunchPicks(today));
    var picks = getRestaurantsForPick(existing);
    if (picks.size() < Math.min(dailyPickCount(), getSupportedMetroRestaurants().size())) {
      picks = getRestaurantsForPick(refreshDailyLunchPicks(today));
    }
    return toRatedDetails(picks);
  }

  /**
   * Gets three fresh lunch picks within fifteen miles of the provided browser location.
   *
   * @param latitude user latitude from the browser geolocation API
   * @param longitude user longitude from the browser geolocation API
   * @return up to three nearby restaurant picks, reshuffled on each request
   */
  public List<RestaurantDetail> getNearbyLunchPicks(
      double latitude,
      double longitude
  ) throws InvalidRequestException {
    return getNearbyLunchPicks(latitude, longitude, List.of());
  }

  /**
   * Gets three fresh lunch picks within fifteen miles, filtered by selected cuisine tags when present.
   *
   * <p>Anonymous callers can provide filters directly. Authenticated callers use their saved filters
   * when this request does not include explicit cuisines.</p>
   *
   * @param latitude user latitude from the browser geolocation API
   * @param longitude user longitude from the browser geolocation API
   * @param requestedCuisines cuisine filters supplied for this request
   * @return up to three nearby restaurant picks, reshuffled on each request
   */
  public List<RestaurantDetail> getNearbyLunchPicks(
      double latitude,
      double longitude,
      List<String> requestedCuisines
  ) throws InvalidRequestException {
    return getNearbyLunchPicks(latitude, longitude, null, requestedCuisines, true);
  }

  /**
   * Gets three fresh lunch picks within fifteen miles, optionally falling back to saved filters.
   *
   * @param latitude user latitude from the browser geolocation API
   * @param longitude user longitude from the browser geolocation API
   * @param requestedRadiusMiles optional nearby search radius
   * @param requestedCuisines cuisine filters supplied for this request
   * @param useSavedPreferences whether saved filters should apply when no request filters are present
   * @return up to three nearby restaurant picks, reshuffled on each request
   */
  public List<RestaurantDetail> getNearbyLunchPicks(
      double latitude,
      double longitude,
      Integer requestedRadiusMiles,
      List<String> requestedCuisines,
      boolean useSavedPreferences
  ) throws InvalidRequestException {
    validateCoordinates(latitude, longitude);
    var preference = resolvePreference(useSavedPreferences);
    var cuisineFilters = resolveCuisineFilters(requestedCuisines, preference, useSavedPreferences);
    var radiusMiles = resolveRadiusMiles(requestedRadiusMiles, preference, useSavedPreferences);
    var restaurants = getNearbyCandidateRestaurants(latitude, longitude, radiusMiles);

    return getNearbyLunchPicksFromRestaurants(latitude, longitude, cuisineFilters, radiusMiles, restaurants);
  }

  /**
   * Gets three fresh lunch picks near a user-entered ZIP code.
   *
   * <p>The ZIP origin comes from persisted Location Census ZIP coordinates before restaurant
   * candidates are loaded for the selected radius.</p>
   *
   * @param zipCode user ZIP code
   * @param requestedRadiusMiles optional nearby search radius
   * @param requestedCuisines cuisine filters supplied for this request
   * @param useSavedPreferences whether saved filters should apply when no request filters are present
   * @return up to three nearby restaurant picks, reshuffled on each request
   */
  public List<RestaurantDetail> getNearbyLunchPicksByZipCode(
      String zipCode,
      Integer requestedRadiusMiles,
      List<String> requestedCuisines,
      boolean useSavedPreferences
  ) throws InvalidRequestException {
    var normalizedZipCode = normalizeZipCode(zipCode);
    var preference = resolvePreference(useSavedPreferences);
    var cuisineFilters = resolveCuisineFilters(requestedCuisines, preference, useSavedPreferences);
    var radiusMiles = resolveRadiusMiles(requestedRadiusMiles, preference, useSavedPreferences);
    var origin = getZipCoordinateOrigin(normalizedZipCode);
    var restaurants = getNearbyCandidateRestaurants(origin.latitude(), origin.longitude(), radiusMiles);
    return getNearbyLunchPicksFromRestaurants(origin.latitude(), origin.longitude(), cuisineFilters, radiusMiles, restaurants);
  }

  private ZipCoordinateDetail getZipCoordinateOrigin(String zipCode)
      throws InvalidRequestException {
    try {
      return zipCoordinateService.getZipCoordinate(zipCode);
    } catch (ResourceNotFoundException e) {
      throw new InvalidRequestException("ZIP code must match an imported US ZIP coordinate.", e);
    }
  }

  private List<RestaurantDetail> getNearbyLunchPicksFromRestaurants(
      double latitude,
      double longitude,
      List<String> cuisineFilters,
      int radiusMiles,
      List<Restaurant> restaurants
  ) {
    var candidates = Optional.ofNullable(restaurants).orElseGet(List::of).stream()
        .filter(restaurant -> restaurant.getId() != null && !restaurant.getId().isBlank())
        .filter(this::hasCoordinates)
        .filter(restaurant -> matchesCuisineFilters(restaurant, cuisineFilters))
        .filter(restaurant -> distanceMiles(
            latitude,
            longitude,
            restaurant.getAddress().getLatitude(),
            restaurant.getAddress().getLongitude()) <= radiusMiles)
        .toList();

    return toRatedDetails(orderLunchCandidates(candidates).stream()
        .limit(NEARBY_LUNCH_PICK_COUNT)
        .toList());
  }

  /**
   * Gets the current user's saved WFL cuisine filters.
   *
   * @return saved cuisine filters, or an empty preference object when no preferences exist
   */
  public WhatsForLunchPreferenceDetail getMyPreferences() {
    var accountId = permissionService.getSelfId();
    return whatsForLunchPreferenceRepository.findById(accountId)
        .map(preference -> WhatsForLunchPreferenceDetail.builder()
            .cuisines(List.copyOf(Optional.ofNullable(preference.getCuisines()).orElseGet(List::of)))
            .radiusMiles(resolveSavedRadiusMiles(preference.getRadiusMiles()))
            .build())
        .orElseGet(this::defaultPreferences);
  }

  /**
   * Gets WFL filters for the current viewer without requiring authentication.
   *
   * @return saved preferences for authenticated users, or defaults for anonymous visitors
   */
  public WhatsForLunchPreferenceDetail getPreferencesForCurrentViewer() {
    var accountId = getSelfIdOrNull();
    if (accountId == null) {
      return defaultPreferences();
    }
    return whatsForLunchPreferenceRepository.findById(accountId)
        .map(preference -> WhatsForLunchPreferenceDetail.builder()
            .cuisines(List.copyOf(Optional.ofNullable(preference.getCuisines()).orElseGet(List::of)))
            .radiusMiles(resolveSavedRadiusMiles(preference.getRadiusMiles()))
            .build())
        .orElseGet(this::defaultPreferences);
  }

  /**
   * Saves the current user's WFL cuisine filters.
   *
   * @param request preference payload from the browser
   * @return saved preference detail
   * @throws InvalidRequestException when the request contains too many or invalid filters
   */
  public WhatsForLunchPreferenceDetail updateMyPreferences(
      WhatsForLunchPreferenceRequest request
  ) throws InvalidRequestException {
    var accountId = permissionService.getSelfId();
    var cuisines = normalizeCuisineFilters(request == null ? null : request.cuisines());
    var radiusMiles = request == null || request.radiusMiles() == null
        ? DEFAULT_NEARBY_LUNCH_RADIUS_MILES
        : validateRadiusMiles(request.radiusMiles());
    var saved = whatsForLunchPreferenceRepository.save(WhatsForLunchPreference.builder()
        .accountId(accountId)
        .cuisines(cuisines)
        .radiusMiles(radiusMiles)
        .build());
    return WhatsForLunchPreferenceDetail.builder()
        .cuisines(List.copyOf(Optional.ofNullable(saved.getCuisines()).orElseGet(List::of)))
        .radiusMiles(resolveSavedRadiusMiles(saved.getRadiusMiles()))
        .build();
  }

  /**
   * Sets the current user's whole-number rating for a restaurant.
   *
   * @param restaurantId restaurant to rate
   * @param request whole-number rating from 1 through 5
   * @return restaurant detail with updated public rating totals
   * @throws InvalidRequestException when the id or rating is invalid
   * @throws ResourceNotFoundException when the restaurant does not exist
   */
  public RestaurantDetail rateRestaurant(
      String restaurantId,
      RestaurantRatingRequest request
  ) throws InvalidRequestException, ResourceNotFoundException {
    var normalizedRestaurantId = validateRestaurantId(restaurantId);
    var rating = validateRating(request == null ? null : request.rating());
    var restaurant = restaurantRepository.findById(normalizedRestaurantId)
        .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + normalizedRestaurantId));
    var accountId = permissionService.getSelfId();
    var now = Instant.now(clock);
    var existing = restaurantRatingRepository.findByRestaurantIdAndAccountId(normalizedRestaurantId, accountId)
        .orElseGet(() -> RestaurantRating.builder()
            .id(UUID.randomUUID().toString())
            .restaurantId(normalizedRestaurantId)
            .accountId(accountId)
            .createdOn(now)
            .build());
    existing.setRating(rating);
    existing.setLastUpdatedOn(now);
    restaurantRatingRepository.save(existing);
    return toRatedDetail(restaurant);
  }

  /**
   * Favorites a restaurant for the current user.
   *
   * @param request favorite request containing the restaurant id
   * @return restaurant detail with {@code myFavorite=true}
   * @throws InvalidRequestException when the restaurant id is blank
   * @throws ResourceNotFoundException when the restaurant does not exist
   */
  public RestaurantDetail favoriteRestaurant(RestaurantFavoriteRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    var restaurantId = validateRestaurantId(request == null ? null : request.restaurantId());
    var restaurant = restaurantRepository.findById(restaurantId)
        .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
    var accountId = permissionService.getSelfId();
    var existing = restaurantFavoriteRepository.findByRestaurantIdAndAccountId(restaurantId, accountId);
    if (existing.isEmpty()) {
      restaurantFavoriteRepository.save(RestaurantFavorite.builder()
          .id(UUID.randomUUID().toString())
          .restaurantId(restaurantId)
          .accountId(accountId)
          .createdOn(Instant.now(clock))
          .build());
    }
    return toRatedDetail(restaurant);
  }

  /**
   * Removes a restaurant favorite for the current user.
   *
   * @param request favorite request containing the restaurant id
   * @return restaurant detail with {@code myFavorite=false}
   * @throws InvalidRequestException when the restaurant id is blank
   * @throws ResourceNotFoundException when the restaurant does not exist
   */
  public RestaurantDetail unfavoriteRestaurant(RestaurantFavoriteRequest request)
      throws InvalidRequestException, ResourceNotFoundException {
    var restaurantId = validateRestaurantId(request == null ? null : request.restaurantId());
    var restaurant = restaurantRepository.findById(restaurantId)
        .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + restaurantId));
    var accountId = permissionService.getSelfId();
    restaurantFavoriteRepository.deleteByRestaurantIdAndAccountId(restaurantId, accountId);
    return toRatedDetail(restaurant);
  }

  /**
   * Lists restaurants favorited by the current user, newest favorite first.
   *
   * @return favorite restaurant details
   */
  public List<RestaurantDetail> getMyFavoriteRestaurants() {
    var accountId = permissionService.getSelfId();
    var favorites = Optional.ofNullable(restaurantFavoriteRepository.findByAccountIdOrderByCreatedOnDesc(accountId))
        .orElseGet(List::of);
    var restaurantIds = favorites.stream()
        .map(RestaurantFavorite::getRestaurantId)
        .filter(id -> id != null && !id.isBlank())
        .distinct()
        .toList();
    var restaurantsById = new java.util.LinkedHashMap<String, Restaurant>();
    restaurantRepository.findAllById(restaurantIds)
        .forEach(restaurant -> restaurantsById.put(restaurant.getId(), restaurant));
    return toRatedDetails(restaurantIds.stream()
        .map(restaurantsById::get)
        .filter(restaurant -> restaurant != null)
        .toList());
  }

  /**
   * Lists the highest rated restaurants, excluding restaurants without ratings.
   *
   * @param limit maximum number of restaurants to return
   * @return top rated restaurant details sorted by average rating and rating count
   */
  public List<RestaurantDetail> getTopRatedRestaurants(int limit) {
    var pageSize = Math.max(1, Math.min(limit, 50));
    var summaries = Optional.ofNullable(restaurantRatingQueryRepository.topRated(pageSize))
        .orElseGet(List::of);
    var restaurantIds = summaries.stream().map(summary -> summary.restaurantId()).toList();
    var restaurantsById = new java.util.LinkedHashMap<String, Restaurant>();
    restaurantRepository.findAllById(restaurantIds)
        .forEach(restaurant -> restaurantsById.put(restaurant.getId(), restaurant));
    return toRatedDetails(restaurantIds.stream()
        .map(restaurantsById::get)
        .filter(restaurant -> restaurant != null)
        .toList());
  }

  /**
   * Deletes a restaurant and rewrites today's pick list with a replacement when possible.
   *
   * @param id the restaurant id to delete
   * @return today's updated lunch picks
   */
  public List<RestaurantDetail> deleteRestaurantFromTodaysLunchPicks(
      String id
  ) throws InvalidRequestException, ResourceNotFoundException {
    if (id == null || id.isBlank()) {
      throw new InvalidRequestException("Restaurant id cannot be null or blank.");
    }

    var restaurant = restaurantRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));

    try {
      restaurantRepository.delete(restaurant);
    } catch (DataAccessException e) {
      throw new ServiceUnavailableException("Failed to delete restaurant with id: " + id, e);
    }

    log.info("Deleted today's lunch restaurant id: {}.", id);
    var updatedPick = replaceDeletedRestaurantInTodaysPick(id);
    return toRatedDetails(getRestaurantsForPick(updatedPick));
  }

  /**
   * Removes duplicate restaurant names, keeping one stable survivor per duplicate group.
   *
   * @return cleanup summary
   */
  public RestaurantDedupeResult removeDuplicateNamedRestaurants() {
    log.info("Restaurant duplicate-name cleanup started.");
    var keptIds = new ArrayList<String>();
    var deletedIds = new ArrayList<String>();
    var updatedSurvivors = 0;
    var groupCount = 0;
    String cursor = null;
    do {
      var page = restaurantDuplicateQueries.find(cursor, 100);
      var groups = groupsFor(page.keys(), page.members());
      for (var duplicateGroup : groups) {
        var normalizedName = duplicateGroup.getKey();
        var group = duplicateGroup.getValue();
        var survivor = chooseDuplicateSurvivor(group);
        var duplicates = group.stream()
            .filter(restaurant -> !restaurant.getId().equals(survivor.getId()))
            .toList();
        restaurantRepository.deleteAll(duplicates);
        duplicates.forEach(restaurant -> deletedIds.add(restaurant.getId()));
        if (!normalizedName.equals(survivor.getNormalizedName())) {
          survivor.setNormalizedName(normalizedName);
          restaurantRepository.save(survivor);
          updatedSurvivors++;
        }
        keptIds.add(survivor.getId());
        groupCount++;
      }
      cursor = page.nextCursor();
    } while (cursor != null);

    log.info("Restaurant duplicate-name cleanup completed. Duplicate groups: {}, deleted: {}, updated survivors: {}.",
        groupCount, deletedIds.size(), updatedSurvivors);
    return RestaurantDedupeResult.builder()
        .duplicateGroups(groupCount)
        .deleted(deletedIds.size())
        .updatedSurvivors(updatedSurvivors)
        .keptRestaurantIds(List.copyOf(keptIds))
        .deletedRestaurantIds(List.copyOf(deletedIds))
        .build();
  }

  /**
   * Imports configured metro lunch spots from OpenStreetMap via Overpass.
   *
   * @return import summary
   */
  RestaurantImportResult importConfiguredMetroRestaurantsFromOpenStreetMap()
      throws IOException, InterruptedException, InvalidRequestException {
    return applyPreparedImport(prepareConfiguredMetroImport(), RestaurantImportLeaseGuard.NONE);
  }

  /** Calculates duplicate groups and stable survivors without mutating data. */
  public RestaurantDedupePreview previewDuplicateNamedRestaurants() {
    return previewDuplicateNamedRestaurants(null, 25);
  }

  /** Calculates one indexed page of duplicate groups without mutating data. */
  public RestaurantDedupePreview previewDuplicateNamedRestaurants(String cursor, int size) {
    var page = restaurantDuplicateQueries.find(cursor, size);
    var groups = groupsFor(page.keys(), page.members()).stream()
        .map(entry -> toDedupeGroupPreview(entry.getKey(), entry.getValue()))
        .toList();
    return new RestaurantDedupePreview(groups, page.nextCursor());
  }

  /** Applies only exact, version-matched duplicate groups after validating every confirmation. */
  public RestaurantDedupeResult applyDuplicateNamedRestaurants(RestaurantDedupeApplyRequest request) {
    if (request == null || request.groups().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate group confirmation is required");
    }
    var confirmedKeys = request.groups().stream()
        .map(dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeConfirmation::normalizedName)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    var currentGroups = groupsFor(
        confirmedKeys,
        restaurantRepository.findByDedupeKeyIn(confirmedKeys)).stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    var confirmedNames = new java.util.HashSet<String>();
    for (var confirmation : request.groups()) {
      if (confirmation == null || confirmation.normalizedName() == null
          || !confirmedNames.add(confirmation.normalizedName())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate group confirmation is invalid");
      }
      var group = currentGroups.get(confirmation.normalizedName());
      if (group == null || !matchesConfirmation(confirmation, group)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate group changed after preview");
      }
    }

    var keptIds = new ArrayList<String>();
    var deletedIds = new ArrayList<String>();
    var updatedSurvivors = 0;
    for (var confirmation : request.groups()) {
      var group = currentGroups.get(confirmation.normalizedName());
      var survivor = chooseDuplicateSurvivor(group);
      var duplicates = group.stream()
          .filter(restaurant -> !restaurant.getId().equals(survivor.getId()))
          .toList();
      restaurantRepository.deleteAll(duplicates);
      duplicates.forEach(restaurant -> deletedIds.add(restaurant.getId()));
      if (!confirmation.normalizedName().equals(survivor.getNormalizedName())) {
        survivor.setNormalizedName(confirmation.normalizedName());
        restaurantRepository.save(survivor);
        updatedSurvivors++;
      }
      keptIds.add(survivor.getId());
    }
    return RestaurantDedupeResult.builder()
        .duplicateGroups(request.groups().size())
        .deleted(deletedIds.size())
        .updatedSurvivors(updatedSurvivors)
        .keptRestaurantIds(List.copyOf(keptIds))
        .deletedRestaurantIds(List.copyOf(deletedIds))
        .build();
  }

  private List<Map.Entry<String, List<Restaurant>>> groupsFor(
      List<String> keys,
      List<Restaurant> members
  ) {
    var byKey = Optional.ofNullable(members).orElseGet(List::of).stream()
        .filter(restaurant -> restaurant.getDedupeKey() != null)
        .collect(Collectors.groupingBy(
            Restaurant::getDedupeKey,
            LinkedHashMap::new,
            Collectors.toList()));
    return keys.stream()
        .map(key -> Map.entry(key, List.copyOf(byKey.getOrDefault(key, List.of()))))
        .filter(entry -> entry.getValue().size() > 1)
        .toList();
  }

  private RestaurantDedupeGroupPreview toDedupeGroupPreview(
      String normalizedName,
      List<Restaurant> group
  ) {
    var memberIds = group.stream().map(Restaurant::getId).sorted().toList();
    return new RestaurantDedupeGroupPreview(
        normalizedName,
        dedupeGroupVersion(group),
        chooseDuplicateSurvivor(group).getId(),
        memberIds,
        group.stream()
            .sorted(Comparator.comparing(restaurant -> nullSafe(restaurant.getId())))
            .map(restaurant -> new RestaurantDedupeCandidate(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                restaurant.getCreatedOn(),
                restaurant.getLastUpdatedOn()))
            .toList());
  }

  private boolean matchesConfirmation(
      dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeConfirmation confirmation,
      List<Restaurant> group
  ) {
    var preview = toDedupeGroupPreview(confirmation.normalizedName(), group);
    return preview.version().equals(confirmation.version())
        && preview.survivorId().equals(confirmation.survivorId())
        && preview.memberIds().equals(confirmation.memberIds());
  }

  private String dedupeGroupVersion(List<Restaurant> group) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      group.stream()
          .map(restaurant -> canonicalImportValue(restaurant) + "|" + nullSafe(
              restaurant.getLastUpdatedOn() == null ? null : restaurant.getLastUpdatedOn().toString()))
          .sorted()
          .forEach(value -> digest.update((value + "\n").getBytes(StandardCharsets.UTF_8)));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** Fetches and classifies remote candidates without mutating local restaurant data. */
  public RestaurantImportSnapshot prepareConfiguredMetroImport()
      throws IOException, InterruptedException, InvalidRequestException {
    var fetched = openStreetMapRestaurantClient.getConfiguredMetroRestaurants();
    var created = 0;
    var updated = 0;
    var unchanged = 0;
    var invalid = 0;
    var representativeChanges = new ArrayList<String>();

    for (var restaurant : fetched) {
      if (!isValidImportRestaurant(restaurant)) {
        invalid++;
        continue;
      }
      applyNormalizedName(restaurant);
      var existingById = restaurantRepository.findById(restaurant.getId());
      if (existingById.isPresent()
          && hasConflictingNormalizedNameOwner(existingById.get(), restaurant)) {
        unchanged++;
        continue;
      }
      var existing = existingById
          .or(() -> findRestaurantByNormalizedName(restaurant.getNormalizedName()));
      if (existing.isEmpty()) {
        created++;
        addRepresentativeChange(representativeChanges, "CREATE", restaurant);
      } else if (hasSameImportValues(existing.get(), restaurant)) {
        unchanged++;
      } else if (existing.get().getId().equals(restaurant.getId())
          || hasSameNameAndAddress(existing.get(), restaurant)) {
        updated++;
        addRepresentativeChange(representativeChanges, "UPDATE", restaurant);
      } else {
        unchanged++;
      }
    }

    return new RestaurantImportSnapshot(
        importChecksum(fetched),
        fetched,
        new RestaurantImportPreviewCounts(
            fetched.size(), created, updated, 0, unchanged, invalid),
        representativeChanges);
  }

  /** Applies a previously fetched in-memory snapshot. Durable preview records never contain this payload. */
  public RestaurantImportResult applyPreparedImport(
      RestaurantImportSnapshot snapshot,
      RestaurantImportLeaseGuard leaseGuard
  )
      throws InvalidRequestException {
    log.info("OpenStreetMap restaurant import started.");
    var fetched = snapshot.restaurants();
    log.info("OpenStreetMap restaurant import fetched {} candidates.", fetched.size());
    var imported = 0;
    var updated = 0;
    var skippedExisting = 0;
    var skippedInvalid = 0;

    for (Restaurant restaurant : fetched) {
      leaseGuard.verifyHeld();
      if (!isValidImportRestaurant(restaurant)) {
        skippedInvalid++;
        log.debug("Skipping invalid OpenStreetMap restaurant candidate: {}", restaurant);
        continue;
      }
      applyNormalizedName(restaurant);

      var existingById = restaurantRepository.findById(restaurant.getId());
      if (existingById.isPresent()) {
        if (hasConflictingNormalizedNameOwner(existingById.get(), restaurant)) {
          skippedExisting++;
          log.debug(
              "Skipping OpenStreetMap restaurant id {} because normalized name {} belongs to another restaurant.",
              restaurant.getId(), restaurant.getNormalizedName());
        } else if (mergeImportedRestaurant(existingById.get(), restaurant, true)) {
          restaurantRepository.save(existingById.get());
          updated++;
          log.info("Updated existing OpenStreetMap restaurant id: {}, name: {}",
              existingById.get().getId(), existingById.get().getName());
        } else {
          skippedExisting++;
          log.debug("Skipping unchanged OpenStreetMap restaurant id: {}", restaurant.getId());
        }
        continue;
      }

      var existingByName = findRestaurantByNormalizedName(restaurant.getNormalizedName());
      if (existingByName.isPresent()) {
        if (hasSameNameAndAddress(existingByName.get(), restaurant)) {
          if (mergeImportedRestaurant(existingByName.get(), restaurant, true)) {
            restaurantRepository.save(existingByName.get());
            updated++;
            log.info("Updated existing restaurant id: {}, name: {} from OpenStreetMap import",
                existingByName.get().getId(), existingByName.get().getName());
          } else {
            skippedExisting++;
            log.debug("Skipping unchanged OpenStreetMap restaurant name: {}", restaurant.getName());
          }
        } else {
          skippedExisting++;
          log.debug("Skipping duplicate OpenStreetMap restaurant name with different address: {}",
              restaurant.getName());
        }
        continue;
      }

      restaurantRepository.save(restaurant);
      imported++;
      log.info("Saved OpenStreetMap restaurant id: {}, name: {}", restaurant.getId(), restaurant.getName());
    }
    leaseGuard.verifyHeld();

    log.info("OpenStreetMap restaurant import completed. Imported: {}, updated: {}, fetched: {}, skipped existing: {}, skipped invalid: {}.",
        imported, updated, fetched.size(), skippedExisting, skippedInvalid);
    return RestaurantImportResult.builder()
        .source("openstreetmap")
        .fetched(fetched.size())
        .imported(imported)
        .updated(updated)
        .skippedExisting(skippedExisting)
        .skippedInvalid(skippedInvalid)
        .build();
  }

  private void addRepresentativeChange(
      List<String> changes,
      String operation,
      Restaurant restaurant
  ) {
    if (changes.size() < 10) {
      changes.add(operation + ": " + restaurant.getName());
    }
  }

  private boolean hasSameImportValues(Restaurant existing, Restaurant imported) {
    return canonicalImportValue(existing).equals(canonicalImportValue(imported));
  }

  private String importChecksum(List<Restaurant> restaurants) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      restaurants.stream()
          .map(this::canonicalImportValue)
          .sorted()
          .forEach(value -> digest.update((value + "\n").getBytes(StandardCharsets.UTF_8)));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private String canonicalImportValue(Restaurant restaurant) {
    if (restaurant == null) {
      return "<invalid>";
    }
    var address = restaurant.getAddress();
    return String.join("|",
        nullSafe(restaurant.getId()),
        nullSafe(restaurant.getName()),
        nullSafe(restaurant.getCuisine()),
        nullSafe(restaurant.getPhoneNumber()),
        nullSafe(restaurant.getSourceAmenity()),
        nullSafe(restaurant.getWebsite()),
        address == null ? "" : nullSafe(address.getStreet1()),
        address == null ? "" : nullSafe(address.getStreet2()),
        address == null ? "" : nullSafe(address.getCity()),
        address == null ? "" : nullSafe(address.getState()),
        address == null ? "" : nullSafe(address.getPostalCode()),
        address == null || address.getLatitude() == null ? "" : address.getLatitude().toString(),
        address == null || address.getLongitude() == null ? "" : address.getLongitude().toString());
  }

  /**
   * Gets a restaurant by a requested id.
   *
   * @param id of the requested restaurant.
   * @return WhatsForLunchResponse containing the requested restaurant.
   * @throws InvalidRequestException is id is null or empty, or if restaurant is not found.
   */
  public RestaurantDetail getRestaurantById(
      String id
  ) throws InvalidRequestException, ResourceNotFoundException {
    if (id == null || id.isBlank()) {
      throw new InvalidRequestException("Restaurant id cannot be null or blank.");
    }

    return restaurantRepository.findById(id)
        .map(this::toRatedDetail)
        .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));
  }

  /**
   * Updates an existing restaurant based on the provided request.
   *
   * @param request the details of the restaurant to update; must not be null and must include a valid id
   * @return the updated restaurant details
   * @throws InvalidRequestException if the request or id is null/blank
   * @throws ResourceNotFoundException if the restaurant with the given id does not exist
   * @throws ResourceExistsException if another restaurant already uses the same normalized name
   * @throws ServiceUnavailableException if persistence fails
   */
  public RestaurantDetail updateRestaurant(
      RestaurantUpdateRequest request
  ) throws ResourceNotFoundException, InvalidRequestException, ResourceExistsException {
    if (request == null || request.id() == null || request.id().isBlank()) {
      throw new InvalidRequestException("Restaurant id cannot be null or blank.");
    }

    var existing = restaurantRepository.findById(request.id())
        .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + request.id()));

    var restaurantToUpdate = restaurantMapper.toRestaurant(request);
    restaurantToUpdate.setWebsite(RestaurantWebsiteUrlPolicy.requireSafe(
        restaurantToUpdate.getWebsite()));
    restaurantToUpdate.setId(existing.getId());
    restaurantToUpdate.setCreatedBy(existing.getCreatedBy());
    restaurantToUpdate.setCreatedOn(existing.getCreatedOn());
    restaurantToUpdate.setLastModifiedBy(existing.getLastModifiedBy());
    restaurantToUpdate.setLastUpdatedOn(existing.getLastUpdatedOn());
    applyNormalizedName(restaurantToUpdate);
    ensureRestaurantNameUnique(restaurantToUpdate.getNormalizedName(), existing.getId());

    try {
      var saved = restaurantRepository.save(restaurantToUpdate);
      return toRatedDetail(saved);
    } catch (DataAccessException e) {
      throw new ServiceUnavailableException(
          "Failed to update restaurant with id: " + request.id(), e);
    }
  }

  /**
   * This is a nightly job that will select a random restaurant per day.
   */
  @Scheduled(
      cron = "${wfl.restaurant-of-the-day.cron}",
      zone = "${wfl.restaurant-of-the-day.zone:America/Chicago}"
  )
  public void setRestaurantOfTheDay() {
    if (!wflProperties.getRestaurantOfTheDay().isEnabled()) {
      return;
    }
    scheduledCollectors.run("wfl-daily-picks", DAILY_PICK_LEASE_DURATION, guard -> {
      var today = LocalDate.now(getRestaurantOfTheDayZone());
      var picks = refreshDailyLunchPicks(today, guard);
      log.info("Restaurant of the day selected {} picks for {}.", picks.getRestaurantIds().size(), today);
      return null;
    });
  }

  DailyLunchPicks refreshDailyLunchPicks(LocalDate pickDate) {
    return refreshDailyLunchPicks(pickDate, CollectorLeaseGuard.NONE);
  }

  private DailyLunchPicks refreshDailyLunchPicks(
      LocalDate pickDate,
      CollectorLeaseGuard guard) {
    var candidates = orderLunchCandidates(getSupportedMetroRestaurants());
    var restaurantIds = candidates.stream()
        .limit(dailyPickCount())
        .map(Restaurant::getId)
        .toList();
    var pick = DailyLunchPicks.builder()
        .id(pickDate.toString())
        .pickDate(pickDate.toString())
        .restaurantIds(restaurantIds)
        .generatedOn(Instant.now())
        .build();
    guard.verifyHeld();
    return dailyLunchPicksRepository.save(pick);
  }

  private DailyLunchPicks replaceDeletedRestaurantInTodaysPick(String deletedRestaurantId) {
    var today = LocalDate.now(getRestaurantOfTheDayZone());
    var existing = dailyLunchPicksRepository.findById(today.toString())
        .orElse(null);
    if (existing == null) {
      return refreshDailyLunchPicks(today);
    }

    var selectedIds = getExistingPickIdsWithoutDeletedRestaurant(existing, deletedRestaurantId);
    if (selectedIds.size() < dailyPickCount()) {
      var candidates = orderLunchCandidates(getSupportedMetroRestaurants().stream()
          .filter(restaurant -> !selectedIds.contains(restaurant.getId()))
          .toList());
      for (Restaurant candidate : candidates) {
        if (selectedIds.size() >= dailyPickCount()) {
          break;
        }
        selectedIds.add(candidate.getId());
      }
    }

    var pick = DailyLunchPicks.builder()
        .id(today.toString())
        .pickDate(today.toString())
        .restaurantIds(List.copyOf(selectedIds))
        .generatedOn(Instant.now())
        .build();
    log.info("Updated today's lunch picks after deleting restaurant id: {}. Pick count: {}.",
        deletedRestaurantId, pick.getRestaurantIds().size());
    return dailyLunchPicksRepository.save(pick);
  }

  private List<String> getExistingPickIdsWithoutDeletedRestaurant(
      DailyLunchPicks existing,
      String deletedRestaurantId
  ) {
    if (existing.getRestaurantIds() == null || existing.getRestaurantIds().isEmpty()) {
      return new ArrayList<>();
    }

    var candidateIds = existing.getRestaurantIds().stream()
        .filter(restaurantId -> !deletedRestaurantId.equals(restaurantId))
        .distinct()
        .toList();
    var existingRestaurantsById = restaurantRepository.findAllById(candidateIds).stream()
        .collect(Collectors.toMap(Restaurant::getId, Function.identity(), (left, ignored) -> left));
    return candidateIds.stream()
        .filter(existingRestaurantsById::containsKey)
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private RestaurantDetail toRatedDetail(Restaurant restaurant) {
    if (restaurant == null) {
      return null;
    }
    var details = toRatedDetails(List.of(restaurant));
    return details.isEmpty() ? null : details.get(0);
  }

  private List<RestaurantDetail> toRatedDetails(List<Restaurant> restaurants) {
    var safeRestaurants = Optional.ofNullable(restaurants).orElseGet(List::of);
    var details = safeRestaurants.stream()
        .map(restaurantMapper::toRestaurantDetail)
        .toList();
    details.forEach(detail -> detail.setWebsite(
        RestaurantWebsiteUrlPolicy.safeOrNull(detail.getWebsite())));
    applyRatingSummaries(details);
    return details;
  }

  private void applyRatingSummaries(List<RestaurantDetail> details) {
    if (details == null || details.isEmpty()) {
      return;
    }
    var restaurantIds = details.stream()
        .map(RestaurantDetail::getId)
        .filter(id -> id != null && !id.isBlank())
        .toList();
    if (restaurantIds.isEmpty()) {
      return;
    }
    details.forEach(detail -> {
      detail.setRatingCount(0);
      detail.setRatingSum(0);
      detail.setMyFavorite(false);
    });
    var ratings = restaurantRatingRepository == null
        ? List.<RestaurantRating>of()
        : Optional.ofNullable(restaurantRatingRepository.findByRestaurantIdIn(restaurantIds)).orElseGet(List::of);
    var ratingsByRestaurantId = ratings.stream()
        .collect(Collectors.groupingBy(RestaurantRating::getRestaurantId));
    var selfId = getSelfIdOrNull();
    var favoriteIds = selfId == null || restaurantFavoriteRepository == null
        ? java.util.Set.<String>of()
        : Optional.ofNullable(restaurantFavoriteRepository.findByRestaurantIdInAndAccountId(restaurantIds, selfId))
            .orElseGet(List::of)
            .stream()
            .map(RestaurantFavorite::getRestaurantId)
            .collect(Collectors.toSet());

    details.forEach(detail -> {
      var restaurantRatings = ratingsByRestaurantId.getOrDefault(detail.getId(), List.of());
      detail.setRatingCount(restaurantRatings.size());
      detail.setRatingSum(restaurantRatings.stream()
          .map(RestaurantRating::getRating)
          .filter(rating -> rating != null)
          .mapToInt(Integer::intValue)
          .sum());
      if (selfId != null) {
        restaurantRatings.stream()
            .filter(rating -> selfId.equals(rating.getAccountId()))
            .findFirst()
            .map(RestaurantRating::getRating)
            .ifPresent(detail::setMyRating);
      }
      detail.setMyFavorite(favoriteIds.contains(detail.getId()));
    });
  }

  private String validateRestaurantId(String restaurantId) throws InvalidRequestException {
    if (restaurantId == null || restaurantId.isBlank()) {
      throw new InvalidRequestException("Restaurant id cannot be null or blank.");
    }
    return restaurantId.strip();
  }

  private int validateRating(Object requestedRating) throws InvalidRequestException {
    if (!(requestedRating instanceof Integer rating) || rating < 1 || rating > 5) {
      throw new InvalidRequestException("Restaurant rating must be a whole number from 1 to 5.");
    }
    return rating;
  }

  private List<Restaurant> getSupportedMetroRestaurants() {
    var cityStates = wflProperties.getRestaurantImport().getOsm().getMetros().stream()
        .flatMap(metro -> metro.getCities().stream()
            .map(city -> normalizeCity(metro.getState()) + ":" + normalizeCity(city)))
        .collect(Collectors.toSet());
    return Optional.ofNullable(restaurantRepository.findAll()).orElseGet(List::of).stream()
        .filter(restaurant -> restaurant.getId() != null && !restaurant.getId().isBlank())
        .filter(restaurant -> restaurant.getAddress() != null)
        .filter(restaurant -> cityStates.contains(
            normalizeCity(restaurant.getAddress().getState()) + ":"
                + normalizeCity(restaurant.getAddress().getCity())))
        .toList();
  }

  private List<Restaurant> orderLunchCandidates(List<Restaurant> restaurants) {
    var candidates = new ArrayList<>(restaurants);
    Collections.shuffle(candidates, new Random());
    return candidates;
  }

  private List<String> resolveCuisineFilters(
      List<String> requestedCuisines,
      Optional<WhatsForLunchPreference> preference,
      boolean useSavedPreferences
  ) throws InvalidRequestException {
    var explicitFilters = normalizeCuisineFilters(requestedCuisines);
    if (!explicitFilters.isEmpty() || !useSavedPreferences) {
      return explicitFilters;
    }

    return preference
        .map(WhatsForLunchPreference::getCuisines)
        .map(cuisines -> {
          try {
            return normalizeCuisineFilters(cuisines);
          } catch (InvalidRequestException e) {
            log.warn("Ignoring invalid saved WFL cuisine filters.", e);
            return List.<String>of();
          }
        })
        .orElseGet(List::of);
  }

  private int resolveRadiusMiles(
      Integer requestedRadiusMiles,
      Optional<WhatsForLunchPreference> preference,
      boolean useSavedPreferences
  ) throws InvalidRequestException {
    if (requestedRadiusMiles != null) {
      return validateRadiusMiles(requestedRadiusMiles);
    }
    if (!useSavedPreferences) {
      return DEFAULT_NEARBY_LUNCH_RADIUS_MILES;
    }
    return preference
        .map(WhatsForLunchPreference::getRadiusMiles)
        .map(this::resolveSavedRadiusMiles)
        .orElse(DEFAULT_NEARBY_LUNCH_RADIUS_MILES);
  }

  private Optional<WhatsForLunchPreference> resolvePreference(boolean useSavedPreferences) {
    if (!useSavedPreferences) {
      return Optional.empty();
    }
    var accountId = getSelfIdOrNull();
    if (accountId == null) {
      return Optional.empty();
    }
    return whatsForLunchPreferenceRepository.findById(accountId);
  }

  private int resolveSavedRadiusMiles(Integer radiusMiles) {
    return ALLOWED_NEARBY_LUNCH_RADII_MILES.contains(radiusMiles)
        ? radiusMiles
        : DEFAULT_NEARBY_LUNCH_RADIUS_MILES;
  }

  private WhatsForLunchPreferenceDetail defaultPreferences() {
    return WhatsForLunchPreferenceDetail.builder()
        .cuisines(List.of())
        .radiusMiles(DEFAULT_NEARBY_LUNCH_RADIUS_MILES)
        .build();
  }

  private int validateRadiusMiles(Integer radiusMiles) throws InvalidRequestException {
    if (!ALLOWED_NEARBY_LUNCH_RADII_MILES.contains(radiusMiles)) {
      throw new InvalidRequestException("Radius must be one of: 1, 5, 10, 15, or 20 miles.");
    }
    return radiusMiles;
  }

  private List<String> normalizeCuisineFilters(List<String> cuisines) throws InvalidRequestException {
    if (cuisines == null) {
      return List.of();
    }
    var normalized = cuisines.stream()
        .flatMap(cuisine -> List.of(nullSafe(cuisine).split(",")).stream())
        .map(this::normalizeCuisineValue)
        .filter(cuisine -> !cuisine.isBlank())
        .distinct()
        .toList();
    if (normalized.size() > MAX_CUISINE_FILTERS) {
      throw new InvalidRequestException("No more than 20 cuisine filters can be selected.");
    }
    return normalized;
  }

  private String normalizeCuisineValue(String cuisine) {
    return nullSafe(cuisine)
        .strip()
        .toLowerCase(Locale.ROOT)
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceAll("\\s+", " ");
  }

  private boolean matchesCuisineFilters(Restaurant restaurant, List<String> cuisineFilters) {
    if (cuisineFilters == null || cuisineFilters.isEmpty()) {
      return true;
    }
    var restaurantCuisines = cuisineTokens(restaurant.getCuisine());
    if (restaurantCuisines.isEmpty()) {
      return false;
    }
    return cuisineFilters.stream()
        .anyMatch(filter -> filterAliases(filter).stream()
            .anyMatch(alias -> restaurantCuisines.stream().anyMatch(cuisine -> cuisine.contains(alias))));
  }

  private List<String> filterAliases(String filter) {
    return switch (filter) {
      case "barbecue" -> List.of("barbecue", "bbq");
      case "bbq" -> List.of("bbq", "barbecue");
      default -> List.of(filter);
    };
  }

  private List<String> cuisineTokens(String cuisine) {
    return List.of(nullSafe(cuisine).split("[;,/|]")).stream()
        .map(this::normalizeCuisineValue)
        .filter(token -> !token.isBlank())
        .toList();
  }

  private String getSelfIdOrNull() {
    try {
      return permissionService.getSelfId();
    } catch (Exception e) {
      return null;
    }
  }

  private List<Restaurant> getRestaurantsForPick(DailyLunchPicks pick) {
    if (pick == null || pick.getRestaurantIds() == null || pick.getRestaurantIds().isEmpty()) {
      return List.of();
    }
    Map<String, Restaurant> restaurantsById = restaurantRepository.findAllById(pick.getRestaurantIds()).stream()
        .collect(Collectors.toMap(Restaurant::getId, Function.identity(), (left, ignored) -> left));
    return pick.getRestaurantIds().stream()
        .map(restaurantsById::get)
        .filter(restaurant -> restaurant != null)
        .toList();
  }

  private String normalizeCity(String value) {
    return nullSafe(value).strip().toLowerCase(Locale.ROOT);
  }

  private String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private ZoneId getRestaurantOfTheDayZone() {
    return ZoneId.of(wflProperties.getRestaurantOfTheDay().getZone());
  }

  private int dailyPickCount() {
    return wflProperties.getRestaurantOfTheDay().getPickCount();
  }

  private boolean isValidImportRestaurant(Restaurant restaurant) {
    return restaurant != null
        && restaurant.getId() != null
        && !restaurant.getId().isBlank()
        && restaurant.getName() != null
        && !restaurant.getName().isBlank()
        && hasSupportedImportLocation(restaurant)
        && (restaurant.getWebsite() == null
            || restaurant.getWebsite().isBlank()
            || RestaurantWebsiteUrlPolicy.safeOrNull(restaurant.getWebsite()) != null);
  }

  private boolean hasSupportedImportLocation(Restaurant restaurant) {
    if (!hasCoordinates(restaurant)) {
      return false;
    }
    var address = restaurant.getAddress();
    var city = normalizeCity(address.getCity());
    var state = normalizeCity(address.getState());
    if (city.isBlank() || state.isBlank() || !isUnitedStates(address.getCountry())) {
      return false;
    }
    return wflProperties.getRestaurantImport().getOsm().getMetros().stream()
        .anyMatch(metro -> normalizeCity(metro.getState()).equals(state)
            && metro.getCities().stream().anyMatch(candidate -> normalizeCity(candidate).equals(city))
            && isWithinBounds(
                metro.getBounds(), address.getLatitude(), address.getLongitude()));
  }

  private boolean isWithinBounds(
      WflProperties.BoundingBox bounds,
      double latitude,
      double longitude
  ) {
    return latitude >= bounds.getSouth()
        && latitude <= bounds.getNorth()
        && longitude >= bounds.getWest()
        && longitude <= bounds.getEast();
  }

  private boolean isUnitedStates(String value) {
    var normalized = normalizeCity(value).replaceAll("[^a-z]", "");
    return List.of("us", "usa", "unitedstates").contains(normalized);
  }

  private boolean mergeImportedRestaurant(
      Restaurant existing,
      Restaurant imported,
      boolean updateNameAndAddress
  ) throws InvalidRequestException {
    var updated = false;

    if (updateNameAndAddress && copyStringIfPresent(existing::getName, existing::setName, imported.getName())) {
      updated = true;
    }

    if (updateNameAndAddress && mergeAddress(existing, imported)) {
      updated = true;
    }

    if (copyStringIfPresent(existing::getPhoneNumber, existing::setPhoneNumber, imported.getPhoneNumber())) {
      updated = true;
    }
    if (copyStringIfPresent(existing::getWebsite, existing::setWebsite, imported.getWebsite())) {
      updated = true;
    }
    if (copyStringIfPresent(existing::getCuisine, existing::setCuisine, imported.getCuisine())) {
      updated = true;
    }
    if (copyStringIfPresent(existing::getSourceAmenity, existing::setSourceAmenity, imported.getSourceAmenity())) {
      updated = true;
    }

    var oldNormalizedName = existing.getNormalizedName();
    applyNormalizedName(existing);
    return updated || !nullSafe(oldNormalizedName).equals(nullSafe(existing.getNormalizedName()));
  }

  private boolean mergeAddress(Restaurant existing, Restaurant imported) {
    if (imported.getAddress() == null) {
      return false;
    }
    if (existing.getAddress() == null) {
      existing.setAddress(imported.getAddress());
      return true;
    }

    var updated = false;
    var existingAddress = existing.getAddress();
    var importedAddress = imported.getAddress();
    if (copyStringIfPresent(existingAddress::getStreet1, existingAddress::setStreet1, importedAddress.getStreet1())) {
      updated = true;
    }
    if (copyStringIfPresent(existingAddress::getStreet2, existingAddress::setStreet2, importedAddress.getStreet2())) {
      updated = true;
    }
    if (copyStringIfPresent(existingAddress::getCity, existingAddress::setCity, importedAddress.getCity())) {
      updated = true;
    }
    if (copyStringIfPresent(existingAddress::getCounty, existingAddress::setCounty, importedAddress.getCounty())) {
      updated = true;
    }
    if (copyStringIfPresent(existingAddress::getState, existingAddress::setState, importedAddress.getState())) {
      updated = true;
    }
    if (copyStringIfPresent(existingAddress::getCountry, existingAddress::setCountry, importedAddress.getCountry())) {
      updated = true;
    }
    if (copyDoubleIfPresent(existingAddress::getLatitude, existingAddress::setLatitude, importedAddress.getLatitude())) {
      updated = true;
    }
    if (copyDoubleIfPresent(existingAddress::getLongitude, existingAddress::setLongitude, importedAddress.getLongitude())) {
      updated = true;
    }
    if (copyStringIfPresent(existingAddress::getPostalCode, existingAddress::setPostalCode, importedAddress.getPostalCode())) {
      updated = true;
    }
    return updated;
  }

  private boolean copyStringIfPresent(
      java.util.function.Supplier<String> existingValue,
      java.util.function.Consumer<String> setter,
      String importedValue
  ) {
    if (importedValue == null || importedValue.isBlank()) {
      return false;
    }
    var sanitizedImportedValue = importedValue.strip();
    if (sanitizedImportedValue.equals(nullSafe(existingValue.get()))) {
      return false;
    }
    setter.accept(sanitizedImportedValue);
    return true;
  }

  private boolean copyDoubleIfPresent(
      java.util.function.Supplier<Double> existingValue,
      java.util.function.Consumer<Double> setter,
      Double importedValue
  ) {
    if (importedValue == null || importedValue.isNaN() || importedValue.isInfinite()) {
      return false;
    }
    if (importedValue.equals(existingValue.get())) {
      return false;
    }
    setter.accept(importedValue);
    return true;
  }

  private boolean hasCoordinates(Restaurant restaurant) {
    if (restaurant.getAddress() == null) {
      return false;
    }
    return isValidCoordinate(restaurant.getAddress().getLatitude(), -90.0, 90.0)
        && isValidCoordinate(restaurant.getAddress().getLongitude(), -180.0, 180.0);
  }

  private String normalizeZipCode(String zipCode) throws InvalidRequestException {
    var normalized = normalizePostalCode(zipCode);
    if (normalized.isBlank()) {
      throw new InvalidRequestException("ZIP code must be a valid 5-digit US ZIP code.");
    }
    return normalized;
  }

  private String normalizePostalCode(String postalCode) {
    if (postalCode == null) {
      return "";
    }
    var normalized = postalCode.strip();
    if (normalized.matches("\\d{5}")) {
      return normalized;
    }
    if (normalized.matches("\\d{5}-\\d{4}")) {
      return normalized.substring(0, 5);
    }
    return "";
  }

  private List<Restaurant> getNearbyCandidateRestaurants(
      double latitude,
      double longitude,
      int radiusMiles
  ) {
    var bounds = coordinateBounds(latitude, longitude, radiusMiles);
    return Optional.ofNullable(restaurantRepository.findByCoordinateBounds(
        bounds.minLatitude(),
        bounds.maxLatitude(),
        bounds.minLongitude(),
        bounds.maxLongitude()))
        .orElseGet(List::of);
  }

  private CoordinateBounds coordinateBounds(double latitude, double longitude, int radiusMiles) {
    var latitudeDelta = Math.toDegrees(radiusMiles / EARTH_RADIUS_MILES);
    var minLatitude = clamp(latitude - latitudeDelta, -90.0, 90.0);
    var maxLatitude = clamp(latitude + latitudeDelta, -90.0, 90.0);
    var cosine = Math.abs(Math.cos(Math.toRadians(latitude)));
    if (cosine < 0.000001) {
      return new CoordinateBounds(minLatitude, maxLatitude, -180.0, 180.0);
    }

    var longitudeDelta = Math.toDegrees(radiusMiles / (EARTH_RADIUS_MILES * cosine));
    var minLongitude = longitude - longitudeDelta;
    var maxLongitude = longitude + longitudeDelta;
    if (minLongitude < -180.0 || maxLongitude > 180.0) {
      return new CoordinateBounds(minLatitude, maxLatitude, -180.0, 180.0);
    }
    return new CoordinateBounds(minLatitude, maxLatitude, minLongitude, maxLongitude);
  }

  private record CoordinateBounds(
      double minLatitude,
      double maxLatitude,
      double minLongitude,
      double maxLongitude
  ) {}

  private double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(value, max));
  }

  private boolean isValidCoordinate(Double value, double min, double max) {
    return value != null && !value.isNaN() && !value.isInfinite() && value >= min && value <= max;
  }

  private void validateCoordinates(double latitude, double longitude) throws InvalidRequestException {
    if (!isValidCoordinate(latitude, -90.0, 90.0) || !isValidCoordinate(longitude, -180.0, 180.0)) {
      throw new InvalidRequestException("Latitude and longitude must be valid coordinates.");
    }
  }

  private double distanceMiles(double latitude1, double longitude1, double latitude2, double longitude2) {
    var latitudeDistance = Math.toRadians(latitude2 - latitude1);
    var longitudeDistance = Math.toRadians(longitude2 - longitude1);
    var a = Math.sin(latitudeDistance / 2) * Math.sin(latitudeDistance / 2)
        + Math.cos(Math.toRadians(latitude1))
        * Math.cos(Math.toRadians(latitude2))
        * Math.sin(longitudeDistance / 2)
        * Math.sin(longitudeDistance / 2);
    return 2 * EARTH_RADIUS_MILES * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  private boolean hasSameNameAndAddress(Restaurant existing, Restaurant imported) {
    if (!normalizeRestaurantName(nullSafe(existing.getName()))
        .equals(normalizeRestaurantName(nullSafe(imported.getName())))) {
      return false;
    }
    if (existing.getAddress() == null || imported.getAddress() == null) {
      return false;
    }

    var existingStreet = normalizeAddressValue(existing.getAddress().getStreet1());
    var importedStreet = normalizeAddressValue(imported.getAddress().getStreet1());
    if (existingStreet.isBlank() || importedStreet.isBlank() || !existingStreet.equals(importedStreet)) {
      return false;
    }
    return addressValuesCompatible(existing.getAddress().getCity(), imported.getAddress().getCity())
        && addressValuesCompatible(existing.getAddress().getState(), imported.getAddress().getState())
        && addressValuesCompatible(existing.getAddress().getPostalCode(), imported.getAddress().getPostalCode());
  }

  private boolean addressValuesCompatible(String existing, String imported) {
    var normalizedExisting = normalizeAddressValue(existing);
    var normalizedImported = normalizeAddressValue(imported);
    return normalizedExisting.isBlank()
        || normalizedImported.isBlank()
        || normalizedExisting.equals(normalizedImported);
  }

  private String normalizeAddressValue(String value) {
    return nullSafe(value)
        .strip()
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]", "");
  }

  private void applyNormalizedName(Restaurant restaurant) throws InvalidRequestException {
    if (restaurant == null || restaurant.getName() == null || restaurant.getName().isBlank()) {
      throw new InvalidRequestException("Restaurant name cannot be null or blank.");
    }
    var normalizedName = normalizeRestaurantName(restaurant.getName());
    restaurant.setNormalizedName(normalizedName);
    restaurant.setDedupeKey(normalizedName);
    var address = restaurant.getAddress();
    restaurant.setSearchCity(address == null ? "" : normalizeFilterValue(address.getCity()));
    restaurant.setSearchState(address == null ? "" : normalizeFilterValue(address.getState()));
  }

  private String normalizeFilterValue(String value) {
    return nullSafe(value).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private void ensureRestaurantNameUnique(String normalizedName, String selfId)
      throws ResourceExistsException {
    var owner = findRestaurantByNormalizedName(normalizedName);
    if (owner.isPresent() && (selfId == null || !selfId.equals(owner.get().getId()))) {
      throw new ResourceExistsException("Restaurant with that name already exists.");
    }
  }

  /** Returns whether another restaurant owns an imported rename before mutation occurs. */
  private boolean hasConflictingNormalizedNameOwner(
      Restaurant persistedById,
      Restaurant imported
  ) {
    if (java.util.Objects.equals(
        persistedById.getNormalizedName(), imported.getNormalizedName())) {
      return false;
    }
    return findRestaurantByNormalizedName(imported.getNormalizedName()).stream()
        .anyMatch(owner -> !java.util.Objects.equals(owner.getId(), persistedById.getId()));
  }

  private Optional<Restaurant> findRestaurantByNormalizedName(String normalizedName) {
    var indexedOwner = restaurantRepository.findByNormalizedName(normalizedName);
    if (indexedOwner.isPresent()) {
      return indexedOwner;
    }
    return restaurantRepository.findAll().stream()
        .filter(restaurant -> normalizedName.equals(normalizeRestaurantName(nullSafe(restaurant.getName()))))
        .findFirst();
  }

  private String normalizeRestaurantName(String name) {
    return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private Restaurant chooseDuplicateSurvivor(List<Restaurant> restaurants) {
    return restaurants.stream()
        .min(Comparator.comparing(restaurant -> nullSafe(restaurant.getId())))
        .orElseThrow();
  }

  private String restaurantCity(Restaurant restaurant) {
    return restaurant == null || restaurant.getAddress() == null
        ? ""
        : nullSafe(restaurant.getAddress().getCity());
  }
}
