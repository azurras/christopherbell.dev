package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavorite;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavoriteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportState;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRating;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRatingRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreference;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestaurantService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantService unit tests")
public class RestaurantServiceTest {
  @Mock private Clock clock;
  @Mock private DailyLunchPicksRepository dailyLunchPicksRepository;
  @Mock private OpenStreetMapRestaurantClient openStreetMapRestaurantClient;
  @Mock private PermissionService permissionService;
  @Mock private RestaurantImportStateRepository restaurantImportStateRepository;
  @Mock private RestaurantMapper restaurantMapper;
  @Mock private RestaurantFavoriteRepository restaurantFavoriteRepository;
  @Mock private RestaurantRatingRepository restaurantRatingRepository;
  @Mock private RestaurantRepository restaurantRepository;
  @Mock private WhatsForLunchPreferenceRepository whatsForLunchPreferenceRepository;
  @InjectMocks private RestaurantService restaurantService;

  @Test
  @DisplayName("Maps request -> entity, saves, maps to detail, returns detail")
  public void testCreateRestaurant_whenValidRequest_ReturnsRestaurantDetail() throws Exception {
    var request = RestaurantStub.getRestaurantCreateRequestStub();
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var saved = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);

    when(restaurantMapper.toRestaurant(eq(request))).thenReturn(restaurant);
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house"))).thenReturn(Optional.empty());
    when(restaurantRepository.findAll()).thenReturn(List.of());
    when(restaurantRepository.save(eq(restaurant))).thenReturn(saved);
    when(restaurantMapper.toRestaurantDetail(eq(saved))).thenReturn(detail);

    var result = restaurantService.createRestaurant(request);

    assertSame(detail, result, "Expected the mapped detail to be returned");
    assertEquals("pflugerville taco house", restaurant.getNormalizedName());
    verify(restaurantMapper).toRestaurant(eq(request));
    verify(restaurantRepository).findByNormalizedName(eq("pflugerville taco house"));
    verify(restaurantRepository).findAll();
    verify(restaurantRepository).save(eq(restaurant));
    verify(restaurantMapper).toRestaurantDetail(eq(saved));
    verifyNoMoreInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Create: duplicate normalized name -> throws ResourceExistsException")
  public void testCreateRestaurant_whenNormalizedNameExists_ThrowsResourceExistsException() {
    var request = RestaurantStub.getRestaurantCreateRequestStub();
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var existing = RestaurantStub.getRestaurantStub(RestaurantStub.ID_2);
    existing.setNormalizedName("pflugerville taco house");

    when(restaurantMapper.toRestaurant(eq(request))).thenReturn(restaurant);
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house")))
        .thenReturn(Optional.of(existing));

    var ex = assertThrows(ResourceExistsException.class, () -> restaurantService.createRestaurant(request));

    assertTrue(ex.getMessage().contains("already exists"));
    verify(restaurantMapper).toRestaurant(eq(request));
    verify(restaurantRepository).findByNormalizedName(eq("pflugerville taco house"));
    verify(restaurantRepository, never()).save(eq(restaurant));
  }

  @Test
  @DisplayName("Translates DuplicateKeyException into ResourceExistsException")
  public void testCreateRestaurant_whenDuplicateKey_ThrowsResourceExistsException() {
    var request = RestaurantStub.getRestaurantCreateRequestStub();
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);

    when(restaurantMapper.toRestaurant(eq(request))).thenReturn(restaurant);
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house"))).thenReturn(Optional.empty());
    when(restaurantRepository.findAll()).thenReturn(List.of());
    when(restaurantRepository.save(eq(restaurant))).thenThrow(DuplicateKeyException.class);

    var ex = assertThrows(ResourceExistsException.class, () -> restaurantService.createRestaurant(request));
    assertTrue(ex.getMessage().contains("already exists"));

    verify(restaurantMapper).toRestaurant(eq(request));
    verify(restaurantRepository).findByNormalizedName(eq("pflugerville taco house"));
    verify(restaurantRepository).findAll();
    verify(restaurantRepository).save(eq(restaurant));
    verifyNoMoreInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Wraps DataAccessException into RuntimeException with message")
  public void testCreateRestaurant_whenDataAccessFails_ThrowsRuntimeException() {
    var request = RestaurantStub.getRestaurantCreateRequestStub();
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);

    when(restaurantMapper.toRestaurant(eq(request))).thenReturn(restaurant);
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house"))).thenReturn(Optional.empty());
    when(restaurantRepository.findAll()).thenReturn(List.of());
    when(restaurantRepository.save(eq(restaurant))).thenThrow(new DataAccessException("boom") {});

    var ex = assertThrows(RuntimeException.class, () -> restaurantService.createRestaurant(request));
    assertTrue(ex.getMessage().contains("Failed to save restaurant"));

    verify(restaurantMapper).toRestaurant(eq(request));
    verify(restaurantRepository).findByNormalizedName(eq("pflugerville taco house"));
    verify(restaurantRepository).findAll();
    verify(restaurantRepository).save(eq(restaurant));
    verifyNoMoreInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Returns mapped list when repository returns restaurants")
  public void testGetRestaurants_whenSomeExist_ReturnsMappedList() {
    var restaurant1 = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var restaurant2 = RestaurantStub.getRestaurantStub(RestaurantStub.ID_2);
    var restaurantDetail1 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var restaurantDetail2 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);

    when(restaurantRepository.findAll()).thenReturn(List.of(restaurant1, restaurant2));
    when(restaurantMapper.toRestaurantDetail(eq(restaurant1))).thenReturn(restaurantDetail1);
    when(restaurantMapper.toRestaurantDetail(eq(restaurant2))).thenReturn(restaurantDetail2);

    var result = restaurantService.getRestaurants();

    assertEquals(2, result.size());
    assertTrue(result.containsAll(List.of(restaurantDetail1, restaurantDetail2)));

