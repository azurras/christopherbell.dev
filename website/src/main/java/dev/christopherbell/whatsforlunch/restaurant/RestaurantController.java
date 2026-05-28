package dev.christopherbell.whatsforlunch.restaurant;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavoriteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRatingRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRatingSetRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantUpdateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionVoteRequest;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionService;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for restaurant management under {@code /api/whatsforlunch/restaurant}.
 */
@RequiredArgsConstructor
@RequestMapping("/api/whatsforlunch/restaurant")
@RestController
public class RestaurantController {

  private final PermissionService permissionService;
  private final RestaurantService restaurantService;
  private final WhatsForLunchSessionService whatsForLunchSessionService;

  /**
   * Creates a new restaurant.
   *
   * @param request create request payload
   * @return HTTP 201 with the created restaurant
   */
  @PostMapping(
      value = APIVersion.V20250912,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<RestaurantDetail>> createRestaurant(
      @RequestBody RestaurantCreateRequest request
  ) throws Exception {
    var response = restaurantService.createRestaurant(request);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.CREATED);
  }

  /**
   * Deletes an existing restaurant.
   *
   * @param id the restaurant ID to delete
   * @return HTTP 200 with the deleted restaurant
   */
  @DeleteMapping(
      value = APIVersion.V20250913 + "/{id}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<RestaurantDetail>> deleteRestaurantById(
      @PathVariable String id
  ) throws Exception {
    var response = restaurantService.deleteRestaurantById(id);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Retrieves a restaurant by ID.
   *
   * @param id the restaurant ID
   * @return HTTP 200 with the matching restaurant
   */
  @GetMapping(value = APIVersion.V20250912 + "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<RestaurantDetail>> getRestaurantById(
      @PathVariable String id
  ) throws Exception {
    var response = restaurantService.getRestaurantById(id);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Lists all restaurants.
   *
   * @return HTTP 200 with all restaurants
   */
  @GetMapping(value = APIVersion.V20250912, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<RestaurantDetail>>> getRestaurants() throws Exception {
    var response = restaurantService.getRestaurants();
    return new ResponseEntity<>(
        Response.<List<RestaurantDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Gets today's public lunch picks.
   *
   * @return HTTP 200 with up to three supported metro restaurant picks
   */
  @GetMapping(value = APIVersion.V20260517 + "/today", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<RestaurantDetail>>> getTodaysLunchPicks() {
    var response = restaurantService.getTodaysLunchPicks();
    return new ResponseEntity<>(
        Response.<List<RestaurantDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Gets fresh public lunch picks near a user's browser-provided location.
   *
   * @param latitude user latitude
   * @param longitude user longitude
   * @return HTTP 200 with up to three nearby restaurant picks
   */
  @GetMapping(value = APIVersion.V20260517 + "/nearby", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<RestaurantDetail>>> getNearbyLunchPicks(
      @RequestParam double latitude,
      @RequestParam double longitude,
      @RequestParam(value = "radiusMiles", required = false) Integer radiusMiles,
      @RequestParam(value = "cuisine", required = false) List<String> cuisines,
      @RequestParam(value = "useSavedPreferences", required = false, defaultValue = "true")
      boolean useSavedPreferences
  ) throws Exception {
    var response = restaurantService.getNearbyLunchPicks(
        latitude,
        longitude,
        radiusMiles,
        cuisines,
        useSavedPreferences);
    return new ResponseEntity<>(
        Response.<List<RestaurantDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Gets fresh public lunch picks near a user-provided ZIP code.
   *
   * @param zipCode user ZIP code
   * @return HTTP 200 with up to three nearby restaurant picks
   */
  @GetMapping(value = APIVersion.V20260517 + "/nearby/zip/{zipCode}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<RestaurantDetail>>> getNearbyLunchPicksByZipCode(
      @PathVariable String zipCode,
      @RequestParam(value = "radiusMiles", required = false) Integer radiusMiles,
      @RequestParam(value = "cuisine", required = false) List<String> cuisines,
      @RequestParam(value = "useSavedPreferences", required = false, defaultValue = "true")
      boolean useSavedPreferences
  ) throws Exception {
    var response = restaurantService.getNearbyLunchPicksByZipCode(
        zipCode,
        radiusMiles,
        cuisines,
        useSavedPreferences);
    return new ResponseEntity<>(
        Response.<List<RestaurantDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Retrieves a public restaurant profile by ID.
   *
   * @param id the restaurant ID
   * @return HTTP 200 with the matching restaurant
   */
  @GetMapping(value = APIVersion.V20260517 + "/profile/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<RestaurantDetail>> getPublicRestaurantById(
      @PathVariable String id
  ) throws Exception {
    var response = restaurantService.getRestaurantById(id);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Lists top-rated public WFL restaurants.
   *
   * @param limit maximum number of restaurants to return
   * @return HTTP 200 with rated restaurants sorted highest first
   */
  @GetMapping(value = APIVersion.V20260517 + "/top-rated", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<List<RestaurantDetail>>> getTopRatedRestaurants(
      @RequestParam(value = "limit", required = false, defaultValue = "10") int limit
  ) {
    var response = restaurantService.getTopRatedRestaurants(limit);
    return new ResponseEntity<>(
        Response.<List<RestaurantDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Gets saved WFL filters for a signed-in user or defaults for anonymous visitors.
   *
   * @return HTTP 200 with saved or default cuisine filters
  */
  @GetMapping(value = APIVersion.V20260517 + "/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<WhatsForLunchPreferenceDetail>> getMyPreferences() {
    var response = restaurantService.getPreferencesForCurrentViewer();
    return new ResponseEntity<>(
        Response.<WhatsForLunchPreferenceDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Saves the signed-in user's WFL filters.
   *
   * @param request selected cuisine filters
   * @return HTTP 200 with the saved filters
   */
  @PutMapping(
      value = APIVersion.V20260517 + "/preferences",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<WhatsForLunchPreferenceDetail>> updateMyPreferences(
      @RequestBody WhatsForLunchPreferenceRequest request
  ) throws Exception {
    var response = restaurantService.updateMyPreferences(request);
    return new ResponseEntity<>(
        Response.<WhatsForLunchPreferenceDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Sets the signed-in user's whole-number rating for a restaurant.
   *
   * @param id restaurant id
   * @param request rating payload
   * @return HTTP 200 with the restaurant's updated public rating totals
   */
  @PutMapping(
      value = APIVersion.V20260517 + "/{id}/rating",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<RestaurantDetail>> rateRestaurant(
      @PathVariable String id,
      @RequestBody RestaurantRatingRequest request
  ) throws Exception {
    var response = restaurantService.rateRestaurant(id, request);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Sets the signed-in user's whole-number rating without putting provider ids in the path.
   *
   * @param request restaurant id and rating payload
   * @return HTTP 200 with the restaurant's updated public rating totals
   */
  @PutMapping(
      value = APIVersion.V20260517 + "/rating",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<RestaurantDetail>> rateRestaurant(
      @RequestBody RestaurantRatingSetRequest request
  ) throws Exception {
    var response = restaurantService.rateRestaurant(
        request == null ? null : request.restaurantId(),
        new RestaurantRatingRequest(request == null ? null : request.rating()));
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Lists the signed-in user's favorite restaurants.
   *
   * @return HTTP 200 with favorite restaurants
   */
  @GetMapping(value = APIVersion.V20260517 + "/favorites", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<List<RestaurantDetail>>> getMyFavoriteRestaurants() {
    var response = restaurantService.getMyFavoriteRestaurants();
    return new ResponseEntity<>(
        Response.<List<RestaurantDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Favorites a restaurant for the signed-in user.
   *
   * @param request restaurant id payload
   * @return HTTP 200 with the updated restaurant detail
   */
  @PutMapping(
      value = APIVersion.V20260517 + "/favorite",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<RestaurantDetail>> favoriteRestaurant(
      @RequestBody RestaurantFavoriteRequest request
  ) throws Exception {
    var response = restaurantService.favoriteRestaurant(request);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Removes a restaurant favorite for the signed-in user.
   *
   * @param request restaurant id payload
   * @return HTTP 200 with the updated restaurant detail
   */
  @DeleteMapping(
      value = APIVersion.V20260517 + "/favorite",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<RestaurantDetail>> unfavoriteRestaurant(
      @RequestBody RestaurantFavoriteRequest request
  ) throws Exception {
    var response = restaurantService.unfavoriteRestaurant(request);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Creates a shared WFL voting session from the current three restaurant picks.
   *
   * @param request session restaurant ids and invited usernames
   * @return HTTP 201 with the created session
   */
  @PostMapping(
      value = APIVersion.V20260517 + "/sessions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<WhatsForLunchSessionDetail>> createSession(
      @RequestBody WhatsForLunchSessionCreateRequest request
  ) throws Exception {
    var response = whatsForLunchSessionService.createSession(request);
    return new ResponseEntity<>(
        Response.<WhatsForLunchSessionDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.CREATED);
  }

  /**
   * Lists recent shared WFL sessions for the signed-in user.
   *
   * @param limit maximum number of sessions to return
   * @return HTTP 200 with recent sessions
  */
  @GetMapping(value = APIVersion.V20260517 + "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<List<WhatsForLunchSessionDetail>>> getMySessions(
      @RequestParam(value = "limit", required = false, defaultValue = "10") int limit
  ) throws Exception {
    var response = whatsForLunchSessionService.getMySessions(limit);
    return new ResponseEntity<>(
        Response.<List<WhatsForLunchSessionDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Gets a shared WFL session when the caller is a participant.
   *
   * @param sessionId session id
   * @return HTTP 200 with the session
  */
  @GetMapping(value = APIVersion.V20260517 + "/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<WhatsForLunchSessionDetail>> getSession(
      @PathVariable String sessionId
  ) throws Exception {
    var response = whatsForLunchSessionService.getSession(sessionId);
    return new ResponseEntity<>(
        Response.<WhatsForLunchSessionDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Joins a shared WFL session from a link.
   *
   * @param sessionId session id
   * @return HTTP 200 with the joined session
  */
  @PostMapping(value = APIVersion.V20260517 + "/sessions/{sessionId}/join", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<WhatsForLunchSessionDetail>> joinSession(
      @PathVariable String sessionId
  ) throws Exception {
    var response = whatsForLunchSessionService.joinSession(sessionId);
    return new ResponseEntity<>(
        Response.<WhatsForLunchSessionDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Casts or updates the caller's vote in a shared WFL session.
   *
   * @param sessionId session id
   * @param request selected restaurant id
   * @return HTTP 200 with the updated session
   */
  @PutMapping(
      value = APIVersion.V20260517 + "/sessions/{sessionId}/vote",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<WhatsForLunchSessionDetail>> voteInSession(
      @PathVariable String sessionId,
      @RequestBody WhatsForLunchSessionVoteRequest request
  ) throws Exception {
    var response = whatsForLunchSessionService.vote(sessionId, request);
    return new ResponseEntity<>(
        Response.<WhatsForLunchSessionDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Replaces the restaurants in a shared WFL session.
   *
   * @param sessionId session id
   * @param request selected restaurant ids
   * @return HTTP 200 with the updated session
   */
  @PutMapping(
      value = APIVersion.V20260517 + "/sessions/{sessionId}/restaurants",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Response<WhatsForLunchSessionDetail>> updateSessionRestaurants(
      @PathVariable String sessionId,
      @RequestBody WhatsForLunchSessionRestaurantsRequest request
  ) throws Exception {
    var response = whatsForLunchSessionService.updateRestaurants(sessionId, request);
    return new ResponseEntity<>(
        Response.<WhatsForLunchSessionDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Deletes a restaurant from today's lunch picks and replaces it when possible.
   *
   * @param id the restaurant ID to delete
   * @return HTTP 200 with today's updated lunch picks
   */
  @DeleteMapping(value = APIVersion.V20260517 + "/today/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<RestaurantDetail>>> deleteRestaurantFromTodaysLunchPicks(
      @PathVariable String id
  ) throws Exception {
    var response = restaurantService.deleteRestaurantFromTodaysLunchPicks(id);
    return new ResponseEntity<>(
        Response.<List<RestaurantDetail>>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Imports configured metro restaurants from OpenStreetMap.
   *
   * @return HTTP 200 with import counts
   */
  @PostMapping(value = APIVersion.V20260517 + "/import/openstreetmap", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<RestaurantImportResult>> importOpenStreetMapRestaurants()
      throws Exception {
    var response = restaurantService.importConfiguredMetroRestaurantsFromOpenStreetMap();
    return new ResponseEntity<>(
        Response.<RestaurantImportResult>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Removes duplicate restaurant names, keeping one stable survivor per group.
   *
   * @return HTTP 200 with cleanup counts
   */
  @PostMapping(value = APIVersion.V20260517 + "/dedupe-names", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<RestaurantDedupeResult>> removeDuplicateNamedRestaurants() {
    var response = restaurantService.removeDuplicateNamedRestaurants();
    return new ResponseEntity<>(
        Response.<RestaurantDedupeResult>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Updates an existing restaurant.
   *
   * @return HTTP 202 with the updated restaurant
   */
  @PutMapping(
      value = APIVersion.V20250913,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<RestaurantDetail>> updateRestaurantById(
      @RequestBody RestaurantUpdateRequest request
  ) throws Exception {
    var response = restaurantService.updateRestaurant(request);
    return new ResponseEntity<>(
        Response.<RestaurantDetail>builder()
            .payload(response)
            .success(true)
            .build(), HttpStatus.ACCEPTED);
  }
}
