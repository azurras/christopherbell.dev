package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantUpdateRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
@Slf4j
public class RestaurantService {
  private static final String DEFAULT_AUSTIN_METRO_CITIES =
      "Austin,Round Rock,Cedar Park,Georgetown,Pflugerville,Leander,Hutto,Manor,Buda,Kyle,"
          + "Bee Cave,Lakeway,Dripping Springs,Bastrop,San Marcos,Austin Metro";
  private static final List<String> FAST_FOOD_NAME_PARTS = List.of(
      "arbys",
      "burger king",
      "chick-fil-a",
      "chipotle",
      "domino's",
      "dominos",
      "jack in the box",
      "jimmy john",
      "kfc",
      "mcdonald",
      "panda express",
      "pizza hut",
      "popeyes",
      "sonic",
      "subway",
      "taco bell",
      "wendy's",
      "whataburger"
  );
  private static final double EARTH_RADIUS_MILES = 3958.7613;
  private static final int NEARBY_LUNCH_PICK_COUNT = 3;
  private static final double NEARBY_LUNCH_RADIUS_MILES = 15.0;

  private final DailyLunchPicksRepository dailyLunchPicksRepository;
  private final OpenStreetMapRestaurantClient openStreetMapRestaurantClient;
  private final RestaurantMapper restaurantMapper;
  private final RestaurantRepository restaurantRepository;

  @Value("${wfl.restaurant-of-the-day.enabled:false}")
  private boolean restaurantOfTheDayEnabled;
  @Value("${wfl.restaurant-of-the-day.pick-count:3}")
  private int dailyPickCount = 3;
  @Value("${wfl.restaurant-of-the-day.zone:America/Chicago}")
  private String restaurantOfTheDayZone = "America/Chicago";
  @Value("${wfl.restaurant-of-the-day.austin-metro-cities:" + DEFAULT_AUSTIN_METRO_CITIES + "}")
  private String austinMetroCities = DEFAULT_AUSTIN_METRO_CITIES;

  /**
   * Creates a new restaurant based on the provided request.
   *
   * @param request containing the details of the restaurant to be created.
   * @return a WhatsForLunchResponse containing the created restaurant details.
   * @throws Exception if there is an error during the creation process.
   */
  public RestaurantDetail createRestaurant(RestaurantCreateRequest request) throws Exception {
    var restaurant = restaurantMapper.toRestaurant(request);
    applyNormalizedName(restaurant);
    ensureRestaurantNameUnique(restaurant.getNormalizedName(), null);

    try {
      var savedRestaurant = restaurantRepository.save(restaurant);
      return restaurantMapper.toRestaurantDetail(savedRestaurant);
    } catch (DuplicateKeyException e) {
      throw new ResourceExistsException("Restaurant already exists", e);
    } catch (DataAccessException e) {
      throw new RuntimeException("Failed to save restaurant", e);
    }
  }

  /**
   * Deletes a restaurant by the provided id.
   *
   * @param id the restaurant id to delete; must not be null or blank
   * @return the details of the deleted restaurant (snapshot of its state before deletion)
   * @throws InvalidRequestException if the id is null or blank
   * @throws ResourceNotFoundException if no restaurant with the provided id exists
   * @throws RuntimeException if the deletion fails due to persistence errors
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
      throw new RuntimeException("Failed to delete restaurant with id: " + id, e);
    }
    return restaurantMapper.toRestaurantDetail(restaurant);
  }

  /**
   * Gets all existing restaurants.
   *
   * @return a WhatsForLunchResponse containing a list of all existing restaurants
   */
  public List<RestaurantDetail> getRestaurants() {
    var restaurants = Optional.of(restaurantRepository.findAll())
        .orElseGet(List::of);
    return restaurants.stream()
        .map(restaurantMapper::toRestaurantDetail)
        .toList();
  }

  /**
   * Gets today's lunch picks, generating them on demand if midnight scheduling has not run yet.
   *
   * @return up to the configured number of Austin metro restaurant picks
   */
  public List<RestaurantDetail> getTodaysLunchPicks() {
    var today = LocalDate.now(getRestaurantOfTheDayZone());
    var existing = dailyLunchPicksRepository.findById(today.toString())
        .orElseGet(() -> refreshDailyLunchPicks(today));
    var picks = getRestaurantsForPick(existing);
    if (picks.size() < Math.min(dailyPickCount, getAustinMetroRestaurants().size())) {
      picks = getRestaurantsForPick(refreshDailyLunchPicks(today));
    }
    return picks.stream()
        .map(restaurantMapper::toRestaurantDetail)
        .toList();
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
    validateCoordinates(latitude, longitude);

    var candidates = Optional.ofNullable(restaurantRepository.findAll()).orElseGet(List::of).stream()
        .filter(restaurant -> restaurant.getId() != null && !restaurant.getId().isBlank())
        .filter(this::hasCoordinates)
        .filter(restaurant -> distanceMiles(
            latitude,
            longitude,
            restaurant.getAddress().getLatitude(),
            restaurant.getAddress().getLongitude()) <= NEARBY_LUNCH_RADIUS_MILES)
        .toList();

    return orderLunchCandidates(candidates).stream()
        .limit(NEARBY_LUNCH_PICK_COUNT)
        .map(restaurantMapper::toRestaurantDetail)
        .toList();
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
      throw new RuntimeException("Failed to delete restaurant with id: " + id, e);
    }

