package dev.christopherbell.whatsforlunch.restaurant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import dev.christopherbell.configuration.security.ControllerSliceSecurityTestConfig;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.libs.test.TestUtil;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeGroupPreview;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupePreview;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavoriteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantInventoryPage;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewCounts;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportPreviewResponse;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportWorkflowService;
import java.time.Instant;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteSetRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionRestaurantsRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionVoteRequest;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(RestaurantController.class)
@Import({ControllerExceptionHandler.class, ControllerSliceSecurityTestConfig.class})
public class RestaurantControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean(name = "permissionService") private PermissionService permissionService;
  @MockitoBean private RestaurantService restaurantService;
  @MockitoBean private RestaurantImportWorkflowService restaurantImportWorkflowService;
  @MockitoBean private WhatsForLunchSessionService whatsForLunchSessionService;

  @Test
  @DisplayName("Should return a bounded filtered restaurant inventory page for ADMIN.")
  @WithMockUser(authorities = {"ADMIN"})
  void getRestaurantInventory() throws Exception {
    var page = new RestaurantInventoryPage(
        List.of(RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID)), "next", 26);
    when(restaurantService.getRestaurantInventory("cafe", "Austin", "TX", null, 25))
        .thenReturn(page);

    mockMvc.perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260729 + "/inventory")
            .queryParam("name", "cafe")
            .queryParam("city", "Austin")
            .queryParam("state", "TX"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.items.length()").value(1))
        .andExpect(jsonPath("$.payload.nextCursor").value("next"))
        .andExpect(jsonPath("$.payload.total").value(26));
  }

  @Test
  @DisplayName("Should reject an unbounded restaurant inventory page size.")
  @WithMockUser(authorities = {"ADMIN"})
  void rejectsUnboundedRestaurantInventoryPageSize() throws Exception {
    when(restaurantService.getRestaurantInventory(null, null, null, null, 101))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "size"));

    mockMvc.perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260729 + "/inventory")
            .queryParam("size", "101"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should create a restaurant when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testCreateRestaurant() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-create-request.json");
    var requestObject =
        TestUtil.readJsonAsObject(
            "/request/restaurant-create-request.json", RestaurantCreateRequest.class);
    var response = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);

    when(restaurantService.createRestaurant(eq(requestObject))).thenReturn(response);

    mockMvc
        .perform(
            post("/api/whatsforlunch/restaurant" + APIVersion.V20250912)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isCreated())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isNotEmpty())
        .andExpect(jsonPath("$.payload.id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload.name").value(RestaurantStub.NAME))
        .andExpect(jsonPath("$.payload.address.street1").value(RestaurantStub.STREET_1))
        .andExpect(jsonPath("$.payload.address.city").value(RestaurantStub.CITY))
        .andExpect(jsonPath("$.payload.address.state").value(RestaurantStub.STATE))
        .andExpect(jsonPath("$.payload.address.country").value(RestaurantStub.COUNTRY))
        .andExpect(jsonPath("$.payload.address.postalCode").value(RestaurantStub.POSTAL_CODE))
        .andExpect(jsonPath("$.payload.phoneNumber").value(RestaurantStub.PHONE_NUMBER))
        .andExpect(jsonPath("$.payload.website").value(RestaurantStub.WEBSITE));

    verify(restaurantService).createRestaurant(eq(requestObject));
  }

  @Test
  @DisplayName("Should return 400 Bad Request when InvalidRequestException is thrown.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testCreateRestaurant_whenInvalidRequestExceptionIsThrown() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-create-request.json");
    var requestObject =
        TestUtil.readJsonAsObject(
            "/request/restaurant-create-request.json", RestaurantCreateRequest.class);
    when(restaurantService.createRestaurant(eq(requestObject)))
        .thenThrow(new InvalidRequestException("Bad Request"));

    mockMvc
        .perform(
            post("/api/whatsforlunch/restaurant" + APIVersion.V20250912)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isBadRequest());

    verify(restaurantService).createRestaurant(eq(requestObject));
  }

  @Test
  @DisplayName("Should return 401 Forbidden when user has no permissions.")
  public void testCreateRestaurant_whenUserHasNoPermissions() throws Exception {
    mockMvc
        .perform(
            post("/api/whatsforlunch/restaurant" + APIVersion.V20250912)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("testDeleteRestaurantById_whenAuthorizedAndValidId_Returns200WithPayload")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteRestaurantById_whenAuthorizedAndValidId_Returns200WithPayload() throws Exception {
    var deleted = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    when(restaurantService.deleteRestaurantById(eq(RestaurantStub.ID))).thenReturn(deleted);

    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20250913 + "/{id}", RestaurantStub.ID)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isNotEmpty())
        .andExpect(jsonPath("$.payload.id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload.name").value(RestaurantStub.NAME))
        .andExpect(jsonPath("$.payload.address.street1").value(RestaurantStub.STREET_1))
        .andExpect(jsonPath("$.payload.address.city").value(RestaurantStub.CITY))
        .andExpect(jsonPath("$.payload.address.state").value(RestaurantStub.STATE))
        .andExpect(jsonPath("$.payload.address.country").value(RestaurantStub.COUNTRY))
        .andExpect(jsonPath("$.payload.address.postalCode").value(RestaurantStub.POSTAL_CODE))
        .andExpect(jsonPath("$.payload.phoneNumber").value(RestaurantStub.PHONE_NUMBER))
        .andExpect(jsonPath("$.payload.website").value(RestaurantStub.WEBSITE));;

    verify(restaurantService).deleteRestaurantById(eq(RestaurantStub.ID));
  }

  @Test
  @DisplayName("testDeleteRestaurantById_whenNotAuthorized_Returns401")
  public void testDeleteRestaurantById_whenNotAuthorized_Returns401() throws Exception {
    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20250913 + "/{id}", RestaurantStub.ID)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("testDeleteRestaurantById_whenInvalidId_Returns400")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteRestaurantById_whenInvalidId_Returns400() throws Exception {
    when(restaurantService.deleteRestaurantById(eq(RestaurantStub.ID)))
        .thenThrow(new InvalidRequestException("Restaurant id cannot be null or blank."));

    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20250913 + "/{id}", RestaurantStub.ID)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isBadRequest());

    verify(restaurantService).deleteRestaurantById(eq(RestaurantStub.ID));
  }

  @Test
  @DisplayName("testDeleteRestaurantById_whenNotFound_Returns404")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteRestaurantById_whenNotFound_Returns404() throws Exception {
    when(restaurantService.deleteRestaurantById(eq(RestaurantStub.ID)))
        .thenThrow(new ResourceNotFoundException("Restaurant not found: " + RestaurantStub.ID));

    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20250913 + "/{id}", RestaurantStub.ID)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isNotFound());

    verify(restaurantService).deleteRestaurantById(eq(RestaurantStub.ID));
  }

  @Test
  @DisplayName("testDeleteRestaurantById_whenWrongContentType_Returns415")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteRestaurantById_whenWrongContentType_Returns415() throws Exception {
    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20250913 + "/{id}", RestaurantStub.ID)
            .contentType(MediaType.TEXT_PLAIN)
            .content("not-json")
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  @DisplayName("testDeleteRestaurantById_whenAcceptHeaderUnsupported_Returns406")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteRestaurantById_whenAcceptHeaderUnsupported_Returns406() throws Exception {
    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20250913 + "/{id}", RestaurantStub.ID)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_PLAIN)
            .with(csrf()))
        .andExpect(status().isNotAcceptable());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("Should get restaurant by id when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetAllRestaurantById() throws Exception {
    var restaurantDetail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    when(restaurantService.getRestaurantById(eq(RestaurantStub.ID)))
        .thenReturn(restaurantDetail);

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20250912 + "/" + RestaurantStub.ID)
                .content(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload.name").value(RestaurantStub.NAME))
        .andExpect(jsonPath("$.payload.address.street1").value(RestaurantStub.STREET_1))
        .andExpect(jsonPath("$.payload.address.city").value(RestaurantStub.CITY))
        .andExpect(jsonPath("$.payload.address.state").value(RestaurantStub.STATE))
        .andExpect(jsonPath("$.payload.address.country").value(RestaurantStub.COUNTRY))
        .andExpect(jsonPath("$.payload.address.postalCode").value(RestaurantStub.POSTAL_CODE))
        .andExpect(jsonPath("$.payload.phoneNumber").value(RestaurantStub.PHONE_NUMBER))
        .andExpect(jsonPath("$.payload.website").value(RestaurantStub.WEBSITE));

    verify(restaurantService).getRestaurantById(eq(RestaurantStub.ID));
  }

  @Test
  @DisplayName("Should get public restaurant profile by id without ADMIN role.")
  @WithMockUser
  public void testGetPublicRestaurantById_Returns200() throws Exception {
    var restaurantDetail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    restaurantDetail.setUpVotes(1);
    restaurantDetail.setDownVotes(0);
    restaurantDetail.setVoteCount(1);
    when(restaurantService.getRestaurantById(eq(RestaurantStub.ID)))
        .thenReturn(restaurantDetail);

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/profile/" + RestaurantStub.ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload.upVotes").value(1))
        .andExpect(jsonPath("$.payload.downVotes").value(0))
        .andExpect(jsonPath("$.payload.voteCount").value(1));

    verify(restaurantService).getRestaurantById(eq(RestaurantStub.ID));
  }

  @Test
  @DisplayName("Should throw ResourceNotFoundException when restaurant does not exist.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetAllRestaurantById_whenResourceNotFoundExceptionIsThrown() throws Exception {
    when(restaurantService.getRestaurantById(eq(RestaurantStub.ID))).thenThrow(new ResourceNotFoundException());

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20250912 + "/" + RestaurantStub.ID)
                .content(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false));

    verify(restaurantService).getRestaurantById(eq(RestaurantStub.ID));
  }

  @Test
  @DisplayName("Should return 401 Unauthorized when caller has no permissions.")
  public void testGetAllRestaurantById_whenCallerHasNoPermissions() throws Exception {
    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20250912 + "/" + RestaurantStub.ID)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("Should get all restaurants when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetAllRestaurants() throws Exception {
    var restaurant1 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var restaurant2 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);
    var restaurantDetails = List.of(restaurant1, restaurant2);
    when(restaurantService.getRestaurants()).thenReturn(restaurantDetails);

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20250912)
                .content(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isArray())
        .andExpect(jsonPath("$.payload[0].id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload[0].name").value(RestaurantStub.NAME))
        .andExpect(jsonPath("$.payload[0].address.street1").value(RestaurantStub.STREET_1))
        .andExpect(jsonPath("$.payload[0].address.city").value(RestaurantStub.CITY))
        .andExpect(jsonPath("$.payload[0].address.state").value(RestaurantStub.STATE))
        .andExpect(jsonPath("$.payload[0].address.country").value(RestaurantStub.COUNTRY))
        .andExpect(jsonPath("$.payload[0].address.postalCode").value(RestaurantStub.POSTAL_CODE))
        .andExpect(jsonPath("$.payload[0].phoneNumber").value(RestaurantStub.PHONE_NUMBER))
        .andExpect(jsonPath("$.payload[0].website").value(RestaurantStub.WEBSITE))
        .andExpect(jsonPath("$.payload[1].id").value(RestaurantStub.ID_2))
        .andExpect(jsonPath("$.payload[1].name").value(RestaurantStub.NAME))
        .andExpect(jsonPath("$.payload[1].address.street1").value(RestaurantStub.STREET_1))
        .andExpect(jsonPath("$.payload[1].address.city").value(RestaurantStub.CITY))
        .andExpect(jsonPath("$.payload[1].address.state").value(RestaurantStub.STATE))
        .andExpect(jsonPath("$.payload[1].address.country").value(RestaurantStub.COUNTRY))
        .andExpect(jsonPath("$.payload[1].address.postalCode").value(RestaurantStub.POSTAL_CODE))
        .andExpect(jsonPath("$.payload[1].phoneNumber").value(RestaurantStub.PHONE_NUMBER))
        .andExpect(jsonPath("$.payload[1].website").value(RestaurantStub.WEBSITE));

    verify(restaurantService).getRestaurants();
  }

  @Test
  @DisplayName("Should get all restaurants and return a response that is empty.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetAllRestaurants_whenResponseIsEmpty() throws Exception {
    when(restaurantService.getRestaurants()).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20250912)
                .content(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    verify(restaurantService).getRestaurants();
  }

  @Test
  @DisplayName("Should return 401 Unauthorized when caller has no permissions.")
  public void testGetAllRestaurants_whenCallerHasNoPermissions() throws Exception {
    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20250912)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("Should get today's lunch picks without ADMIN authority.")
  @WithMockUser
  public void testGetTodaysLunchPicks_Returns200() throws Exception {
    var restaurant1 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var restaurant2 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);
    when(restaurantService.getTodaysLunchPicks()).thenReturn(List.of(restaurant1, restaurant2));

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/today")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isArray())
        .andExpect(jsonPath("$.payload[0].id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload[1].id").value(RestaurantStub.ID_2));

    verify(restaurantService).getTodaysLunchPicks();
  }

  @Test
  @DisplayName("Should get nearby lunch picks without ADMIN authority.")
  @WithMockUser
  public void testGetNearbyLunchPicks_Returns200() throws Exception {
    var restaurant1 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    var restaurant2 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);
    when(restaurantService.getNearbyLunchPicks(eq(30.2672), eq(-97.7431), eq(10), eq(List.of("mexican")), eq(false)))
        .thenReturn(List.of(restaurant1, restaurant2));

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/nearby")
                .param("latitude", "30.2672")
                .param("longitude", "-97.7431")
                .param("radiusMiles", "10")
                .param("cuisine", "mexican")
                .param("useSavedPreferences", "false")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isArray())
        .andExpect(jsonPath("$.payload[0].id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload[1].id").value(RestaurantStub.ID_2));

    verify(restaurantService).getNearbyLunchPicks(eq(30.2672), eq(-97.7431), eq(10), eq(List.of("mexican")), eq(false));
  }

  @Test
  @DisplayName("Should get nearby lunch picks by ZIP code without ADMIN authority.")
  @WithMockUser
  public void testGetNearbyLunchPicksByZipCode_Returns200() throws Exception {
    var restaurant1 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    when(restaurantService.getNearbyLunchPicksByZipCode(eq("78701"), eq(10), eq(List.of("thai")), eq(false)))
        .thenReturn(List.of(restaurant1));

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/nearby/zip/{zipCode}", "78701")
                .param("radiusMiles", "10")
                .param("cuisine", "thai")
                .param("useSavedPreferences", "false")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value(RestaurantStub.ID));

    verify(restaurantService).getNearbyLunchPicksByZipCode(eq("78701"), eq(10), eq(List.of("thai")), eq(false));
  }

  @Test
  @DisplayName("Should get WFL preferences when caller has USER role.")
  @WithMockUser(authorities = {"USER"})
  public void testGetMyPreferences_whenUser_Returns200() throws Exception {
    when(restaurantService.getPreferencesForCurrentViewer())
        .thenReturn(WhatsForLunchPreferenceDetail.builder()
            .cuisines(List.of("mexican", "thai"))
            .radiusMiles(10)
            .build());

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/preferences")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.cuisines[0]").value("mexican"))
        .andExpect(jsonPath("$.payload.cuisines[1]").value("thai"))
        .andExpect(jsonPath("$.payload.radiusMiles").value(10));

    verify(restaurantService).getPreferencesForCurrentViewer();
  }

  @Test
  @DisplayName("Should save WFL preferences when caller has USER role.")
  @WithMockUser(authorities = {"USER"})
  public void testUpdateMyPreferences_whenUser_Returns200() throws Exception {
    var request = new WhatsForLunchPreferenceRequest(List.of("mexican", "thai"), 5);
    when(restaurantService.updateMyPreferences(eq(request)))
        .thenReturn(WhatsForLunchPreferenceDetail.builder()
            .cuisines(List.of("mexican", "thai"))
            .radiusMiles(5)
            .build());

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/preferences")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"cuisines\":[\"mexican\",\"thai\"],\"radiusMiles\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.cuisines[0]").value("mexican"))
        .andExpect(jsonPath("$.payload.cuisines[1]").value("thai"))
        .andExpect(jsonPath("$.payload.radiusMiles").value(5));

    verify(restaurantService).updateMyPreferences(eq(request));
  }

  @Test
  @DisplayName("Should reject WFL preference save without authentication.")
  public void testUpdateMyPreferences_whenUnauthenticated_Returns401() throws Exception {
    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/preferences")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"cuisines\":[\"mexican\"]}"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("Should vote for a restaurant when caller has USER role.")
  @WithMockUser(authorities = {"USER"})
  public void testVoteRestaurant_whenUser_ReturnsUpdatedVoteTotals() throws Exception {
    var request = new RestaurantVoteRequest("UP");
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setUpVotes(2);
    detail.setDownVotes(0);
    detail.setVoteCount(2);
    detail.setMyVote(RestaurantVoteValue.UP);
    when(restaurantService.voteRestaurant(eq(RestaurantStub.ID), eq(request))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/" + RestaurantStub.ID + "/vote")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"vote\":\"UP\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.upVotes").value(2))
        .andExpect(jsonPath("$.payload.downVotes").value(0))
        .andExpect(jsonPath("$.payload.voteCount").value(2))
        .andExpect(jsonPath("$.payload.myVote").value("UP"));

    verify(restaurantService).voteRestaurant(eq(RestaurantStub.ID), eq(request));
  }

  @Test
  @DisplayName("Should vote for a restaurant when caller has Spring ROLE_USER authority.")
  @WithMockUser(authorities = {"ROLE_USER"})
  public void testVoteRestaurant_whenSpringRoleUser_ReturnsUpdatedVoteTotals() throws Exception {
    var request = new RestaurantVoteRequest("DOWN");
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setUpVotes(0);
    detail.setDownVotes(1);
    detail.setVoteCount(1);
    detail.setMyVote(RestaurantVoteValue.DOWN);
    when(restaurantService.voteRestaurant(eq(RestaurantStub.ID), eq(request))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/" + RestaurantStub.ID + "/vote")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"vote\":\"DOWN\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.upVotes").value(0))
        .andExpect(jsonPath("$.payload.downVotes").value(1))
        .andExpect(jsonPath("$.payload.voteCount").value(1))
        .andExpect(jsonPath("$.payload.myVote").value("DOWN"));

    verify(restaurantService).voteRestaurant(eq(RestaurantStub.ID), eq(request));
  }

  @Test
  @DisplayName("Should vote on an OpenStreetMap restaurant through the body-based vote endpoint.")
  @WithMockUser(authorities = {"USER"})
  public void testVoteRestaurantWithBody_whenOpenStreetMapId_ReturnsUpdatedVoteTotals() throws Exception {
    var restaurantId = "osm:way:55591510";
    var request = new RestaurantVoteSetRequest(restaurantId, "UP");
    var serviceRequest = new RestaurantVoteRequest("UP");
    var detail = RestaurantStub.getRestaurantDetailStub(restaurantId);
    detail.setUpVotes(1);
    detail.setDownVotes(0);
    detail.setVoteCount(1);
    detail.setMyVote(RestaurantVoteValue.UP);
    when(restaurantService.voteRestaurant(eq(restaurantId), eq(serviceRequest))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/vote")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(restaurantId))
        .andExpect(jsonPath("$.payload.upVotes").value(1))
        .andExpect(jsonPath("$.payload.downVotes").value(0))
        .andExpect(jsonPath("$.payload.voteCount").value(1))
        .andExpect(jsonPath("$.payload.myVote").value("UP"));

    verify(restaurantService).voteRestaurant(eq(restaurantId), eq(serviceRequest));
  }

  @Test
  @DisplayName("Should reject restaurant votes without authentication.")
  public void testVoteRestaurant_whenUnauthenticated_Returns401() throws Exception {
    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/" + RestaurantStub.ID + "/vote")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"vote\":\"UP\"}"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @WithMockUser(authorities = {"USER"})
  void legacyRatingAndTopRatedRoutesAreNotMapped() throws Exception {
    mockMvc.perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/rating")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rating\":5}"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/top-rated"))
        .andExpect(status().isNotFound());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("Should list favorite restaurants when caller is authenticated.")
  @WithMockUser(authorities = {"USER"})
  public void testGetMyFavoriteRestaurants_whenUser_ReturnsFavorites() throws Exception {
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setMyFavorite(true);
    when(restaurantService.getMyFavoriteRestaurants()).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/favorites")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload[0].myFavorite").value(true));

    verify(restaurantService).getMyFavoriteRestaurants();
  }

  @Test
  @DisplayName("Should favorite a restaurant when caller is authenticated.")
  @WithMockUser(authorities = {"USER"})
  public void testFavoriteRestaurant_whenUser_ReturnsUpdatedRestaurant() throws Exception {
    var request = new RestaurantFavoriteRequest(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setMyFavorite(true);
    when(restaurantService.favoriteRestaurant(eq(request))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/favorite")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.myFavorite").value(true));

    verify(restaurantService).favoriteRestaurant(eq(request));
  }

  @Test
  @DisplayName("Should unfavorite a restaurant when caller is authenticated.")
  @WithMockUser(authorities = {"USER"})
  public void testUnfavoriteRestaurant_whenUser_ReturnsUpdatedRestaurant() throws Exception {
    var request = new RestaurantFavoriteRequest(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setMyFavorite(false);
    when(restaurantService.unfavoriteRestaurant(eq(request))).thenReturn(detail);

    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/favorite")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.myFavorite").value(false));

    verify(restaurantService).unfavoriteRestaurant(eq(request));
  }

  @Test
  @DisplayName("Should list top-liked restaurants.")
  @WithMockUser
  public void testGetTopLikedRestaurants_ReturnsLikedRestaurants() throws Exception {
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setUpVotes(2);
    detail.setDownVotes(0);
    detail.setVoteCount(2);
    when(restaurantService.getTopLikedRestaurants(null)).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/top-liked")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].upVotes").value(2))
        .andExpect(jsonPath("$.payload[0].downVotes").value(0))
        .andExpect(jsonPath("$.payload[0].voteCount").value(2));

    verify(restaurantService).getTopLikedRestaurants(null);
  }

  @Test
  @DisplayName("Should create a shared WFL session when caller has USER role.")
  @WithMockUser(authorities = {"USER"})
  public void testCreateSession_whenUser_Returns201() throws Exception {
    var request = new WhatsForLunchSessionCreateRequest(
        List.of(RestaurantStub.ID, RestaurantStub.ID_2, "restaurant-3"),
        List.of("friend"));
    var detail = WhatsForLunchSessionDetail.builder()
        .id("session-1")
        .createdByUsername("owner")
        .participantUsernames(List.of("owner", "friend"))
        .restaurants(List.of(RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID)))
        .build();
    when(whatsForLunchSessionService.createSession(eq(request))).thenReturn(detail);

    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/sessions")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("session-1"))
        .andExpect(jsonPath("$.payload.participantUsernames[1]").value("friend"));

    verify(whatsForLunchSessionService).createSession(eq(request));
  }

  @Test
  @DisplayName("Should join a shared WFL session from a link when caller has USER role.")
  @WithMockUser(authorities = {"USER"})
  public void testJoinSession_whenUser_Returns200() throws Exception {
    var detail = WhatsForLunchSessionDetail.builder()
        .id("session-1")
        .participantUsernames(List.of("owner", "friend"))
        .build();
    when(whatsForLunchSessionService.joinSession(eq("session-1"))).thenReturn(detail);

    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/sessions/session-1/join")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("session-1"))
        .andExpect(jsonPath("$.payload.participantUsernames[1]").value("friend"));

    verify(whatsForLunchSessionService).joinSession(eq("session-1"));
  }

  @Test
  @DisplayName("Should vote in a shared WFL session when caller has USER role.")
  @WithMockUser(authorities = {"USER"})
  public void testVoteInSession_whenUser_Returns200() throws Exception {
    var request = new WhatsForLunchSessionVoteRequest(RestaurantStub.ID);
    var detail = WhatsForLunchSessionDetail.builder()
        .id("session-1")
        .myVoteRestaurantId(RestaurantStub.ID)
        .build();
    when(whatsForLunchSessionService.vote(eq("session-1"), eq(request))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/sessions/session-1/vote")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.myVoteRestaurantId").value(RestaurantStub.ID));

    verify(whatsForLunchSessionService).vote(eq("session-1"), eq(request));
  }

  @Test
  @DisplayName("Should replace restaurants in a shared WFL session when caller has USER role.")
  @WithMockUser(authorities = {"USER"})
  public void testUpdateSessionRestaurants_whenUser_Returns200() throws Exception {
    var request = new WhatsForLunchSessionRestaurantsRequest(
        List.of(RestaurantStub.ID, RestaurantStub.ID_2, "restaurant-3"));
    var detail = WhatsForLunchSessionDetail.builder()
        .id("session-1")
        .restaurants(List.of(RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID)))
        .build();
    when(whatsForLunchSessionService.updateRestaurants(eq("session-1"), eq(request))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/sessions/session-1/restaurants")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("session-1"))
        .andExpect(jsonPath("$.payload.restaurants[0].id").value(RestaurantStub.ID));

    verify(whatsForLunchSessionService).updateRestaurants(eq("session-1"), eq(request));
  }

  @Test
  @DisplayName("Should delete today's lunch pick and return replacement list when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteRestaurantFromTodaysLunchPicks_whenAdmin_ReturnsUpdatedPicks()
      throws Exception {
    var restaurant1 = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID_2);
    var restaurant2 = RestaurantStub.getRestaurantDetailStub("replacement");
    when(restaurantService.deleteRestaurantFromTodaysLunchPicks(eq(RestaurantStub.ID)))
        .thenReturn(List.of(restaurant1, restaurant2));

    mockMvc
        .perform(
            delete("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/today/" + RestaurantStub.ID)
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isArray())
        .andExpect(jsonPath("$.payload[0].id").value(RestaurantStub.ID_2))
        .andExpect(jsonPath("$.payload[1].id").value("replacement"));

    verify(restaurantService).deleteRestaurantFromTodaysLunchPicks(eq(RestaurantStub.ID));
  }

  @Test
  @DisplayName("Should preview OpenStreetMap restaurants when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testImportOpenStreetMapRestaurants_whenAdmin_Returns200() throws Exception {
    var result = new RestaurantImportPreviewResponse(
        "token-1",
        "checksum-1",
        Instant.parse("2026-07-26T12:15:00Z"),
        new RestaurantImportPreviewCounts(10, 7, 2, 0, 0, 1),
        List.of("CREATE: Cafe"));
    when(restaurantImportWorkflowService.previewOpenStreetMapImport()).thenReturn(result);

    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260726 + "/import/openstreetmap/preview")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.token").value("token-1"))
        .andExpect(jsonPath("$.payload.checksum").value("checksum-1"))
        .andExpect(jsonPath("$.payload.counts.fetched").value(10))
        .andExpect(jsonPath("$.payload.counts.created").value(7))
        .andExpect(jsonPath("$.payload.counts.updated").value(2));

    verify(restaurantImportWorkflowService).previewOpenStreetMapImport();
  }

  @Test
  @DisplayName("Should reject OpenStreetMap import without authentication.")
  public void testImportOpenStreetMapRestaurants_whenUnauthenticated_Returns401() throws Exception {
    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260726 + "/import/openstreetmap/preview")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantImportWorkflowService);
  }

  @Test
  @DisplayName("Should preview duplicate restaurant names when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testRemoveDuplicateNamedRestaurants_whenAdmin_Returns200() throws Exception {
    var result = new RestaurantDedupePreview(List.of(new RestaurantDedupeGroupPreview(
        "lunch spot", "version-1", "austin-id", List.of("austin-id", "pflugerville-id"), List.of())));
    when(restaurantService.previewDuplicateNamedRestaurants()).thenReturn(result);

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260726 + "/dedupe-names/preview")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.groups[0].version").value("version-1"))
        .andExpect(jsonPath("$.payload.groups[0].survivorId").value("austin-id"));

    verify(restaurantService).previewDuplicateNamedRestaurants();
  }

  @Test
  @DisplayName("testUpdateRestaurantById_whenAuthorizedAndValidRequest_Returns202WithPayload")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateRestaurantById_whenAuthorizedAndValidRequest_Returns202WithPayload() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-update-request.json");
    var requestObject = RestaurantStub.getRestaurantUpdateRequestStub();
    var updated = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);

    when(restaurantService.updateRestaurant(eq(requestObject))).thenReturn(updated);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20250913)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf())
            .content(request))
        .andExpect(status().isAccepted())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isNotEmpty())
        .andExpect(jsonPath("$.payload.id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload.name").value(RestaurantStub.NAME))
        .andExpect(jsonPath("$.payload.address.street1").value(RestaurantStub.STREET_1))
        .andExpect(jsonPath("$.payload.address.city").value(RestaurantStub.CITY))
        .andExpect(jsonPath("$.payload.address.state").value(RestaurantStub.STATE))
        .andExpect(jsonPath("$.payload.address.country").value(RestaurantStub.COUNTRY))
        .andExpect(jsonPath("$.payload.address.postalCode").value(RestaurantStub.POSTAL_CODE))
        .andExpect(jsonPath("$.payload.phoneNumber").value(RestaurantStub.PHONE_NUMBER))
        .andExpect(jsonPath("$.payload.website").value(RestaurantStub.WEBSITE));

    verify(restaurantService).updateRestaurant(eq(requestObject));
  }

  @Test
  @DisplayName("testUpdateRestaurantById_whenNotAuthorized_Returns401")
  public void testUpdateRestaurantById_whenNotAuthorized_Returns401() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-update-request.json");

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20250913)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf())
            .content(request))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("testUpdateRestaurantById_whenInvalidRequest_Returns400")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateRestaurantById_whenInvalidRequest_Returns400() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-update-request.json");
    var requestObject = RestaurantStub.getRestaurantUpdateRequestStub();

    when(restaurantService.updateRestaurant(eq(requestObject)))
        .thenThrow(new InvalidRequestException("Restaurant id cannot be null or blank."));

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20250913)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf())
            .content(request))
        .andExpect(status().isBadRequest());

    verify(restaurantService).updateRestaurant(eq(requestObject));
  }

  @Test
  @DisplayName("testUpdateRestaurantById_whenNotFound_Returns404")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateRestaurantById_whenNotFound_Returns404() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-update-request.json");
    var requestObject = RestaurantStub.getRestaurantUpdateRequestStub();

    when(restaurantService.updateRestaurant(eq(requestObject)))
        .thenThrow(new ResourceNotFoundException("Restaurant not found: missing-id"));

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20250913)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf())
            .content(request))
        .andExpect(status().isNotFound());

    verify(restaurantService).updateRestaurant(eq(requestObject));
  }

  @Test
  @DisplayName("testUpdateRestaurantById_whenWrongContentType_Returns415")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateRestaurantById_whenWrongContentType_Returns415() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-update-request.json");

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20250913)
            .contentType(MediaType.TEXT_PLAIN)
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf())
            .content(request))
        .andExpect(status().isUnsupportedMediaType());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("testUpdateRestaurantById_whenAcceptHeaderUnsupported_Returns406")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateRestaurantById_whenAcceptHeaderUnsupported_Returns406() throws Exception {
    var request = TestUtil.readJsonAsString("/request/restaurant-update-request.json");

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20250913)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_PLAIN)
            .with(csrf())
            .content(request))
        .andExpect(status().isNotAcceptable());

    verifyNoInteractions(restaurantService);
  }
}
