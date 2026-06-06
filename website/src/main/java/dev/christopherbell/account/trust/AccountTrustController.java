package dev.christopherbell.account.trust;

import dev.christopherbell.account.trust.model.AccountTrustActionRequest;
import dev.christopherbell.account.trust.model.AccountTrustDetail;
import dev.christopherbell.account.trust.model.AccountTrustType;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.model.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account trust API for muting and blocking other users. */
@RequiredArgsConstructor
@RequestMapping("/api/accounts/2026-06-02/trust")
@RestController
public class AccountTrustController {
  private final AccountTrustService accountTrustService;

  @PutMapping(value = "/{username}", consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<AccountTrustDetail>> setTrust(
      @PathVariable String username,
      @RequestBody AccountTrustActionRequest request
  ) throws InvalidRequestException, ResourceNotFoundException {
    return new ResponseEntity<>(
        Response.<AccountTrustDetail>builder()
            .payload(accountTrustService.setTrust(username, request == null ? null : request.type()))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @DeleteMapping(value = "/{username}/{type}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<Void>> clearTrust(
      @PathVariable String username,
      @PathVariable AccountTrustType type
  ) throws InvalidRequestException, ResourceNotFoundException {
    accountTrustService.clearTrust(username, type);
    return new ResponseEntity<>(Response.<Void>builder().success(true).build(), HttpStatus.OK);
  }
}
