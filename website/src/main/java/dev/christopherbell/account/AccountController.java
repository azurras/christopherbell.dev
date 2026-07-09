package dev.christopherbell.account;

import static dev.christopherbell.libs.api.APIVersion.V20241215;
import static dev.christopherbell.libs.api.APIVersion.V20250903;
import static dev.christopherbell.libs.api.APIVersion.V20250914;

import dev.christopherbell.account.model.dto.AccountDetail;
import dev.christopherbell.account.model.dto.AccountCreateRequest;
import dev.christopherbell.account.model.AccountLoginRequest;
import dev.christopherbell.account.model.AccountPasswordResetConfirmRequest;
import dev.christopherbell.account.model.AccountPasswordResetRequest;
import dev.christopherbell.account.model.dto.AccountProfile;
import dev.christopherbell.account.model.dto.AccountUsernameSuggestion;
import dev.christopherbell.account.model.dto.AccountUpdateRequest;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

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
  private AccountService accountService;
  private PermissionService permissionService;

    /**
   * Approves a pending or unapproved account.
   *
   * <p>Requires {@code ADMIN} authority.</p>
   *
   * @param accountId the ID of the account to approve
   * @return HTTP 200 with the updated {@link AccountDetail} in the response payload
   * @throws Exception if approval fails or the account cannot be found
   */
  @PostMapping(
      value = V20250903 + "/approve/{accountId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDetail>> approveAccount(
      @PathVariable String accountId
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(accountService.approveAccount(accountId))
            .success(true)
            .build(), HttpStatus.OK);
  }

  /**
   * Creates a new account.
   *
   * @param accountCreateRequest the account creation request payload
   * @return HTTP 200 with the created {@link AccountDetail} in the response payload
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
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
            .payload(accountService.createAccount(accountCreateRequest))
            .success(true)
            .build(), HttpStatus.OK);
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
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')")
  public ResponseEntity<Response<AccountDetail>> deleteAccount(
      @PathVariable String accountId
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<AccountDetail>builder()
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
   * @param accountLoginRequest - the account login request.
   * @return a JWT token if the login is successful.
   * @throws Exception if there is an error logging in the account.
   */
  @PostMapping(
      value = V20241215 + "/login",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Response<String>> loginAccount(
      @RequestBody AccountLoginRequest accountLoginRequest
  ) throws Exception {
    return new ResponseEntity<>(Response.<String>builder()
        .payload(accountService.loginAccount(accountLoginRequest))
        .success(true)
        .build(), HttpStatus.OK);
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
      @RequestBody AccountPasswordResetRequest requestBody,
      HttpServletRequest servletRequest
  ) {
    accountService.requestPasswordReset(requestBody, getBaseUrl(servletRequest));
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
      @RequestBody AccountPasswordResetConfirmRequest request
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
            .build(), HttpStatus.ACCEPTED);
  }

  private String getBaseUrl(HttpServletRequest request) {
    var forwardedProto = request.getHeader("X-Forwarded-Proto");
    var forwardedHost = request.getHeader("X-Forwarded-Host");
    if (forwardedProto != null && !forwardedProto.isBlank()
        && forwardedHost != null && !forwardedHost.isBlank()) {
      return forwardedProto + "://" + forwardedHost;
    }

    var url = request.getRequestURL();
    var uri = request.getRequestURI();
    return url.substring(0, url.length() - uri.length());
  }
}
