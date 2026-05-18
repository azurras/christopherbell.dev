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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.libs.test.TestUtil;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantDedupeResult;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantImportResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RestaurantController.class)
@Import(ControllerExceptionHandler.class)
public class RestaurantControllerTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private RestaurantService restaurantService;

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
            .accept(MediaType.APPLICATION_JSON)
            .with(csrf()))
        .andExpect(status().isUnsupportedMediaType());

    verifyNoInteractions(restaurantService);
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
    when(restaurantService.getNearbyLunchPicks(eq(30.2672), eq(-97.7431)))
        .thenReturn(List.of(restaurant1, restaurant2));

    mockMvc
        .perform(
            get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/nearby")
                .param("latitude", "30.2672")
                .param("longitude", "-97.7431")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isArray())
        .andExpect(jsonPath("$.payload[0].id").value(RestaurantStub.ID))
        .andExpect(jsonPath("$.payload[1].id").value(RestaurantStub.ID_2));

    verify(restaurantService).getNearbyLunchPicks(eq(30.2672), eq(-97.7431));
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
  @DisplayName("Should import OpenStreetMap restaurants when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testImportOpenStreetMapRestaurants_whenAdmin_Returns200() throws Exception {
    var result = RestaurantImportResult.builder()
        .source("openstreetmap")
        .fetched(10)
        .imported(7)
        .updated(2)
        .skippedExisting(2)
        .skippedInvalid(1)
        .build();
    when(restaurantService.importAustinMetroRestaurantsFromOpenStreetMap()).thenReturn(result);

    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/import/openstreetmap")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.source").value("openstreetmap"))
        .andExpect(jsonPath("$.payload.fetched").value(10))
        .andExpect(jsonPath("$.payload.imported").value(7))
        .andExpect(jsonPath("$.payload.updated").value(2))
        .andExpect(jsonPath("$.payload.skippedExisting").value(2))
        .andExpect(jsonPath("$.payload.skippedInvalid").value(1));

    verify(restaurantService).importAustinMetroRestaurantsFromOpenStreetMap();
  }

  @Test
  @DisplayName("Should reject OpenStreetMap import without authentication.")
  public void testImportOpenStreetMapRestaurants_whenUnauthenticated_Returns401() throws Exception {
    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/import/openstreetmap")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(restaurantService);
  }

  @Test
  @DisplayName("Should dedupe restaurant names when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testRemoveDuplicateNamedRestaurants_whenAdmin_Returns200() throws Exception {
    var result = RestaurantDedupeResult.builder()
        .duplicateGroups(1)
        .deleted(2)
        .updatedSurvivors(1)
        .keptRestaurantIds(List.of("austin-id"))
        .deletedRestaurantIds(List.of("pflugerville-id", "cedar-park-id"))
        .build();
    when(restaurantService.removeDuplicateNamedRestaurants()).thenReturn(result);

    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/dedupe-names")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.duplicateGroups").value(1))
        .andExpect(jsonPath("$.payload.deleted").value(2))
        .andExpect(jsonPath("$.payload.updatedSurvivors").value(1))
        .andExpect(jsonPath("$.payload.keptRestaurantIds[0]").value("austin-id"))
        .andExpect(jsonPath("$.payload.deletedRestaurantIds[0]").value("pflugerville-id"));

    verify(restaurantService).removeDuplicateNamedRestaurants();
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
