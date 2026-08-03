package dev.christopherbell.whatsforlunch.restaurant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.AccountRepository;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.security.ControllerSliceMethodSecurityTestConfig;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantFavoriteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceDetail;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchPreferenceRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteSetRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.RestaurantVoteValue;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionCreateRequest;
import dev.christopherbell.whatsforlunch.restaurant.model.WhatsForLunchSessionDetail;
import dev.christopherbell.whatsforlunch.restaurant.session.WhatsForLunchSessionService;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantImportWorkflowService;
import dev.christopherbell.whatsforlunch.restaurant.importing.RestaurantDataFreshness;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RestaurantController.class)
@Import({ControllerExceptionHandler.class, ControllerSliceMethodSecurityTestConfig.class})
class RestaurantControllerMemberSecurityTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean(name = "permissionService") private PermissionService permissionService;
  @MockitoBean private AccountRepository accountRepository;
  @MockitoBean private RestaurantService restaurantService;
  @MockitoBean private RestaurantImportWorkflowService restaurantImportWorkflowService;
  @MockitoBean private WhatsForLunchSessionService whatsForLunchSessionService;

  @Test
  @DisplayName("Anonymous request can read WFL data freshness")
  void getFreshness_whenAnonymous_returns200() throws Exception {
    when(restaurantImportWorkflowService.getPublicFreshness())
        .thenReturn(new RestaurantDataFreshness(
            "OpenStreetMap", null, false, 45, List.of("Austin, TX")));

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260726 + "/freshness")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload.source").value("OpenStreetMap"))
        .andExpect(jsonPath("$.payload.cityCoverage[0]").value("Austin, TX"));
  }

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
            .with(csrf())
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
            .with(csrf())
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
            .with(csrf())
            .header("Authorization", bearer(Role.USER))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("session-1"));

    verify(whatsForLunchSessionService).joinSession(eq("session-1"));
  }

  @Test
  @DisplayName("Member token can vote on an OpenStreetMap restaurant through the body-based endpoint")
  void voteRestaurant_whenBearerTokenAndOpenStreetMapId_returns200() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
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
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value(restaurantId))
        .andExpect(jsonPath("$.payload.myVote").value("UP"));

    verify(restaurantService).voteRestaurant(eq(restaurantId), eq(serviceRequest));
  }

  @Test
  @DisplayName("Path vote rejects a legacy rating property even when vote is valid")
  void voteRestaurantByPath_whenLegacyRatingIsPresent_returns400() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var endpoint = "/api/whatsforlunch/restaurant" + APIVersion.V20260517
        + "/" + RestaurantStub.ID + "/vote";

    for (var body : List.of(
        "{\"vote\":\"UP\",\"rating\":1}",
        "{\"vote\":\"UP\",\"rating\":null}")) {
      mockMvc
          .perform(put(endpoint)
              .with(csrf())
              .header("Authorization", bearer(Role.USER))
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.messages[0].code").value("REQUEST_ERROR"));
    }

    verify(restaurantService, never()).voteRestaurant(eq(RestaurantStub.ID), any());
  }

  @Test
  @DisplayName("Body vote rejects a legacy rating property even when vote is valid")
  void voteRestaurantByBody_whenLegacyRatingIsPresent_returns400() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var endpoint = "/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/vote";

    for (var body : List.of(
        "{\"restaurantId\":\"" + RestaurantStub.ID + "\",\"vote\":\"DOWN\",\"rating\":2}",
        "{\"restaurantId\":\"" + RestaurantStub.ID + "\",\"vote\":\"DOWN\",\"rating\":null}")) {
      mockMvc
          .perform(put(endpoint)
              .with(csrf())
              .header("Authorization", bearer(Role.USER))
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.messages[0].code").value("REQUEST_ERROR"));
    }

    verify(restaurantService, never()).voteRestaurant(any(), any());
  }

  @Test
  @DisplayName("Both vote endpoint forms reject arbitrary unknown properties")
  void voteRestaurant_whenUnknownPropertyIsPresent_returns400() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    var pathEndpoint = "/api/whatsforlunch/restaurant" + APIVersion.V20260517
        + "/" + RestaurantStub.ID + "/vote";
    var bodyEndpoint = "/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/vote";

    mockMvc
        .perform(put(pathEndpoint)
            .with(csrf())
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"vote\":\"UP\",\"unexpected\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.messages[0].code").value("REQUEST_ERROR"));
    mockMvc
        .perform(put(bodyEndpoint)
            .with(csrf())
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"restaurantId\":\"" + RestaurantStub.ID
                + "\",\"vote\":\"UP\",\"unexpected\":true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.messages[0].code").value("REQUEST_ERROR"));

    verify(restaurantService, never()).voteRestaurant(any(), any());
  }

  @Test
  @DisplayName("Both vote endpoint forms preserve invalid vote partitions")
  void voteRestaurant_whenVoteValueIsMalformed_returns400() throws Exception {
    when(permissionService.hasAuthority(eq("USER"))).thenReturn(true);
    when(restaurantService.voteRestaurant(eq(RestaurantStub.ID), any(RestaurantVoteRequest.class)))
        .thenThrow(new InvalidRequestException("Restaurant vote must be UP or DOWN."));
    var pathEndpoint = "/api/whatsforlunch/restaurant" + APIVersion.V20260517
        + "/" + RestaurantStub.ID + "/vote";
    var bodyEndpoint = "/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/vote";
    var malformedVotes = List.of("5", "\"MAYBE\"", "null");

    for (var malformedVote : malformedVotes) {
      mockMvc
          .perform(put(pathEndpoint)
              .with(csrf())
              .header("Authorization", bearer(Role.USER))
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .content("{\"vote\":" + malformedVote + "}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.messages[0].code").value("INVALID_REQUEST"));
      mockMvc
          .perform(put(bodyEndpoint)
              .with(csrf())
              .header("Authorization", bearer(Role.USER))
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .content("{\"restaurantId\":\"" + RestaurantStub.ID
                  + "\",\"vote\":" + malformedVote + "}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.messages[0].code").value("INVALID_REQUEST"));
    }

    mockMvc
        .perform(put(pathEndpoint)
            .with(csrf())
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.messages[0].code").value("INVALID_REQUEST"));
    mockMvc
        .perform(put(bodyEndpoint)
            .with(csrf())
            .header("Authorization", bearer(Role.USER))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .content("{\"restaurantId\":\"" + RestaurantStub.ID + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.messages[0].code").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("Anonymous request can read top-liked WFL restaurants")
  void getTopLikedRestaurants_whenAnonymous_returns200() throws Exception {
    var detail = RestaurantStub.getRestaurantDetailStub(RestaurantStub.ID);
    detail.setUpVotes(1);
    detail.setDownVotes(0);
    detail.setVoteCount(1);
    when(restaurantService.getTopLikedRestaurants(null)).thenReturn(List.of(detail));

    mockMvc
        .perform(get("/api/whatsforlunch/restaurant" + APIVersion.V20260517 + "/top-liked")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].voteCount").value(1));

    verify(restaurantService).getTopLikedRestaurants(null);
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
            .with(csrf())
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
            .with(csrf())
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
    var account = Account.builder()
        .id("account-1")
        .role(role)
        .status(AccountStatus.ACTIVE)
        .build();
    when(accountRepository.findById("account-1")).thenReturn(Optional.of(account));
    return "Bearer " + PermissionService.generateToken(account);
  }
}