    log.info("Deleted today's lunch restaurant id: {}.", id);
    var updatedPick = replaceDeletedRestaurantInTodaysPick(id);
    return getRestaurantsForPick(updatedPick).stream()
        .map(restaurantMapper::toRestaurantDetail)
        .toList();
  }

  /**
   * Removes duplicate restaurant names, preferring the Austin record when a duplicate group has one.
   *
   * @return cleanup summary
   */
  public RestaurantDedupeResult removeDuplicateNamedRestaurants() {
    log.info("Restaurant duplicate-name cleanup started.");
    var restaurants = Optional.ofNullable(restaurantRepository.findAll()).orElseGet(List::of);
    var duplicateGroups = restaurants.stream()
        .filter(restaurant -> restaurant.getName() != null && !restaurant.getName().isBlank())
        .collect(Collectors.groupingBy(restaurant -> normalizeRestaurantName(restaurant.getName())))
        .entrySet()
        .stream()
        .filter(entry -> entry.getValue().size() > 1)
        .toList();

    var keptIds = new ArrayList<String>();
    var deletedIds = new ArrayList<String>();
    var updatedSurvivors = 0;

    for (var duplicateGroup : duplicateGroups) {
      var normalizedName = duplicateGroup.getKey();
      var group = duplicateGroup.getValue();
      var survivor = chooseDuplicateSurvivor(group);
      var duplicates = group.stream()
          .filter(restaurant -> !restaurant.getId().equals(survivor.getId()))
          .toList();

      restaurantRepository.deleteAll(duplicates);
      duplicates.forEach(restaurant -> {
        deletedIds.add(restaurant.getId());
        log.info("Deleted duplicate restaurant id: {}, name: {}, city: {}",
            restaurant.getId(), restaurant.getName(), restaurantCity(restaurant));
      });

      if (!normalizedName.equals(survivor.getNormalizedName())) {
        survivor.setNormalizedName(normalizedName);
        restaurantRepository.save(survivor);
        updatedSurvivors++;
      }
      keptIds.add(survivor.getId());
      log.info("Kept restaurant id: {}, name: {}, city: {}",
          survivor.getId(), survivor.getName(), restaurantCity(survivor));
    }

    log.info("Restaurant duplicate-name cleanup completed. Duplicate groups: {}, deleted: {}, updated survivors: {}.",
        duplicateGroups.size(), deletedIds.size(), updatedSurvivors);
    return RestaurantDedupeResult.builder()
        .duplicateGroups(duplicateGroups.size())
        .deleted(deletedIds.size())
        .updatedSurvivors(updatedSurvivors)
        .keptRestaurantIds(List.copyOf(keptIds))
        .deletedRestaurantIds(List.copyOf(deletedIds))
        .build();
  }

  /**
   * Imports Austin metro lunch spots from OpenStreetMap via Overpass.
   *
   * @return import summary
   */
  public RestaurantImportResult importAustinMetroRestaurantsFromOpenStreetMap()
      throws IOException, InterruptedException, InvalidRequestException {
    log.info("OpenStreetMap restaurant import started.");
    var fetched = openStreetMapRestaurantClient.getAustinMetroRestaurants();
    log.info("OpenStreetMap restaurant import fetched {} candidates.", fetched.size());
    var imported = 0;
    var updated = 0;
    var skippedExisting = 0;
    var skippedInvalid = 0;

    for (Restaurant restaurant : fetched) {
      if (!isValidImportRestaurant(restaurant)) {
        skippedInvalid++;
        log.debug("Skipping invalid OpenStreetMap restaurant candidate: {}", restaurant);
        continue;
      }
      applyNormalizedName(restaurant);

      var existingById = restaurantRepository.findById(restaurant.getId());
      if (existingById.isPresent()) {
        if (mergeImportedRestaurant(existingById.get(), restaurant, true)) {
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
        .map(restaurantMapper::toRestaurantDetail)
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
   * @throws RuntimeException if persistence fails
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
    restaurantToUpdate.setId(existing.getId());
    restaurantToUpdate.setCreatedBy(existing.getCreatedBy());
    restaurantToUpdate.setCreatedOn(existing.getCreatedOn());
    restaurantToUpdate.setLastModifiedBy(existing.getLastModifiedBy());
    restaurantToUpdate.setLastUpdatedOn(existing.getLastUpdatedOn());
    applyNormalizedName(restaurantToUpdate);
    ensureRestaurantNameUnique(restaurantToUpdate.getNormalizedName(), existing.getId());

    try {
      var saved = restaurantRepository.save(restaurantToUpdate);
      return restaurantMapper.toRestaurantDetail(saved);
    } catch (DataAccessException e) {
      throw new RuntimeException("Failed to update restaurant with id: " + request.id(), e);
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
    if (!restaurantOfTheDayEnabled) {
      return;
    }
    log.info("Restaurant of the day job started.");
    try {
      var today = LocalDate.now(getRestaurantOfTheDayZone());
      var picks = refreshDailyLunchPicks(today);
      log.info("Restaurant of the day selected {} picks for {}.", picks.getRestaurantIds().size(), today);
    } finally {
      log.info("Restaurant of the day job completed.");
    }
  }

  DailyLunchPicks refreshDailyLunchPicks(LocalDate pickDate) {
    var candidates = orderLunchCandidates(getAustinMetroRestaurants());
    var restaurantIds = candidates.stream()
        .limit(Math.max(0, dailyPickCount))
        .map(Restaurant::getId)
        .toList();
    var pick = DailyLunchPicks.builder()
        .id(pickDate.toString())
        .pickDate(pickDate.toString())
        .restaurantIds(restaurantIds)
        .generatedOn(Instant.now())
        .build();
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
    if (selectedIds.size() < Math.max(0, dailyPickCount)) {
      var candidates = orderLunchCandidates(getAustinMetroRestaurants().stream()
          .filter(restaurant -> !selectedIds.contains(restaurant.getId()))
          .toList());
      for (Restaurant candidate : candidates) {
        if (selectedIds.size() >= Math.max(0, dailyPickCount)) {
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

  private List<Restaurant> getAustinMetroRestaurants() {
    var cities = parseAustinMetroCities();
    return Optional.ofNullable(restaurantRepository.findAll()).orElseGet(List::of).stream()
        .filter(restaurant -> restaurant.getId() != null && !restaurant.getId().isBlank())
        .filter(restaurant -> restaurant.getAddress() != null)
        .filter(restaurant -> "TX".equalsIgnoreCase(nullSafe(restaurant.getAddress().getState())))
        .filter(restaurant -> cities.contains(normalizeCity(restaurant.getAddress().getCity())))
        .toList();
  }

  private List<Restaurant> orderLunchCandidates(List<Restaurant> restaurants) {
    var preferred = new ArrayList<>(restaurants.stream()
        .filter(restaurant -> !isFastFoodRestaurant(restaurant))
        .toList());
    var fallback = new ArrayList<>(restaurants.stream()
        .filter(this::isFastFoodRestaurant)
        .toList());
    Collections.shuffle(preferred, new Random());
    Collections.shuffle(fallback, new Random());
    preferred.addAll(fallback);
    return preferred;
  }

  private boolean isFastFoodRestaurant(Restaurant restaurant) {
    if ("fast_food".equalsIgnoreCase(nullSafe(restaurant.getSourceAmenity()))) {
      return true;
    }
    var normalizedName = normalizeRestaurantName(nullSafe(restaurant.getName()))
        .replaceAll("[^a-z0-9 -]", "");
    return FAST_FOOD_NAME_PARTS.stream().anyMatch(normalizedName::contains);
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

  private List<String> parseAustinMetroCities() {
    return List.of(austinMetroCities.split(",")).stream()
        .map(this::normalizeCity)
        .filter(city -> !city.isBlank())
        .toList();
  }

  private String normalizeCity(String value) {
    return nullSafe(value).strip().toLowerCase(Locale.ROOT);
  }

  private String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private ZoneId getRestaurantOfTheDayZone() {
    return ZoneId.of(restaurantOfTheDayZone);
  }

  private boolean isValidImportRestaurant(Restaurant restaurant) {
    return restaurant != null
        && restaurant.getId() != null
        && !restaurant.getId().isBlank()
        && restaurant.getName() != null
        && !restaurant.getName().isBlank()
        && restaurant.getAddress() != null;
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
    restaurant.setNormalizedName(normalizeRestaurantName(restaurant.getName()));
  }

  private void ensureRestaurantNameUnique(String normalizedName, String selfId)
      throws ResourceExistsException {
    var owner = findRestaurantByNormalizedName(normalizedName);
    if (owner.isPresent() && (selfId == null || !selfId.equals(owner.get().getId()))) {
      throw new ResourceExistsException("Restaurant with that name already exists.");
    }
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
        .min(Comparator
            .comparing((Restaurant restaurant) -> !isAustinRestaurant(restaurant))
            .thenComparing(restaurant -> nullSafe(restaurant.getId())))
        .orElseThrow();
  }

  private boolean isAustinRestaurant(Restaurant restaurant) {
    return "austin".equals(normalizeCity(restaurantCity(restaurant)));
  }

  private String restaurantCity(Restaurant restaurant) {
    return restaurant == null || restaurant.getAddress() == null
        ? ""
        : nullSafe(restaurant.getAddress().getCity());
  }
}
