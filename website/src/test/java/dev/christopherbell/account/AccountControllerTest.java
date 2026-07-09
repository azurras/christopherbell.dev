package dev.christopherbell.account;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.account.model.dto.AccountUsernameSuggestion;
import dev.christopherbell.account.model.AccountPasswordResetConfirmRequest;
import dev.christopherbell.account.model.AccountPasswordResetRequest;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceExistsException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.test.TestUtil;
import dev.christopherbell.permission.PermissionService;
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

@WebMvcTest(AccountController.class)
@Import(ControllerExceptionHandler.class)
public class AccountControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private AccountService accountService;

  @Test
  @DisplayName("Create account: invalid email returns 400 before service")
  @WithMockUser
  public void testCreateAccount_whenInvalidEmail_Returns400() throws Exception {
    var request = """
        {
          "firstName": "Chris",
          "lastName": "Bell",
          "email": "not-an-email",
          "password": "long-enough",
          "username": "chris"
        }
        """;

    mockMvc
        .perform(
            post("/api/accounts" + APIVersion.V20241215 + "/create")
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));

    verifyNoInteractions(accountService);
  }

  @Test
  @DisplayName("Should update an account when caller has ADMIN role.")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateAccount() throws Exception {
    var request = TestUtil.readJsonAsString("/request/account-update-request.json");
    var requestObject = TestUtil.readJsonAsObject("/request/account-update-request.json", AccountUpdateRequest.class);

    var detail = AccountDetail.builder()
        .id(requestObject.id())
        .email(requestObject.email())
        .firstName(requestObject.firstName())
        .lastName(requestObject.lastName())
        .username(requestObject.username())
        .role(Role.ADMIN)
        .status(AccountStatus.ACTIVE)
        .build();

    when(accountService.updateAccount(eq(requestObject))).thenReturn(detail);

    mockMvc
        .perform(
            put("/api/accounts" + APIVersion.V20250914)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isAccepted())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").isNotEmpty())
        .andExpect(jsonPath("$.payload.id").value(requestObject.id()))
        .andExpect(jsonPath("$.payload.username").value(requestObject.username()))
        .andExpect(jsonPath("$.payload.email").value(requestObject.email()))
        .andExpect(jsonPath("$.payload.firstName").value(requestObject.firstName()))
        .andExpect(jsonPath("$.payload.lastName").value(requestObject.lastName()));

    verify(accountService).updateAccount(eq(requestObject));
  }

  @Test
  @DisplayName("testUpdateAccount_whenNotAuthorized_Returns401")
  public void testUpdateAccount_whenNotAuthorized_Returns401() throws Exception {
    var request = TestUtil.readJsonAsString("/request/account-update-request.json");

    mockMvc
        .perform(
            put("/api/accounts" + APIVersion.V20250914)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(accountService);
  }

  @Test
  @DisplayName("testUpdateAccount_whenInvalidRequest_Returns400")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateAccount_whenInvalidRequest_Returns400() throws Exception {
    var request = TestUtil.readJsonAsString("/request/account-update-request.json");
    var requestObject = TestUtil.readJsonAsObject("/request/account-update-request.json", AccountUpdateRequest.class);

    when(accountService.updateAccount(eq(requestObject)))
        .thenThrow(new InvalidRequestException("Account id cannot be null or blank."));

    mockMvc
        .perform(
            put("/api/accounts" + APIVersion.V20250914)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isBadRequest());

    verify(accountService).updateAccount(eq(requestObject));
  }

  @Test
  @DisplayName("testUpdateAccount_whenNotFound_Returns404")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateAccount_whenNotFound_Returns404() throws Exception {
    var request = TestUtil.readJsonAsString("/request/account-update-request.json");
    var requestObject = TestUtil.readJsonAsObject("/request/account-update-request.json", AccountUpdateRequest.class);

    when(accountService.updateAccount(eq(requestObject)))
        .thenThrow(new ResourceNotFoundException("Account not found"));

    mockMvc
        .perform(
            put("/api/accounts" + APIVersion.V20250914)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isNotFound());

    verify(accountService).updateAccount(eq(requestObject));
  }

  @Test
  @DisplayName("testUpdateAccount_whenWrongContentType_Returns415")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateAccount_whenWrongContentType_Returns415() throws Exception {
    var request = TestUtil.readJsonAsString("/request/account-update-request.json");

    mockMvc
        .perform(
            put("/api/accounts" + APIVersion.V20250914)
                .with(csrf())
                .content(request)
                .contentType(MediaType.TEXT_PLAIN))
        .andExpect(status().isUnsupportedMediaType());

    verifyNoInteractions(accountService);
  }

  @Test
  @DisplayName("testUpdateAccount_whenAcceptHeaderUnsupported_Returns406")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateAccount_whenAcceptHeaderUnsupported_Returns406() throws Exception {
    var request = TestUtil.readJsonAsString("/request/account-update-request.json");

    mockMvc
        .perform(
            put("/api/accounts" + APIVersion.V20250914)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_PLAIN))
        .andExpect(status().isNotAcceptable());

    verifyNoInteractions(accountService);
  }

  @Test
  @DisplayName("testUpdateAccount_whenConflict_Returns409")
  @WithMockUser(authorities = {"ADMIN"})
  public void testUpdateAccount_whenConflict_Returns409() throws Exception {
    var request = TestUtil.readJsonAsString("/request/account-update-request.json");
    var requestObject = TestUtil.readJsonAsObject("/request/account-update-request.json", AccountUpdateRequest.class);

    when(accountService.updateAccount(eq(requestObject)))
        .thenThrow(new ResourceExistsException("Email already exists"));

    mockMvc
        .perform(
            put("/api/accounts" + APIVersion.V20250914)
                .with(csrf())
                .content(request)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isConflict());

    verify(accountService).updateAccount(eq(requestObject));
  }

  @Test
  @DisplayName("testApproveAccount_whenAuthorized_Returns200")
  @WithMockUser(authorities = {"ADMIN"})
  public void testApproveAccount_whenAuthorized_Returns200() throws Exception {
    var detail = AccountDetail.builder()
        .id("acc-42")
        .email("user@example.com")
        .username("user42")
        .build();

    when(accountService.approveAccount(eq("acc-42"))).thenReturn(detail);

    mockMvc
        .perform(
            post("/api/accounts" + APIVersion.V20250903 + "/approve/{accountId}", "acc-42")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("acc-42"));

    verify(accountService).approveAccount(eq("acc-42"));
  }

  @Test
  @DisplayName("testDeleteAccount_whenAuthorized_Returns200")
  @WithMockUser(authorities = {"ADMIN"})
  public void testDeleteAccount_whenAuthorized_Returns200() throws Exception {
    var detail = AccountDetail.builder().id("to-del").build();
    when(accountService.deleteAccount(eq("to-del"))).thenReturn(detail);

    mockMvc
        .perform(
            delete("/api/accounts" + APIVersion.V20250903 + "/{id}", "to-del")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("to-del"));

    verify(accountService).deleteAccount(eq("to-del"));
  }

  @Test
  @DisplayName("testGetAccountByEmail_whenAuthorized_Returns200")
  @WithMockUser(authorities = {"ADMIN"})
  public void testGetAccountByEmail_whenAuthorized_Returns200() throws Exception {
    var detail = AccountDetail.builder().id("acc-1").email("user@example.com").build();
    when(accountService.getAccountByEmail(eq("user@example.com"))).thenReturn(detail);

    mockMvc
        .perform(
            get("/api/accounts" + dev.christopherbell.libs.api.APIVersion.V20241215 + "/email/{email}", "user@example.com")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.email").value("user@example.com"));

    verify(accountService).getAccountByEmail(eq("user@example.com"));
  }

  @Test
  @DisplayName("testGetMyAccount_whenUserAuthorized_Returns200")
  @WithMockUser(authorities = {"USER"})
  public void testGetMyAccount_whenUserAuthorized_Returns200() throws Exception {
    var detail = AccountDetail.builder().id("me").email("me@example.com").build();
    when(accountService.getSelfAccount()).thenReturn(detail);

    mockMvc
        .perform(
            get("/api/accounts" + APIVersion.V20250903 + "/me")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("me"));
  }

  @Test
  @DisplayName("Public profile: returns safe profile metadata")
  @WithMockUser
  public void testGetPublicProfile_Returns200() throws Exception {
    var profile = AccountProfile.builder()
        .id("acc-1")
        .username("chris")
        .followerCount(2)
        .followingCount(3)
        .postCount(4)
        .replyCount(5)
        .followedByMe(false)
        .self(false)
        .build();
    when(accountService.getPublicProfile(eq("chris"))).thenReturn(profile);

    mockMvc
        .perform(get("/api/accounts" + APIVersion.V20250914 + "/profile/{username}", "chris")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.username").value("chris"))
        .andExpect(jsonPath("$.payload.firstName").doesNotExist())
        .andExpect(jsonPath("$.payload.lastName").doesNotExist())
        .andExpect(jsonPath("$.payload.followerCount").value(2))
        .andExpect(jsonPath("$.payload.followingCount").value(3))
        .andExpect(jsonPath("$.payload.postCount").value(4))
        .andExpect(jsonPath("$.payload.replyCount").value(5))
        .andExpect(jsonPath("$.payload.totalLikesReceived").doesNotExist());

    verify(accountService).getPublicProfile(eq("chris"));
  }

  @Test
  @DisplayName("Username search: USER -> returns public-safe suggestions")
  @WithMockUser(authorities = {"USER"})
  public void testSearchAccountsByUsername_whenUser_ReturnsSuggestions() throws Exception {
    when(accountService.searchUsernameSuggestions(eq("ali"), eq(5)))
        .thenReturn(List.of(new AccountUsernameSuggestion("alice")));

    mockMvc
        .perform(get("/api/accounts" + APIVersion.V20250914 + "/search")
            .queryParam("username", "ali")
            .queryParam("limit", "5")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].username").value("alice"))
        .andExpect(jsonPath("$.payload[0].email").doesNotExist());

    verify(accountService).searchUsernameSuggestions(eq("ali"), eq(5));
  }

  @Test
  @DisplayName("Username search: anonymous -> 401")
  public void testSearchAccountsByUsername_whenAnonymous_Returns401() throws Exception {
    mockMvc
        .perform(get("/api/accounts" + APIVersion.V20250914 + "/search")
            .queryParam("username", "ali")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(accountService);
  }

  @Test
  @DisplayName("Follow account: USER -> 200 updated profile")
  @WithMockUser(authorities = {"USER"})
  public void testFollowAccount_whenUser_Returns200() throws Exception {
    var profile = AccountProfile.builder()
        .id("acc-2")
        .username("target")
        .followedByMe(true)
        .followerCount(1)
        .build();
    when(accountService.followAccount(eq("target"))).thenReturn(profile);

    mockMvc
        .perform(post("/api/accounts" + APIVersion.V20250914 + "/profile/{username}/follow", "target")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.followedByMe").value(true));

    verify(accountService).followAccount(eq("target"));
  }

  @Test
  @DisplayName("Unfollow account: USER -> 200 updated profile")
  @WithMockUser(authorities = {"USER"})
  public void testUnfollowAccount_whenUser_Returns200() throws Exception {
    var profile = AccountProfile.builder()
        .id("acc-2")
        .username("target")
        .followedByMe(false)
        .followerCount(0)
        .build();
    when(accountService.unfollowAccount(eq("target"))).thenReturn(profile);

    mockMvc
        .perform(delete("/api/accounts" + APIVersion.V20250914 + "/profile/{username}/follow", "target")
            .with(csrf())
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.followedByMe").value(false));

    verify(accountService).unfollowAccount(eq("target"));
  }

  @Test
  @DisplayName("testLoginAccount_whenValid_Returns200WithToken")
  @WithMockUser
  public void testLoginAccount_whenValid_Returns200WithToken() throws Exception {
    when(accountService.loginAccount(eq(new dev.christopherbell.account.model.AccountLoginRequest("user@example.com", "pass"))))
        .thenReturn("jwt-token");

    var json = "{\"email\":\"user@example.com\",\"password\":\"pass\"}";

    mockMvc
        .perform(
            post("/api/accounts" + APIVersion.V20241215 + "/login")
                .with(csrf())
                .content(json)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").value("jwt-token"));
  }

  @Test
  @DisplayName("Password reset request returns generic success")
  @WithMockUser
  public void testRequestPasswordReset_ReturnsGenericSuccess() throws Exception {
    var json = "{\"email\":\"user@example.com\"}";

    mockMvc
        .perform(
            post("/api/accounts" + APIVersion.V20241215 + "/password-reset/request")
                .with(csrf())
                .content(json)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").value("If an account exists for that email, a password reset link has been sent."));

    verify(accountService).requestPasswordReset(
        eq(new AccountPasswordResetRequest("user@example.com")),
        eq("http://localhost"));
  }

  @Test
  @DisplayName("Password reset confirm returns success")
  @WithMockUser
  public void testResetPassword_ReturnsSuccess() throws Exception {
    var json = "{\"token\":\"reset-token\",\"password\":\"new-password\"}";
    var request = new AccountPasswordResetConfirmRequest("reset-token", "new-password");

    mockMvc
        .perform(
            post("/api/accounts" + APIVersion.V20241215 + "/password-reset/confirm")
                .with(csrf())
                .content(json)
                .contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").value("Your password has been reset."));

    verify(accountService).resetPassword(eq(request));
  }
}
