package dev.christopherbell.whatsforlunch.restaurant;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.SecurityConfig;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavoriteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRatingRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantRatingSetRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionDetail;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RestaurantController.class)
@Import({ControllerExceptionHandler.class, SecurityConfig.class})
class RestaurantControllerMemberSecurityTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean(name = "permissionService") private PermissionService permissionService;
  @MockitoBean private RestaurantService restaurantService;
  @MockitoBean private WhatsForLunchSessionService whatsForLunchSessionService;

  @Test
  @DisplayName("Member token can get WFL filters")
  void getMyPreferences_whenBearerTokenHasUserAuthority_returns200() throws Exception {
    when(restaurantService.getPreferencesForCurrentViewer())
        .thenReturn(WhatsForLunchPreferenceDetail.builder()
            .cuisines(List.of("mexican"))
            .radiusMiles(5)
            .build());

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/preferences")
            .header("Authorization", bearer(Role.USER))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.radiusMiles").value(5));

    verify(restaurantService).getPreferencesForCurrentViewer();
  }

  @Test
  @DisplayName("Anonymous request can get default WFL filters")
  void getMyPreferences_whenAnonymous_returns200() throws Exception {
    when(restaurantService.getPreferencesForCurrentViewer())
        .thenReturn(WhatsForLunchPreferenceDetail.builder()
            .cuisines(List.of())
            .radiusMiles(15)
            .build());

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/preferences")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.radiusMiles").value(15));

    verify(restaurantService).getPreferencesForCurrentViewer();
  }

  @Test
  @DisplayName("Member token can save WFL filters")
  void updateMyPreferences_whenBearerTokenHasUserAuthority_returns200() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var request = new WhatsForLunchPreferenceRequest(List.of("mexican"), 5);
    when(restaurantService.updateMyPreferences(eq(request)))
        .thenReturn(WhatsForLunchPreferenceDetail.builder()
            .cuisines(List.of("mexican"))
            .radiusMiles(5)
            .build());

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/preferences")
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.radiusMiles").value(5));

    verify(restaurantService).updateMyPreferences(eq(request));
  }

  @Test
  @DisplayName("Member token can create a WFL share session")
  void createSession_whenBearerTokenHasUserAuthority_returns201() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var request = new WhatsForLunchSessionCreateRequest(
        List.of(RestaurantStub.ID, RestaurantStub.ID_2, "restaurant-3"),
        List.of());
    when(whatsForLunchSessionService.createSession(eq(request)))
        .thenReturn(WhatsForLunchSessionDetail.builder()
            .id("session-1")
            .createdByUsername("owner")
            .participantUsernames(List.of("owner"))
            .restaurants(List.of(RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID)))
            .build());

    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/sessions")
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("session-1"));

    verify(whatsForLunchSessionService).createSession(eq(request));
  }

  @Test
  @DisplayName("Member token can join a WFL shared link session")
  void joinSession_whenBearerTokenHasUserAuthority_returns200() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    when(whatsForLunchSessionService.joinSession(eq("session-1")))
        .thenReturn(WhatsForLunchSessionDetail.builder()
            .id("session-1")
            .participantUsernames(List.of("owner", "friend"))
            .build());

    mockMvc
        .perform(post("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/sessions/session-1/join")
            .header("Authorization", bearer(Role.USER))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("session-1"));

    verify(whatsForLunchSessionService).joinSession(eq("session-1"));
  }

  @Test
  @DisplayName("Member token can rate an OpenStreetMap restaurant through body-based endpoint")
  void rateRestaurant_whenBearerTokenAndOpenStreetMapId_returns200() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var restaurantId = "osm:way:55591510";
    var request = new RestaurantRatingSetRequest(restaurantId, 3);
    var serviceRequest = new RestaurantRatingRequest(3);
    var detail = RestaurantStub.getRestaurantDetailStub(restaurantId);
    detail.setRatingSum(3);
    detail.setRatingCount(1);
    detail.setMyRating(3);
    when(restaurantService.rateRestaurant(eq(restaurantId), eq(serviceRequest))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/rating")
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(restaurantId))
        .andExpect(jsonPath("$.payload.myRating").value(3));

    verify(restaurantService).rateRestaurant(eq(restaurantId), eq(serviceRequest));
  }

  @Test
  @DisplayName("Anonymous request can read top-rated WFL restaurants")
  void getTopRatedRestaurants_whenAnonymous_returns200() throws Exception {
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setRatingSum(5);
    detail.setRatingCount(1);
    when(restaurantService.getTopRatedRestaurants(eq(10))).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/top-rated")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].ratingCount").value(1));

    verify(restaurantService).getTopRatedRestaurants(eq(10));
  }

  @Test
  @DisplayName("Member token can list favorite WFL restaurants")
  void getMyFavoriteRestaurants_whenBearerTokenHasUserAuthority_returns200() throws Exception {
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setMyFavorite(true);
    when(restaurantService.getMyFavoriteRestaurants()).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/favorites")
            .header("Authorization", bearer(Role.USER))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].myFavorite").value(true));

    verify(restaurantService).getMyFavoriteRestaurants();
  }

  @Test
  @DisplayName("Member token can favorite a WFL restaurant")
  void favoriteRestaurant_whenBearerTokenHasUserAuthority_returns200() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var request = new RestaurantFavoriteRequest(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setMyFavorite(true);
    when(restaurantService.favoriteRestaurant(eq(request))).thenReturn(detail);

    mockMvc
        .perform(put("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/favorite")
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.myFavorite").value(true));

    verify(restaurantService).favoriteRestaurant(eq(request));
  }

  @Test
  @DisplayName("Member token can unfavorite a WFL restaurant")
  void unfavoriteRestaurant_whenBearerTokenHasUserAuthority_returns200() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var request = new RestaurantFavoriteRequest(RestaurantStub.ID);
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setMyFavorite(false);
    when(restaurantService.unfavoriteRestaurant(eq(request))).thenReturn(detail);

    mockMvc
        .perform(delete("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/favorite")
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.myFavorite").value(false));

    verify(restaurantService).unfavoriteRestaurant(eq(request));
  }

  private String bearer(Role role) {
    return "Bearer " + PermissionService.generateToken(Account.builder()
        .id("account-1")
        .role(role)
        .build());
  }
}
