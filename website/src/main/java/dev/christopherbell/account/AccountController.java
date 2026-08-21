package dev.christopherbell.account;

import static dev.christopherbell.libs.api.APIVersion.V20241215;
import static dev.christopherbell.libs.api.APIVersion.V20250903;
import static dev.christopherbell.libs.api.APIVersion.V20250914;
import static dev.christopherbell.libs.api.APIVersion.V20260717;
import static dev.christopherbell.libs.api.APIVersion.V20260726;
import static dev.christopherbell.libs.api.APIVersion.V20260728;

import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.deletion.AccountDeletionResult;
import dev.christopherbell.account.model.dto.AccountCreateRequest;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.account.model.AccountPasswordResetConfirmRequest;
import dev.christopherbell.account.model.AccountPasswordResetRequest;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.account.model.dto.MusicPermissionUpdate;
import dev.christopherbell.account.model.dto.SharedFolderPermissionUpdate;
import dev.christopherbell.account.model.dto.AccountUsernameSuggestion;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.account.model.dto.FederationConsentUpdate;
import dev.christopherbell.account.model.dto.FederationConsentStatus;
import dev.christopherbell.configuration.security.BrowserAuthenticationCookies;
import dev.christopherbell.configuration.security.BrowserSecurityProperties;
import dev.christopherbell.configuration.security.browser.BrowserSessionService;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.permission.PermissionService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.util.WebUtils;

/**
 * REST controller for account management endpoints under the base path
 * {@code /api/accounts}.
 *
 * <p>Endpoints generally return a {@link ResponseEntity} wrapping a
 * {@link Response} payload. Some routes are versioned using constants from
 * {@link dev.christopherbell.libs.api.APIVersion} such as {@link V20241215}
 * and {@link V20250903}.</p>
 *
 * <p>Authorization is enforced via Spring Security annotations. Most
 * administrative operations require the {@code ADMIN} authority as evaluated by
 * {@link PermissionService}.</p>
 *
 * @see AccountService
 * @see PermissionService
 * @see Response
 */
@Slf4j
@AllArgsConstructor
@RequestMapping("/api/accounts")
@RestController
public class AccountController {
  private static final String BROWSER_SESSION_HEADER = "X-CBELL-Browser-Session";
  private final AccountService accountService;
  private final AdminAccountQueryPort adminAccountQueryService;
  private final PermissionService permissionService;
  private final BrowserAuthenticationCookies browserAuthenticationCookies;
  private final BrowserSecurityProperties browserSecurityProperties;
  private final BrowserSessionService browserSessions;

  /**
   * Creates a new account.
   *
   * @param accountCreateRequest the account creation request payload
   * @return HTTP 201 with the created account and its canonical resource location
   * @throws Exception if validation fails or creation cannot be completed
   */
  @PostMapping(
      value = V20241215 + "/create",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<AccountDetail>> createAccount(
      @Valid @RequestBody AccountCreateRequest accountCreateRequest
  ) throws Exception {
    var account = accountService.createAccount(accountCreateRequest);
    var location = URI.create("/api/accounts" + V20250903 + "/" + account.getId());
    return ResponseEntity.created(location)
        .body(Response.<AccountDetail>builder()
            .payload(account)
            .success(true)
            .build());
  }

  /**
   * Deletes an account by ID.
   *
   * <p>Requires {@code ADMIN} authority.</p>
   *
   * @param accountId the ID of the account to delete
   * @return HTTP 200 with the deleted {@link AccountDetail} in the response payload
   * @throws Exception if deletion fails or the account cannot be found
   */
  @DeleteMapping(
      value = V20250903 + "/{accountId}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDetail>> deleteAccount(
      @PathVariable String accountId
  ) throws Exception {
    var account = accountService.getAccountById(accountId);
    accountService.deleteAccount(accountId);
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(account)
            .success(true)
            .build(), HttpStatus.OK);
  }

