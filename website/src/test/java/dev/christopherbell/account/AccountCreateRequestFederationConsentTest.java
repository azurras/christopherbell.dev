package dev.christopherbell.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.account.model.dto.AccountCreateRequest;
import org.junit.jupiter.api.Test;

class AccountCreateRequestFederationConsentTest {

  @Test
  void omittedFederationChoiceRemainsOptedOutForApiCompatibility() {
    var request = AccountCreateRequest.builder().build();

    assertFalse(request.federationRequested());
  }

  @Test
  void explicitFederationChoiceRequestsProvisioning() {
    var request = AccountCreateRequest.builder()
        .federatePublicVoidPosts(true)
        .build();

    assertTrue(request.federationRequested());
  }
}