    verify(restaurantRepository).findAll();
    verify(restaurantMapper).toRestaurantDetail(eq(restaurant1));
    verify(restaurantMapper).toRestaurantDetail(eq(restaurant2));
    verifyNoMoreInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Returns empty list when repository returns empty list")
  public void testGetRestaurants_whenNoneExist_ReturnsEmptyList() {
    when(restaurantRepository.findAll()).thenReturn(List.of());

    var result = restaurantService.getRestaurants();

    assertNotNull(result);
    assertTrue(result.isEmpty());

    verify(restaurantRepository).findAll();
    verifyNoMoreInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Nearby lunch picks: returns three restaurants within fifteen miles")
  public void testGetNearbyLunchPicks_ReturnsNearbyRestaurants() throws Exception {
    var austin = nearbyRestaurant("austin", "Austin Bistro", 30.2672, -97.7431);
    var eastAustin = nearbyRestaurant("east-austin", "East Austin Cafe", 30.2634, -97.6981);
    var southAustin = nearbyRestaurant("south-austin", "South Austin Diner", 30.2270, -97.7697);
    var dallas = nearbyRestaurant("dallas", "Dallas Lunch", 32.7767, -96.7970);
    var austinDetail = RestaurantStub.getRestaurantDetailStub("austin");
    var eastAustinDetail = RestaurantStub.getRestaurantDetailStub("east-austin");
    var southAustinDetail = RestaurantStub.getRestaurantDetailStub("south-austin");

    when(restaurantRepository.findAll()).thenReturn(List.of(austin, eastAustin, southAustin, dallas));
    when(restaurantMapper.toRestaurantDetail(eq(austin))).thenReturn(austinDetail);
    when(restaurantMapper.toRestaurantDetail(eq(eastAustin))).thenReturn(eastAustinDetail);
    when(restaurantMapper.toRestaurantDetail(eq(southAustin))).thenReturn(southAustinDetail);

    var result = restaurantService.getNearbyLunchPicks(30.2672, -97.7431);
    var ids = result.stream().map(RestaurantDetail::getId).toList();

    assertEquals(3, result.size());
    assertTrue(ids.containsAll(List.of("austin", "east-austin", "south-austin")));
    verify(restaurantRepository).findAll();
  }

  @Test
  @DisplayName("Nearby lunch picks: explicit cuisine filters limit candidates")
  public void testGetNearbyLunchPicks_whenCuisineFiltersProvided_ReturnsMatchingRestaurants()
      throws Exception {
    var tacos = nearbyRestaurant("tacos", "Austin Tacos", 30.2672, -97.7431);
    tacos.setCuisine("mexican;tex-mex");
    var sushi = nearbyRestaurant("sushi", "Austin Sushi", 30.2673, -97.7432);
    sushi.setCuisine("japanese");
    var tacoDetail = RestaurantStub.getRestaurantDetailStub("tacos");

    when(restaurantRepository.findAll()).thenReturn(List.of(tacos, sushi));
    when(restaurantMapper.toRestaurantDetail(eq(tacos))).thenReturn(tacoDetail);

    var result = restaurantService.getNearbyLunchPicks(30.2672, -97.7431, 15, List.of("Mexican"), false);

    assertEquals(List.of(tacoDetail), result);
    verify(restaurantRepository).findAll();
    verify(restaurantMapper).toRestaurantDetail(eq(tacos));
    verifyNoInteractions(whatsForLunchPreferenceRepository);
  }

  @Test
  @DisplayName("Nearby lunch picks: saved cuisine filters are used when request has none")
  public void testGetNearbyLunchPicks_whenNoCuisineProvided_UsesSavedPreferences()
      throws Exception {
    var tacos = nearbyRestaurant("tacos", "Austin Tacos", 30.2672, -97.7431);
    tacos.setCuisine("mexican");
    var sushi = nearbyRestaurant("sushi", "Austin Sushi", 30.2673, -97.7432);
    sushi.setCuisine("japanese");
    var sushiDetail = RestaurantStub.getRestaurantDetailStub("sushi");

    when(permissionService.getSelfId()).thenReturn("account-1");
    when(whatsForLunchPreferenceRepository.findById(eq("account-1")))
        .thenReturn(Optional.of(WhatsForLunchPreference.builder()
            .accountId("account-1")
            .cuisines(List.of("japanese"))
            .radiusMiles(15)
            .build()));
    when(restaurantRepository.findAll()).thenReturn(List.of(tacos, sushi));
    when(restaurantMapper.toRestaurantDetail(eq(sushi))).thenReturn(sushiDetail);

    var result = restaurantService.getNearbyLunchPicks(30.2672, -97.7431, List.of());

    assertEquals(List.of(sushiDetail), result);
    verify(permissionService, times(2)).getSelfId();
    verify(whatsForLunchPreferenceRepository).findById(eq("account-1"));
  }

  @Test
  @DisplayName("Nearby lunch picks: saved filters can be bypassed")
  public void testGetNearbyLunchPicks_whenSavedPreferencesDisabled_DoesNotLoadPreferences()
      throws Exception {
    var tacos = nearbyRestaurant("tacos", "Austin Tacos", 30.2672, -97.7431);
    tacos.setCuisine("mexican");
    var tacoDetail = RestaurantStub.getRestaurantDetailStub("tacos");

    when(restaurantRepository.findAll()).thenReturn(List.of(tacos));
    when(restaurantMapper.toRestaurantDetail(eq(tacos))).thenReturn(tacoDetail);

    var result = restaurantService.getNearbyLunchPicks(30.2672, -97.7431, null, List.of(), false);

    assertEquals(List.of(tacoDetail), result);
    verifyNoInteractions(whatsForLunchPreferenceRepository);
  }

  @Test
  @DisplayName("Nearby lunch picks: selected radius limits candidates")
  public void testGetNearbyLunchPicks_whenRadiusProvided_UsesRequestedRadius()
      throws Exception {
    var close = nearbyRestaurant("close", "Close Tacos", 30.2672, -97.7431);
    var farther = nearbyRestaurant("farther", "Farther Tacos", 30.4423, -97.6200);
    var closeDetail = RestaurantStub.getRestaurantDetailStub("close");

    when(restaurantRepository.findAll()).thenReturn(List.of(close, farther));
    when(restaurantMapper.toRestaurantDetail(eq(close))).thenReturn(closeDetail);

    var result = restaurantService.getNearbyLunchPicks(30.2672, -97.7431, 1, List.of(), false);

    assertEquals(List.of(closeDetail), result);
    verifyNoInteractions(whatsForLunchPreferenceRepository);
  }

  @Test
  @DisplayName("Nearby lunch picks: ZIP code uses saved restaurant coordinates as the origin")
  public void testGetNearbyLunchPicksByZipCode_whenZipMatchesRestaurants_ReturnsNearbyRestaurants()
      throws Exception {
    var zipCenter = nearbyRestaurant("zip-center", "Downtown Tacos", 30.2672, -97.7431);
    zipCenter.getAddress().setPostalCode("78701");
    var nearby = nearbyRestaurant("nearby", "Nearby Noodles", 30.2680, -97.7440);
    nearby.getAddress().setPostalCode("78702");
    var dallas = nearbyRestaurant("dallas", "Dallas Lunch", 32.7767, -96.7970);
    dallas.getAddress().setPostalCode("75201");
    var zipDetail = RestaurantStub.getRestaurantDetailStub("zip-center");
    var nearbyDetail = RestaurantStub.getRestaurantDetailStub("nearby");

    when(restaurantRepository.findAll()).thenReturn(List.of(zipCenter, nearby, dallas));
    when(restaurantMapper.toRestaurantDetail(eq(zipCenter))).thenReturn(zipDetail);
    when(restaurantMapper.toRestaurantDetail(eq(nearby))).thenReturn(nearbyDetail);

    var result = restaurantService.getNearbyLunchPicksByZipCode("78701", 15, List.of(), false);
    var ids = result.stream().map(RestaurantDetail::getId).toList();

    assertEquals(2, result.size());
    assertTrue(ids.containsAll(List.of("zip-center", "nearby")));
    verifyNoInteractions(whatsForLunchPreferenceRepository);
  }

  @Test
  @DisplayName("Nearby lunch picks: saved radius is used when request radius is missing")
  public void testGetNearbyLunchPicks_whenNoRadiusProvided_UsesSavedRadius()
      throws Exception {
    var close = nearbyRestaurant("close", "Close Tacos", 30.2672, -97.7431);
    var farther = nearbyRestaurant("farther", "Farther Tacos", 30.4423, -97.6200);
    var closeDetail = RestaurantStub.getRestaurantDetailStub("close");

    when(permissionService.getSelfId()).thenReturn("account-1");
    when(whatsForLunchPreferenceRepository.findById(eq("account-1")))
        .thenReturn(Optional.of(WhatsForLunchPreference.builder()
            .accountId("account-1")
            .cuisines(List.of())
            .radiusMiles(1)
            .build()));
    when(restaurantRepository.findAll()).thenReturn(List.of(close, farther));
    when(restaurantMapper.toRestaurantDetail(eq(close))).thenReturn(closeDetail);

    var result = restaurantService.getNearbyLunchPicks(30.2672, -97.7431, List.of());

    assertEquals(List.of(closeDetail), result);
    verify(permissionService, times(2)).getSelfId();
    verify(whatsForLunchPreferenceRepository).findById(eq("account-1"));
  }

  @Test
  @DisplayName("Nearby lunch picks: invalid radius is rejected")
  public void testGetNearbyLunchPicks_whenRadiusInvalid_ThrowsInvalidRequestException() {
    assertThrows(
        InvalidRequestException.class,
        () -> restaurantService.getNearbyLunchPicks(30.2672, -97.7431, 2, List.of(), false));

    verifyNoInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Preferences: returns saved filters for current user")
  public void testGetMyPreferences_whenSavedPreferencesExist_ReturnsCuisines() {
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(whatsForLunchPreferenceRepository.findById(eq("account-1")))
        .thenReturn(Optional.of(WhatsForLunchPreference.builder()
            .accountId("account-1")
            .cuisines(List.of("mexican", "thai"))
            .radiusMiles(10)
            .build()));

    var result = restaurantService.getMyPreferences();

    assertEquals(List.of("mexican", "thai"), result.cuisines());
    assertEquals(10, result.radiusMiles());
    verify(permissionService).getSelfId();
    verify(whatsForLunchPreferenceRepository).findById(eq("account-1"));
  }

  @Test
  @DisplayName("Preferences: current viewer gets defaults when anonymous")
  public void testGetPreferencesForCurrentViewer_whenAnonymous_ReturnsDefaults() {
    when(permissionService.getSelfId()).thenThrow(IllegalStateException.class);

    var result = restaurantService.getPreferencesForCurrentViewer();

    assertEquals(List.of(), result.cuisines());
    assertEquals(15, result.radiusMiles());
    verify(permissionService).getSelfId();
    verifyNoInteractions(whatsForLunchPreferenceRepository);
  }

  @Test
  @DisplayName("Preferences: current viewer gets saved filters when authenticated")
  public void testGetPreferencesForCurrentViewer_whenAuthenticated_ReturnsSavedFilters() {
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(whatsForLunchPreferenceRepository.findById(eq("account-1")))
        .thenReturn(Optional.of(WhatsForLunchPreference.builder()
            .accountId("account-1")
            .cuisines(List.of("kebab"))
            .radiusMiles(5)
            .build()));

    var result = restaurantService.getPreferencesForCurrentViewer();

    assertEquals(List.of("kebab"), result.cuisines());
    assertEquals(5, result.radiusMiles());
    verify(permissionService).getSelfId();
    verify(whatsForLunchPreferenceRepository).findById(eq("account-1"));
  }

  @Test
  @DisplayName("Preferences: save normalizes and deduplicates filters")
  public void testUpdateMyPreferences_NormalizesAndSavesCuisines() throws Exception {
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(whatsForLunchPreferenceRepository.save(org.mockito.ArgumentMatchers.any(WhatsForLunchPreference.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = restaurantService.updateMyPreferences(
        new WhatsForLunchPreferenceRequest(List.of(" Mexican ", "mexican", "BBQ, Thai"), 20));

    assertEquals(List.of("mexican", "bbq", "thai"), result.cuisines());
    assertEquals(20, result.radiusMiles());
    verify(whatsForLunchPreferenceRepository).save(org.mockito.ArgumentMatchers.argThat(preference ->
        "account-1".equals(preference.getAccountId())
            && preference.getCuisines().equals(List.of("mexican", "bbq", "thai"))
            && Integer.valueOf(20).equals(preference.getRadiusMiles())));
  }

  @Test
  @DisplayName("Preferences: rejects too many cuisine filters")
  public void testUpdateMyPreferences_whenTooManyFilters_ThrowsInvalidRequestException() {
    var cuisines = java.util.stream.IntStream.range(0, 21)
        .mapToObj(index -> "cuisine-" + index)
        .toList();
    when(permissionService.getSelfId()).thenReturn("account-1");

    assertThrows(
        InvalidRequestException.class,
        () -> restaurantService.updateMyPreferences(new WhatsForLunchPreferenceRequest(cuisines, 15)));

    verifyNoInteractions(whatsForLunchPreferenceRepository);
  }

  @Test
  @DisplayName("Preferences: rejects unsupported radius")
  public void testUpdateMyPreferences_whenRadiusInvalid_ThrowsInvalidRequestException() {
    when(permissionService.getSelfId()).thenReturn("account-1");

    assertThrows(
        InvalidRequestException.class,
        () -> restaurantService.updateMyPreferences(
            new WhatsForLunchPreferenceRequest(List.of("mexican"), 2)));

    verifyNoInteractions(whatsForLunchPreferenceRepository);
  }

  @Test
  @DisplayName("Ratings: saves whole-number rating and returns aggregate totals")
  public void testRateRestaurant_whenValidWholeNumber_savesRatingAndReturnsTotals() throws Exception {
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var now = Instant.parse("2026-05-19T12:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(restaurantRepository.findById(eq(RestaurantStub.ID))).thenReturn(Optional.of(restaurant));
    when(restaurantRatingRepository.findByRestaurantIdAndAccountId(eq(RestaurantStub.ID), eq("account-1")))
        .thenReturn(Optional.empty());
    when(restaurantRatingRepository.save(any(RestaurantRating.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(restaurantMapper.toRestaurantDetail(eq(restaurant))).thenReturn(detail);
    when(restaurantRatingRepository.findByRestaurantIdIn(eq(List.of(RestaurantStub.ID))))
        .thenReturn(List.of(
            RestaurantRating.builder().restaurantId(RestaurantStub.ID).accountId("account-1").rating(5).build(),
            RestaurantRating.builder().restaurantId(RestaurantStub.ID).accountId("account-2").rating(4).build()));

    var result = restaurantService.rateRestaurant(RestaurantStub.ID, new RestaurantRatingRequest(5));

    assertEquals(9, result.getRatingSum());
    assertEquals(2, result.getRatingCount());
    assertEquals(5, result.getMyRating());
    verify(restaurantRatingRepository).save(argThat(rating ->
        RestaurantStub.ID.equals(rating.getRestaurantId())
            && "account-1".equals(rating.getAccountId())
            && Integer.valueOf(5).equals(rating.getRating())));
  }

  @Test
  @DisplayName("Ratings: rejects fractional ratings")
  public void testRateRestaurant_whenFractionalRating_ThrowsInvalidRequestException() {
    assertThrows(
        InvalidRequestException.class,
        () -> restaurantService.rateRestaurant(RestaurantStub.ID, new RestaurantRatingRequest(3.5)));

    verifyNoInteractions(restaurantRepository, restaurantRatingRepository);
  }

  @Test
  @DisplayName("Ratings: rejects out-of-range whole numbers")
  public void testRateRestaurant_whenRatingOutOfRange_ThrowsInvalidRequestException() {
    assertThrows(
        InvalidRequestException.class,
        () -> restaurantService.rateRestaurant(RestaurantStub.ID, new RestaurantRatingRequest(6)));

    verifyNoInteractions(restaurantRepository, restaurantRatingRepository);
  }

  @Test
  @DisplayName("Favorites: saves favorite and returns restaurant marked as favorite")
  public void testFavoriteRestaurant_whenValid_savesFavoriteAndReturnsFavoriteDetail() throws Exception {
    var now = Instant.parse("2026-05-20T10:00:00Z");
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var favorite = RestaurantFavorite.builder()
        .restaurantId(RestaurantStub.ID)
        .accountId("account-1")
        .createdOn(now)
        .build();

    when(clock.instant()).thenReturn(now);
    when(permissionService.getSelfId()).thenReturn("account-1");
    when(restaurantRepository.findById(eq(RestaurantStub.ID))).thenReturn(Optional.of(restaurant));
    when(restaurantFavoriteRepository.findByRestaurantIdAndAccountId(eq(RestaurantStub.ID), eq("account-1")))
        .thenReturn(Optional.empty());
    when(restaurantMapper.toRestaurantDetail(eq(restaurant))).thenReturn(detail);
    when(restaurantFavoriteRepository.findByRestaurantIdInAndAccountId(eq(List.of(RestaurantStub.ID)), eq("account-1")))
        .thenReturn(List.of(favorite));

    var result = restaurantService.favoriteRestaurant(new RestaurantFavoriteRequest(RestaurantStub.ID));

    assertEquals(true, result.getMyFavorite());
    verify(restaurantFavoriteRepository).save(argThat(saved ->
        RestaurantStub.ID.equals(saved.getRestaurantId()) && "account-1".equals(saved.getAccountId())));
  }

  @Test
  @DisplayName("Favorites: deletes favorite and returns restaurant marked as not favorite")
  public void testUnfavoriteRestaurant_whenValid_deletesFavoriteAndReturnsNonFavoriteDetail() throws Exception {
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);

    when(permissionService.getSelfId()).thenReturn("account-1");
    when(restaurantRepository.findById(eq(RestaurantStub.ID))).thenReturn(Optional.of(restaurant));
    when(restaurantMapper.toRestaurantDetail(eq(restaurant))).thenReturn(detail);
    when(restaurantFavoriteRepository.findByRestaurantIdInAndAccountId(eq(List.of(RestaurantStub.ID)), eq("account-1")))
        .thenReturn(List.of());

    var result = restaurantService.unfavoriteRestaurant(new RestaurantFavoriteRequest(RestaurantStub.ID));

    assertEquals(false, result.getMyFavorite());
    verify(restaurantFavoriteRepository).deleteByRestaurantIdAndAccountId(eq(RestaurantStub.ID), eq("account-1"));
  }

  @Test
  @DisplayName("Favorites: lists current user's restaurants newest favorite first")
  public void testGetMyFavoriteRestaurants_returnsFavoritesInFavoriteOrder() {
    var first = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var second = RestaurantStub.getRestaurantStub(RestaurantStub.ID_2);
    var firstDetail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var secondDetail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);
    var favorites = List.of(
        RestaurantFavorite.builder().restaurantId(RestaurantStub.ID_2).accountId("account-1").build(),
        RestaurantFavorite.builder().restaurantId(RestaurantStub.ID).accountId("account-1").build());

    when(permissionService.getSelfId()).thenReturn("account-1");
    when(restaurantFavoriteRepository.findByAccountIdOrderByCreatedOnDesc(eq("account-1")))
        .thenReturn(favorites);
    when(restaurantRepository.findAllById(eq(List.of(RestaurantStub.ID_2, RestaurantStub.ID))))
        .thenReturn(List.of(first, second));
    when(restaurantMapper.toRestaurantDetail(eq(second))).thenReturn(secondDetail);
    when(restaurantMapper.toRestaurantDetail(eq(first))).thenReturn(firstDetail);
    when(restaurantFavoriteRepository.findByRestaurantIdInAndAccountId(
        eq(List.of(RestaurantStub.ID_2, RestaurantStub.ID)), eq("account-1")))
        .thenReturn(favorites);

    var result = restaurantService.getMyFavoriteRestaurants();

    assertEquals(List.of(secondDetail, firstDetail), result);
    assertEquals(true, result.get(0).getMyFavorite());
    assertEquals(true, result.get(1).getMyFavorite());
  }

  @Test
  @DisplayName("Top rated: excludes unrated restaurants and sorts by average then count")
  public void testGetTopRatedRestaurants_sortsByAverageThenCount() {
    var fiveWithTwoRatings = RestaurantStub.getRestaurantStub("five-two");
    var fiveWithOneRating = RestaurantStub.getRestaurantStub("five-one");
    var fourStar = RestaurantStub.getRestaurantStub("four-star");
    var fiveWithTwoRatingsDetail = RestaurantStub.getRestaurantDetailStub("five-two");
    var fiveWithOneRatingDetail = RestaurantStub.getRestaurantDetailStub("five-one");
    var fourStarDetail = RestaurantStub.getRestaurantDetailStub("four-star");
    var ratings = List.of(
        RestaurantRating.builder().restaurantId("five-one").accountId("account-1").rating(5).build(),
        RestaurantRating.builder().restaurantId("five-two").accountId("account-1").rating(5).build(),
        RestaurantRating.builder().restaurantId("five-two").accountId("account-2").rating(5).build(),
        RestaurantRating.builder().restaurantId("four-star").accountId("account-1").rating(4).build());

    when(restaurantRatingRepository.findAll()).thenReturn(ratings);
    when(restaurantRepository.findAllById(eq(List.of("five-two", "five-one", "four-star"))))
        .thenReturn(List.of(fiveWithOneRating, fourStar, fiveWithTwoRatings));
    when(restaurantMapper.toRestaurantDetail(eq(fiveWithTwoRatings))).thenReturn(fiveWithTwoRatingsDetail);
    when(restaurantMapper.toRestaurantDetail(eq(fiveWithOneRating))).thenReturn(fiveWithOneRatingDetail);
    when(restaurantMapper.toRestaurantDetail(eq(fourStar))).thenReturn(fourStarDetail);
    when(restaurantRatingRepository.findByRestaurantIdIn(eq(List.of("five-two", "five-one", "four-star"))))
        .thenReturn(ratings);

    var result = restaurantService.getTopRatedRestaurants(10);

    assertEquals(List.of(fiveWithTwoRatingsDetail, fiveWithOneRatingDetail, fourStarDetail), result);
    assertEquals(2, result.get(0).getRatingCount());
    assertEquals(10, result.get(0).getRatingSum());
  }

  @Test
  @DisplayName("Nearby lunch picks: rejects invalid coordinates")
  public void testGetNearbyLunchPicks_whenCoordinatesInvalid_ThrowsInvalidRequestException() {
    assertThrows(InvalidRequestException.class, () -> restaurantService.getNearbyLunchPicks(91.0, -97.7431));
    verifyNoInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Today picks: returns existing daily picks in stored order")
  public void testGetTodaysLunchPicks_whenExistingPick_ReturnsStoredOrder() {
    var restaurant1 = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var restaurant2 = RestaurantStub.getRestaurantStub(RestaurantStub.ID_2);
    var detail1 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var detail2 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);
    var today = LocalDate.now().toString();
    var pick = DailyLunchPicks.builder()
        .id(today)
        .pickDate(today)
        .restaurantIds(List.of(RestaurantStub.ID_2, RestaurantStub.ID))
        .build();

    when(dailyLunchPicksRepository.findById(eq(today))).thenReturn(Optional.of(pick));
    when(restaurantRepository.findAllById(eq(List.of(RestaurantStub.ID_2, RestaurantStub.ID))))
        .thenReturn(List.of(restaurant1, restaurant2));
    when(restaurantMapper.toRestaurantDetail(eq(restaurant2))).thenReturn(detail2);
    when(restaurantMapper.toRestaurantDetail(eq(restaurant1))).thenReturn(detail1);

    var result = restaurantService.getTodaysLunchPicks();

    assertEquals(List.of(detail2, detail1), result);
    verify(dailyLunchPicksRepository).findById(eq(today));
    verify(restaurantRepository).findAllById(eq(List.of(RestaurantStub.ID_2, RestaurantStub.ID)));
    verify(restaurantMapper).toRestaurantDetail(eq(restaurant2));
    verify(restaurantMapper).toRestaurantDetail(eq(restaurant1));
  }

  @Test
  @DisplayName("Delete today's lunch pick: deletes restaurant and replaces it")
  public void testDeleteRestaurantFromTodaysLunchPicks_DeletesAndReplacesPick()
      throws Exception {
    var deleted = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var kept = RestaurantStub.getRestaurantStub(RestaurantStub.ID_2);
    var replacement = RestaurantStub.getRestaurantStub("replacement");
    var keptDetail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);
    var replacementDetail = RestaurantStub.getRestaurantDetailStub("replacement");
    var today = LocalDate.now().toString();
    var existingPick = DailyLunchPicks.builder()
        .id(today)
        .pickDate(today)
        .restaurantIds(List.of(RestaurantStub.ID, RestaurantStub.ID_2))
        .build();

    when(restaurantRepository.findById(eq(RestaurantStub.ID))).thenReturn(Optional.of(deleted));
    when(dailyLunchPicksRepository.findById(eq(today))).thenReturn(Optional.of(existingPick));
    when(restaurantRepository.findAllById(eq(List.of(RestaurantStub.ID_2)))).thenReturn(List.of(kept));
    when(restaurantRepository.findAll()).thenReturn(List.of(kept, replacement));
    when(dailyLunchPicksRepository.save(org.mockito.ArgumentMatchers.any(DailyLunchPicks.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(restaurantRepository.findAllById(eq(List.of(RestaurantStub.ID_2, "replacement"))))
        .thenReturn(List.of(kept, replacement));
    when(restaurantMapper.toRestaurantDetail(eq(kept))).thenReturn(keptDetail);
    when(restaurantMapper.toRestaurantDetail(eq(replacement))).thenReturn(replacementDetail);

    var result = restaurantService.deleteRestaurantFromTodaysLunchPicks(RestaurantStub.ID);

    assertEquals(List.of(keptDetail, replacementDetail), result);
    verify(restaurantRepository).delete(eq(deleted));
    verify(dailyLunchPicksRepository)
        .save(org.mockito.ArgumentMatchers.argThat(pick ->
            pick.getRestaurantIds().equals(List.of(RestaurantStub.ID_2, "replacement"))));
  }

  @Test
  @DisplayName("Refresh daily picks: selects up to three supported metro restaurants")
  public void testRefreshDailyLunchPicks_SelectsSupportedMetroRestaurants() {
    var austin = RestaurantStub.getRestaurantStub("austin");
    austin.getAddress().setCity("Austin");
    var pflugerville = RestaurantStub.getRestaurantStub("pflugerville");
    pflugerville.getAddress().setCity("Pflugerville");
    var roundRock = RestaurantStub.getRestaurantStub("round-rock");
    roundRock.getAddress().setCity("Round Rock");
    var miami = RestaurantStub.getRestaurantStub("miami");
    miami.getAddress().setCity("Miami");
    var oklahoma = RestaurantStub.getRestaurantStub("oklahoma");
    oklahoma.getAddress().setState("OK");

    when(restaurantRepository.findAll())
        .thenReturn(List.of(austin, pflugerville, roundRock, miami, oklahoma));
    when(dailyLunchPicksRepository.save(org.mockito.ArgumentMatchers.any(DailyLunchPicks.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var pick = restaurantService.refreshDailyLunchPicks(LocalDate.of(2026, 5, 17));

    assertEquals("2026-05-17", pick.getId());
    assertEquals(3, pick.getRestaurantIds().size());
    assertTrue(pick.getRestaurantIds().containsAll(List.of("austin", "pflugerville", "round-rock")));
    verify(restaurantRepository).findAll();
    verify(dailyLunchPicksRepository).save(org.mockito.ArgumentMatchers.any(DailyLunchPicks.class));
  }

  @Test
  @DisplayName("Refresh daily picks: includes fast-food restaurants without a ranking penalty")
  public void testRefreshDailyLunchPicks_IncludesFastFoodRestaurants() {
    var bistro = RestaurantStub.getRestaurantStub("bistro");
    bistro.setName("Austin Bistro");
    bistro.getAddress().setCity("Austin");
    var diner = RestaurantStub.getRestaurantStub("diner");
    diner.setName("Austin Diner");
    diner.getAddress().setCity("Austin");
    var fastFood = RestaurantStub.getRestaurantStub("fast-food");
    fastFood.setName("Taco Bell");
    fastFood.getAddress().setCity("Austin");
    fastFood.setSourceAmenity("fast_food");

    when(restaurantRepository.findAll()).thenReturn(List.of(fastFood, bistro, diner));
    when(dailyLunchPicksRepository.save(org.mockito.ArgumentMatchers.any(DailyLunchPicks.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var pick = restaurantService.refreshDailyLunchPicks(LocalDate.of(2026, 5, 17));

    assertEquals(3, pick.getRestaurantIds().size());
    assertTrue(pick.getRestaurantIds().containsAll(List.of("fast-food", "bistro", "diner")));
    verify(restaurantRepository).findAll();
    verify(dailyLunchPicksRepository).save(org.mockito.ArgumentMatchers.any(DailyLunchPicks.class));
  }

  @Test
  @DisplayName("OpenStreetMap import: saves new restaurants and skips existing")
  public void testImportConfiguredMetroRestaurantsFromOpenStreetMap_SavesNewAndSkipsExisting()
      throws Exception {
    var existing = RestaurantStub.getRestaurantStub("osm:node:1");
    existing.setNormalizedName("pflugerville taco house");
    var newRestaurant = RestaurantStub.getRestaurantStub("osm:node:2");
    var invalid = Restaurant.builder().id("osm:node:3").build();

    when(openStreetMapRestaurantClient.getConfiguredMetroRestaurants())
        .thenReturn(List.of(existing, newRestaurant, invalid));
    when(restaurantRepository.findById(eq("osm:node:1"))).thenReturn(Optional.of(existing));
    when(restaurantRepository.findById(eq("osm:node:2"))).thenReturn(Optional.empty());
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house")))
        .thenReturn(Optional.empty());
    when(restaurantRepository.findAll()).thenReturn(List.of());
    when(restaurantRepository.save(eq(newRestaurant))).thenReturn(newRestaurant);

    var result = restaurantService.importConfiguredMetroRestaurantsFromOpenStreetMap();

    assertEquals("openstreetmap", result.source());
    assertEquals(3, result.fetched());
    assertEquals(1, result.imported());
    assertEquals(0, result.updated());
    assertEquals(1, result.skippedExisting());
    assertEquals(1, result.skippedInvalid());
    verify(openStreetMapRestaurantClient).getConfiguredMetroRestaurants();
    verify(restaurantRepository).findById(eq("osm:node:1"));
    verify(restaurantRepository).findById(eq("osm:node:2"));
    verify(restaurantRepository).findByNormalizedName(eq("pflugerville taco house"));
    verify(restaurantRepository).findAll();
    verify(restaurantRepository).save(eq(newRestaurant));
  }

  @Test
  @DisplayName("OpenStreetMap import: updates existing same-name same-address restaurants")
  public void testImportConfiguredMetroRestaurantsFromOpenStreetMap_UpdatesSameNameSameAddress()
      throws Exception {
    var imported = RestaurantStub.getRestaurantStub("osm:node:9");
    imported.setPhoneNumber("512-555-9999");
    imported.setWebsite("https://updated.example.com");
    imported.setCuisine("tex-mex");
    imported.setSourceAmenity("restaurant");
    imported.getAddress().setPostalCode("78701");
    var existing = RestaurantStub.getRestaurantStub("manual-1");
    existing.setNormalizedName("pflugerville taco house");
    existing.setPhoneNumber(null);
    existing.setWebsite("https://old.example.com");
    existing.setCuisine(null);
    existing.setSourceAmenity(null);
    existing.getAddress().setPostalCode(null);

    when(openStreetMapRestaurantClient.getConfiguredMetroRestaurants()).thenReturn(List.of(imported));
    when(restaurantRepository.findById(eq("osm:node:9"))).thenReturn(Optional.empty());
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house")))
        .thenReturn(Optional.of(existing));
    when(restaurantRepository.save(eq(existing))).thenReturn(existing);

    var result = restaurantService.importConfiguredMetroRestaurantsFromOpenStreetMap();

    assertEquals(0, result.imported());
    assertEquals(1, result.updated());
    assertEquals(0, result.skippedExisting());
    assertEquals("512-555-9999", existing.getPhoneNumber());
    assertEquals("https://updated.example.com", existing.getWebsite());
    assertEquals("tex-mex", existing.getCuisine());
    assertEquals("restaurant", existing.getSourceAmenity());
    assertEquals("78701", existing.getAddress().getPostalCode());
    verify(restaurantRepository).save(eq(existing));
    verify(restaurantRepository, never()).save(eq(imported));
  }

  @Test
  @DisplayName("OpenStreetMap import: skips duplicate restaurant names with different addresses")
  public void testImportConfiguredMetroRestaurantsFromOpenStreetMap_SkipsDuplicateNamesWithDifferentAddress()
      throws Exception {
    var duplicate = RestaurantStub.getRestaurantStub("osm:node:9");
    var existing = RestaurantStub.getRestaurantStub("manual-1");
    existing.setNormalizedName("pflugerville taco house");
    existing.getAddress().setStreet1("200 Different St");

    when(openStreetMapRestaurantClient.getConfiguredMetroRestaurants()).thenReturn(List.of(duplicate));
    when(restaurantRepository.findById(eq("osm:node:9"))).thenReturn(Optional.empty());
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house")))
        .thenReturn(Optional.of(existing));

    var result = restaurantService.importConfiguredMetroRestaurantsFromOpenStreetMap();

    assertEquals(0, result.imported());
    assertEquals(0, result.updated());
    assertEquals(1, result.skippedExisting());
    verify(restaurantRepository, never()).save(eq(duplicate));
  }

  @Test
  @DisplayName("Monthly import: tracked run saves completed state")
  public void testRunTrackedOpenStreetMapImport_savesCompletedState() throws Exception {
    var started = Instant.parse("2026-05-15T08:00:00Z");
    var completed = Instant.parse("2026-05-15T08:00:03Z");
    var fixedClock = Clock.fixed(started, ZoneId.of("UTC"));

    when(clock.instant()).thenReturn(started, completed);
    when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);
    when(restaurantImportStateRepository.findById(eq("openstreetmap-monthly")))
        .thenReturn(Optional.empty(), Optional.empty());
    when(restaurantImportStateRepository.save(any(RestaurantImportState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(openStreetMapRestaurantClient.getConfiguredMetroRestaurants()).thenReturn(List.of());

    restaurantService.runTrackedOpenStreetMapImport("test");

    var captor = ArgumentCaptor.forClass(RestaurantImportState.class);
    verify(restaurantImportStateRepository, times(2)).save(captor.capture());
    var startedState = captor.getAllValues().get(0);
    var completedState = captor.getAllValues().get(1);
    assertEquals("openstreetmap-monthly", startedState.getId());
    assertEquals(started, startedState.getLastStartedOn());
    assertEquals("openstreetmap-monthly", completedState.getId());
    assertEquals(completed, completedState.getLastCompletedOn());
    assertEquals("2026-05", completedState.getLastCompletedMonth());
    assertNotNull(completedState.getLastResult());
    assertEquals(0, completedState.getLastResult().fetched());
  }

  @Test
  @DisplayName("Monthly import catch-up: skips when an import completed last month")
  public void testRunMissedMonthlyOpenStreetMapImport_whenLastMonthCompleted_skipsImport()
      throws Exception {
    var fixedClock = Clock.fixed(Instant.parse("2026-05-18T08:00:00Z"), ZoneId.of("UTC"));
    when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);
    when(restaurantImportStateRepository.findById(eq("openstreetmap-monthly")))
        .thenReturn(Optional.of(RestaurantImportState.builder()
            .id("openstreetmap-monthly")
            .lastCompletedMonth("2026-04")
            .build()));

    restaurantService.runMissedMonthlyOpenStreetMapImport();

    verify(restaurantImportStateRepository).findById(eq("openstreetmap-monthly"));
    verifyNoInteractions(openStreetMapRestaurantClient);
  }

  @Test
  @DisplayName("Monthly import catch-up: runs immediately when last month is missing")
  public void testRunMissedMonthlyOpenStreetMapImport_whenLastMonthMissing_runsImport()
      throws Exception {
    var started = Instant.parse("2026-05-18T08:00:00Z");
    var completed = Instant.parse("2026-05-18T08:00:03Z");
    var fixedClock = Clock.fixed(started, ZoneId.of("UTC"));

    when(clock.withZone(any(ZoneId.class))).thenReturn(fixedClock);
    when(clock.instant()).thenReturn(started, completed);
    when(restaurantImportStateRepository.findById(eq("openstreetmap-monthly")))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.empty());
    when(restaurantImportStateRepository.save(any(RestaurantImportState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(openStreetMapRestaurantClient.getConfiguredMetroRestaurants()).thenReturn(List.of());

    restaurantService.runMissedMonthlyOpenStreetMapImport();

    verify(openStreetMapRestaurantClient).getConfiguredMetroRestaurants();
    verify(restaurantImportStateRepository).save(argThat(state ->
        "2026-05".equals(state.getLastCompletedMonth())
            && state.getLastResult() != null));
  }

  @Test
  @DisplayName("Dedupe names: keeps a stable survivor and deletes duplicate names")
  public void testRemoveDuplicateNamedRestaurants_keepsStableSurvivorAndDeletesDuplicates() {
    var dallas = RestaurantStub.getRestaurantStub("b-dallas-id");
    dallas.getAddress().setCity("Dallas");
    var sanFrancisco = RestaurantStub.getRestaurantStub("a-san-francisco-id");
    sanFrancisco.getAddress().setCity("San Francisco");
    var newOrleans = RestaurantStub.getRestaurantStub("c-new-orleans-id");
    newOrleans.getAddress().setCity("New Orleans");
    var unique = RestaurantStub.getRestaurantStub("unique-id");
    unique.setName("Unique Lunch");

    when(restaurantRepository.findAll()).thenReturn(List.of(dallas, sanFrancisco, newOrleans, unique));
    when(restaurantRepository.save(eq(sanFrancisco))).thenReturn(sanFrancisco);

    var result = restaurantService.removeDuplicateNamedRestaurants();

    assertEquals(1, result.duplicateGroups());
    assertEquals(2, result.deleted());
    assertEquals(1, result.updatedSurvivors());
    assertEquals(List.of("a-san-francisco-id"), result.keptRestaurantIds());
    assertTrue(result.deletedRestaurantIds().containsAll(List.of("b-dallas-id", "c-new-orleans-id")));
    assertEquals("pflugerville taco house", sanFrancisco.getNormalizedName());
    verify(restaurantRepository).findAll();
    verify(restaurantRepository).deleteAll(eq(List.of(dallas, newOrleans)));
    verify(restaurantRepository).save(eq(sanFrancisco));
  }

  @Test
  @DisplayName("Dedupe names: does nothing when names are unique")
  public void testRemoveDuplicateNamedRestaurants_whenNoDuplicates_doesNothing() {
    var one = RestaurantStub.getRestaurantStub("one");
    one.setName("One Lunch");
    var two = RestaurantStub.getRestaurantStub("two");
    two.setName("Two Lunch");

    when(restaurantRepository.findAll()).thenReturn(List.of(one, two));

    var result = restaurantService.removeDuplicateNamedRestaurants();

    assertEquals(0, result.duplicateGroups());
    assertEquals(0, result.deleted());
    assertEquals(0, result.updatedSurvivors());
    verify(restaurantRepository).findAll();
    verifyNoMoreInteractions(restaurantRepository);
  }

  @Test
  @DisplayName("Throws InvalidRequestException when id is null")
  public void testGetRestaurantById_whenIdNull_ThrowsInvalidRequestException() {
    assertThrows(InvalidRequestException.class, () -> restaurantService.getRestaurantById(null));
    verifyNoInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Throws InvalidRequestException when id is blank")
  public void testGetRestaurantById_whenIdBlank_ThrowsInvalidRequestException() {
    assertThrows(InvalidRequestException.class, () -> restaurantService.getRestaurantById("   "));
    verifyNoInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Returns rated detail when entity is found")
  public void testGetRestaurantById_whenFound_ReturnsRestaurantDetail() throws Exception {
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var restaurantDetail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);


    when(restaurantRepository.findById(eq(RestaurantStub.ID))).thenReturn(Optional.of(restaurant));
    when(restaurantMapper.toRestaurantDetail(eq(restaurant))).thenReturn(restaurantDetail);

    var result = restaurantService.getRestaurantById(RestaurantStub.ID);

    assertSame(restaurantDetail, result);
    assertEquals(0, result.getRatingCount());
    assertEquals(0, result.getRatingSum());

    verify(restaurantRepository).findById(eq(RestaurantStub.ID));
    verify(restaurantMapper).toRestaurantDetail(eq(restaurant));
    verifyNoMoreInteractions(restaurantMapper, restaurantRepository);
  }

  @Test
  @DisplayName("Throws ResourceNotFoundException when entity is not found")
  public void testGetRestaurantById_whenNotFound_ThrowsResourceNotFoundException() {

    when(restaurantRepository.findById(eq(RestaurantStub.ID))).thenReturn(Optional.empty());

    var ex = assertThrows(ResourceNotFoundException.class, () -> restaurantService.getRestaurantById(RestaurantStub.ID));
    assertTrue(ex.getMessage().contains(RestaurantStub.ID));

    verify(restaurantRepository).findById(eq(RestaurantStub.ID));
    verifyNoMoreInteractions(restaurantMapper, restaurantRepository);
  }

  private Restaurant nearbyRestaurant(String id, String name, double latitude, double longitude) {
    var restaurant = RestaurantStub.getRestaurantStub(id);
    restaurant.setName(name);
    restaurant.getAddress().setLatitude(latitude);
    restaurant.getAddress().setLongitude(longitude);
    return restaurant;
  }
}