  /** Deletes an account using the resumable workflow and returns its final progress. */
  @DeleteMapping(
      value = V20260726 + "/{accountId}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDeletionResult>> deleteAccountResumably(
      @PathVariable String accountId
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDeletionResult>builder()
            .payload(accountService.deleteAccount(accountId))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Retrieves an account by email address.
   *
   * <p>Requires {@code ADMIN} authority.</p>
   *
   * @param email the email address of the account to retrieve
   * @return HTTP 200 with the matching {@link AccountDetail} in the response payload
   * @throws Exception if lookup fails or no account matches the email
   */
  @GetMapping(
      value = V20241215 + "/email/{email}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDetail>> getAccountByEmail(
      @PathVariable String email
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(accountService.getAccountByEmail(email))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Retrieves an account by ID.
   *
   * <p>Requires {@code ADMIN} authority.</p>
   *
   * @param id the ID of the account to retrieve
   * @return HTTP 200 with the matching {@link AccountDetail} in the response payload
   * @throws Exception if lookup fails or the account cannot be found
   */
  @GetMapping(
      value = V20250903 + "/{id}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDetail>> getAccountById(
      @PathVariable String id
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(accountService.getAccountById(id))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Retrieves an account by username.
   *
   * <p>Requires {@code ADMIN} authority.</p>
   *
   * @param username the username of the account to retrieve
   * @return HTTP 200 with the matching {@link AccountDetail} in the response payload
   * @throws Exception if lookup fails or the account cannot be found
   */
  @GetMapping(
      value = V20250903 + "/username/{username}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDetail>> getAccountByUsername(
      @PathVariable String username
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(accountService.getAccountByUsername(username))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Lists all accounts.
   *
   * <p>Requires {@code ADMIN} authority.</p>
   *
   * @return HTTP 200 with a list of {@link AccountDetail} in the response payload
   */
  @GetMapping(
      value = V20241215,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<List<AccountDetail>>> getAccounts() {
    return new ResponseEntity<>(
        Response.<List<AccountDetail>>builder()
            .payload(accountService.getAccounts())
            .success(true)
            .build(), HttpStatus.OK);
  }

  /** Returns a bounded, searchable page of accounts for back-office administration. */
  @GetMapping(
      value = V20260726 + "/admin",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AdminAccountPage>> getAdminAccounts(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String role,
      @RequestParam(required = false) String text
  ) throws InvalidRequestException {
    var query = AdminAccountQuery.from(page, size, sort, direction, status, role, text);
    return ResponseEntity.ok(Response.<AdminAccountPage>builder()
        .payload(adminAccountQueryService.getAccounts(query))
        .success(true)
        .build());
  }

  /**
   * Retrieves the account of the currently authenticated user.
   *
   * @return HTTP 200 with the caller's {@link AccountDetail} in the response payload
   * @throws Exception if the account cannot be resolved for the current user
   */
  @GetMapping(
      value = V20250903 + "/me",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<AccountDetail>> getMyAccount(
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(accountService.getSelfAccount())
            .success(true)
            .build(), HttpStatus.OK);
  }

  /** Replaces the authenticated account's explicit ActivityPub federation consent. */
  @PatchMapping(
      value = V20260728 + "/self/federation",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<AccountDetail>> updateFederationConsent(
      @Valid @RequestBody FederationConsentUpdate request) throws Exception {
    return ResponseEntity.ok(Response.<AccountDetail>builder()
        .payload(accountService.setFederationEnabled(
            permissionService.getSelfId(), request.requestedState()))
        .success(true)
        .build());
  }

  /** Returns the authenticated account's authoritative federation state. */
  @GetMapping(
      value = V20260728 + "/self/federation",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<FederationConsentStatus>> getFederationConsent()
      throws Exception {
    return ResponseEntity.ok(Response.<FederationConsentStatus>builder()
        .payload(accountService.getFederationConsent(permissionService.getSelfId()))
        .success(true)
        .build());
  }

  /**
   * Retrieves public profile metadata for a username.
   *
   * @param username the username to retrieve
   * @return HTTP 200 with public profile metadata
   * @throws Exception if the account cannot be found
   */
  @GetMapping(
      value = V20250914 + "/profile/{username}",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<AccountProfile>> getPublicProfile(
      @PathVariable String username
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountProfile>builder()
            .payload(accountService.getPublicProfile(username))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Searches active accounts by username prefix for signed-in recipient pickers.
   *
   * @param username partial username typed by the caller
   * @param limit maximum number of suggestions to return
   * @return HTTP 200 with public-safe username suggestions
   * @throws Exception if the current caller cannot be resolved
   */
  @GetMapping(
      value = V20250914 + "/search",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<AccountUsernameSuggestion>>> searchAccountsByUsername(
      @RequestParam(name = "username", required = false) String username,
      @RequestParam(name = "limit", required = false) Integer limit
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<AccountUsernameSuggestion>>builder()
            .payload(accountService.searchUsernameSuggestions(username, limit))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Follows an account by username for the authenticated user.
   *
   * @param username username to follow
   * @return HTTP 200 with updated public profile metadata
   * @throws Exception if the account cannot be followed
   */
  @PostMapping(
      value = V20250914 + "/profile/{username}/follow",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<AccountProfile>> followAccount(
      @PathVariable String username
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountProfile>builder()
            .payload(accountService.followAccount(username))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Unfollows an account by username for the authenticated user.
   *
   * @param username username to unfollow
   * @return HTTP 200 with updated public profile metadata
   * @throws Exception if the account cannot be unfollowed
   */
  @DeleteMapping(
      value = V20250914 + "/profile/{username}/follow",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<AccountProfile>> unfollowAccount(
      @PathVariable String username
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountProfile>builder()
            .payload(accountService.unfollowAccount(username))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Logs in an account.
   *
   * @param accountLoginRequest account credentials
   * @param sessionMode {@code cookie} for an HttpOnly browser session, otherwise legacy bearer mode
   * @return a JWT payload for API clients or an opaque cookie with no payload for browser mode
   * @throws Exception if there is an error logging in the account.
   */
  @PostMapping(
      value = V20241215 + "/login",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<String>> loginAccount(
      @Valid @RequestBody AccountLoginRequest accountLoginRequest,
      @RequestHeader(name = BROWSER_SESSION_HEADER, defaultValue = "") String sessionMode
  ) throws Exception {
    var token = accountService.loginAccount(accountLoginRequest);
    var browserSession = "cookie".equalsIgnoreCase(sessionMode.trim());
    var body = Response.<String>builder()
        .payload(browserSession ? null : token)
        .success(true)
        .build();
    var headers = browserSession
        ? cookieHeaders(browserAuthenticationCookies.authenticated(browserSessions.create(token)))
        : new HttpHeaders();
    return new ResponseEntity<>(body, headers, HttpStatus.OK);
  }

  /** Clears browser authentication cookies. CSRF remains required for this public endpoint. */
  @PostMapping(
      value = V20241215 + "/logout",
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<Void>> logoutAccount(HttpServletRequest request) {
    var cookie = WebUtils.getCookie(request, BrowserAuthenticationCookies.AUTH_COOKIE_NAME);
    if (cookie != null) browserSessions.revoke(cookie.getValue());
    return new ResponseEntity<>(Response.<Void>builder()
        .success(true)
        .build(), cookieHeaders(browserAuthenticationCookies.cleared()), HttpStatus.OK);
  }

  /**
   * Requests a password reset link for an email address. The response remains generic to avoid
   * disclosing whether an account exists.
   *
   * @param requestBody email address to reset
   * @param servletRequest current HTTP request for building the reset URL
   * @return HTTP 200 with a generic confirmation message
   */
  @PostMapping(
      value = V20241215 + "/password-reset/request",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<String>> requestPasswordReset(
      @Valid @RequestBody AccountPasswordResetRequest requestBody
  ) {
    accountService.requestPasswordReset(
        requestBody, browserSecurityProperties.publicBaseUrl().toString());
    return new ResponseEntity<>(Response.<String>builder()
        .payload("If an account exists for that email, a password reset link has been sent.")
        .success(true)
        .build(), HttpStatus.OK);
  }

  /**
   * Resets an account password using a valid reset token.
   *
   * @param request password reset token and new password
   * @return HTTP 200 with a confirmation message
   * @throws Exception if the token is invalid or the request is malformed
   */
  @PostMapping(
      value = V20241215 + "/password-reset/confirm",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<String>> resetPassword(
      @Valid @RequestBody AccountPasswordResetConfirmRequest request
  ) throws Exception {
    accountService.resetPassword(request);
    return new ResponseEntity<>(Response.<String>builder()
        .payload("Your password has been reset.")
        .success(true)
        .build(), HttpStatus.OK);
  }

  /**
   * Updates an existing account.
   *
   * <p>Requires {@code ADMIN} authority.</p>
   *
   * @param request the account update request payload
   * @return HTTP 202 with the updated {@link AccountDetail} in the response payload
   * @throws Exception if validation fails or update cannot be completed
   */
  @PutMapping(
      value = V20250914,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDetail>> updateAccount(
      @RequestBody AccountUpdateRequest request
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(accountService.updateAccount(request))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Replaces the stored shared-folder capabilities for an account.
   *
   * <p>Requires {@code ADMIN} authority. This endpoint intentionally changes capabilities only;
   * it does not change the account's role or JWT authorities.</p>
   *
   * @param accountId target account id
   * @param request requested read and write state
   * @return the saved account detail in the standard response envelope
   * @throws Exception if validation or the account update fails
   */
  @PatchMapping(
      value = V20260717 + "/{accountId}/shared-folder-permissions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<AccountDetail>> updateSharedFolderPermissions(
      @PathVariable String accountId,
      @RequestBody SharedFolderPermissionUpdate request) throws Exception {
    return ResponseEntity.ok(Response.<AccountDetail>builder()
        .payload(accountService.updateSharedFolderPermissions(accountId, request))
        .success(true)
        .build());
  }

  /** Replaces the target account's stored Music capabilities without changing its role. */
  @PatchMapping(
      value = V20260728 + "/{accountId}/music-permissions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Response<AccountDetail>> updateMusicPermissions(
      @PathVariable String accountId,
      @RequestBody MusicPermissionUpdate request) throws Exception {
    return ResponseEntity.ok(Response.<AccountDetail>builder()
        .payload(accountService.updateMusicPermissions(accountId, request))
        .success(true)
        .build());
  }

  private HttpHeaders cookieHeaders(List<ResponseCookie> cookies) {
    var headers = new HttpHeaders();
    cookies.forEach(cookie -> headers.add(HttpHeaders.SET_COOKIE, cookie.toString()));
    return headers;
  }
}
