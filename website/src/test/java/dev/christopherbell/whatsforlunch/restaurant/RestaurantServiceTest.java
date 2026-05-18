package dev.christopherbell.whatsforlunch.restaurant;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.whatsforlunch.restaurant.model.DailyLunchPicks;
import dev.christopherbell.whatsforlunch.restaurant.model.Restaurant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDetail;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RestaurantService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestaurantService unit tests")
public class RestaurantServiceTest {
  @Mock private DailyLunchPicksRepository dailyLunchPicksRepository;
  @Mock private OpenStreetMapRestaurantClient openStreetMapRestaurantClient;
  @Mock private RestaurantMapper restaurantMapper;
  @Mock private RestaurantRepository restaurantRepository;
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
  @DisplayName("Refresh daily picks: selects up to three Austin metro Texas restaurants")
  public void testRefreshDailyLunchPicks_SelectsAustinMetroRestaurants() {
    var austin = RestaurantStub.getRestaurantStub("austin");
    austin.getAddress().setCity("Austin");
    var pflugerville = RestaurantStub.getRestaurantStub("pflugerville");
    pflugerville.getAddress().setCity("Pflugerville");
    var roundRock = RestaurantStub.getRestaurantStub("round-rock");
    roundRock.getAddress().setCity("Round Rock");
    var dallas = RestaurantStub.getRestaurantStub("dallas");
    dallas.getAddress().setCity("Dallas");
    var oklahoma = RestaurantStub.getRestaurantStub("oklahoma");
    oklahoma.getAddress().setState("OK");

    when(restaurantRepository.findAll())
        .thenReturn(List.of(austin, pflugerville, roundRock, dallas, oklahoma));
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
  @DisplayName("Refresh daily picks: prefers non-fast-food restaurants")
  public void testRefreshDailyLunchPicks_PrefersNonFastFoodRestaurants() {
    var bistro = RestaurantStub.getRestaurantStub("bistro");
    bistro.setName("Austin Bistro");
    bistro.getAddress().setCity("Austin");
    var diner = RestaurantStub.getRestaurantStub("diner");
    diner.setName("Austin Diner");
    diner.getAddress().setCity("Austin");
    var tacos = RestaurantStub.getRestaurantStub("tacos");
    tacos.setName("Austin Tacos");
    tacos.getAddress().setCity("Austin");
    var fastFood = RestaurantStub.getRestaurantStub("fast-food");
    fastFood.setName("Taco Bell");
    fastFood.getAddress().setCity("Austin");
    fastFood.setSourceAmenity("fast_food");

    when(restaurantRepository.findAll()).thenReturn(List.of(fastFood, bistro, diner, tacos));
    when(dailyLunchPicksRepository.save(org.mockito.ArgumentMatchers.any(DailyLunchPicks.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var pick = restaurantService.refreshDailyLunchPicks(LocalDate.of(2026, 5, 17));

    assertEquals(3, pick.getRestaurantIds().size());
    assertTrue(pick.getRestaurantIds().containsAll(List.of("bistro", "diner", "tacos")));
    verify(restaurantRepository).findAll();
    verify(dailyLunchPicksRepository).save(org.mockito.ArgumentMatchers.any(DailyLunchPicks.class));
  }

  @Test
  @DisplayName("OpenStreetMap import: saves new restaurants and skips existing")
  public void testImportAustinMetroRestaurantsFromOpenStreetMap_SavesNewAndSkipsExisting()
      throws Exception {
    var existing = RestaurantStub.getRestaurantStub("osm:node:1");
    existing.setNormalizedName("pflugerville taco house");
    var newRestaurant = RestaurantStub.getRestaurantStub("osm:node:2");
    var invalid = Restaurant.builder().id("osm:node:3").build();

    when(openStreetMapRestaurantClient.getAustinMetroRestaurants())
        .thenReturn(List.of(existing, newRestaurant, invalid));
    when(restaurantRepository.findById(eq("osm:node:1"))).thenReturn(Optional.of(existing));
    when(restaurantRepository.findById(eq("osm:node:2"))).thenReturn(Optional.empty());
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house")))
        .thenReturn(Optional.empty());
    when(restaurantRepository.findAll()).thenReturn(List.of());
    when(restaurantRepository.save(eq(newRestaurant))).thenReturn(newRestaurant);

    var result = restaurantService.importAustinMetroRestaurantsFromOpenStreetMap();

    assertEquals("openstreetmap", result.source());
    assertEquals(3, result.fetched());
    assertEquals(1, result.imported());
    assertEquals(0, result.updated());
    assertEquals(1, result.skippedExisting());
    assertEquals(1, result.skippedInvalid());
    verify(openStreetMapRestaurantClient).getAustinMetroRestaurants();
    verify(restaurantRepository).findById(eq("osm:node:1"));
    verify(restaurantRepository).findById(eq("osm:node:2"));
    verify(restaurantRepository).findByNormalizedName(eq("pflugerville taco house"));
    verify(restaurantRepository).findAll();
    verify(restaurantRepository).save(eq(newRestaurant));
  }

  @Test
  @DisplayName("OpenStreetMap import: updates existing same-name same-address restaurants")
  public void testImportAustinMetroRestaurantsFromOpenStreetMap_UpdatesSameNameSameAddress()
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

    when(openStreetMapRestaurantClient.getAustinMetroRestaurants()).thenReturn(List.of(imported));
    when(restaurantRepository.findById(eq("osm:node:9"))).thenReturn(Optional.empty());
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house")))
        .thenReturn(Optional.of(existing));
    when(restaurantRepository.save(eq(existing))).thenReturn(existing);

    var result = restaurantService.importAustinMetroRestaurantsFromOpenStreetMap();

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
  public void testImportAustinMetroRestaurantsFromOpenStreetMap_SkipsDuplicateNamesWithDifferentAddress()
      throws Exception {
    var duplicate = RestaurantStub.getRestaurantStub("osm:node:9");
    var existing = RestaurantStub.getRestaurantStub("manual-1");
    existing.setNormalizedName("pflugerville taco house");
    existing.getAddress().setStreet1("200 Different St");

    when(openStreetMapRestaurantClient.getAustinMetroRestaurants()).thenReturn(List.of(duplicate));
    when(restaurantRepository.findById(eq("osm:node:9"))).thenReturn(Optional.empty());
    when(restaurantRepository.findByNormalizedName(eq("pflugerville taco house")))
        .thenReturn(Optional.of(existing));

    var result = restaurantService.importAustinMetroRestaurantsFromOpenStreetMap();

    assertEquals(0, result.imported());
    assertEquals(0, result.updated());
    assertEquals(1, result.skippedExisting());
    verify(restaurantRepository, never()).save(eq(duplicate));
  }

  @Test
  @DisplayName("Dedupe names: keeps Austin restaurant and deletes non-Austin duplicates")
  public void testRemoveDuplicateNamedRestaurants_keepsAustinAndDeletesDuplicates() {
    var austin = RestaurantStub.getRestaurantStub("austin-id");
    austin.getAddress().setCity("Austin");
    var pflugerville = RestaurantStub.getRestaurantStub("pflugerville-id");
    pflugerville.getAddress().setCity("Pflugerville");
    var cedarPark = RestaurantStub.getRestaurantStub("cedar-park-id");
    cedarPark.getAddress().setCity("Cedar Park");
    var unique = RestaurantStub.getRestaurantStub("unique-id");
    unique.setName("Unique Lunch");

    when(restaurantRepository.findAll()).thenReturn(List.of(pflugerville, austin, cedarPark, unique));
    when(restaurantRepository.save(eq(austin))).thenReturn(austin);

    var result = restaurantService.removeDuplicateNamedRestaurants();

    assertEquals(1, result.duplicateGroups());
    assertEquals(2, result.deleted());
    assertEquals(1, result.updatedSurvivors());
    assertEquals(List.of("austin-id"), result.keptRestaurantIds());
    assertTrue(result.deletedRestaurantIds().containsAll(List.of("pflugerville-id", "cedar-park-id")));
    assertEquals("pflugerville taco house", austin.getNormalizedName());
    verify(restaurantRepository).findAll();
    verify(restaurantRepository).deleteAll(eq(List.of(pflugerville, cedarPark)));
    verify(restaurantRepository).save(eq(austin));
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
  @DisplayName("Returns mapped detail when entity is found")
  public void testGetRestaurantById_whenFound_ReturnsRestaurantDetail() throws Exception {
    var restaurant = RestaurantStub.getRestaurantStub(RestaurantStub.ID);
    var restaurantDetail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);


    when(restaurantRepository.findById(eq(RestaurantStub.ID))).thenReturn(Optional.of(restaurant));
    when(restaurantMapper.toRestaurantDetail(eq(restaurant))).thenReturn(restaurantDetail);

    var result = restaurantService.getRestaurantById(RestaurantStub.ID);

    assertSame(restaurantDetail, result);

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
