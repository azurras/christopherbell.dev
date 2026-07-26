package dev.christopherbell.account.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AccountAuthenticationRequestValidationTest {
  private static jakarta.validation.ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void createValidator() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    validatorFactory.close();
  }

  @Test
  void loginRequest_partitionsMissingMalformedOversizedAndValidValues() {
    var invalid = List.of(
        new AccountLoginRequest(null, null),
        new AccountLoginRequest(" ", " "),
        new AccountLoginRequest("not-an-email", "password"),
        new AccountLoginRequest("a".repeat(243) + "@example.test", "password"),
        new AccountLoginRequest("user@example.test", "p".repeat(129)));

    invalid.forEach(request -> assertFalse(validator.validate(request).isEmpty()));
    assertTrue(validator.validate(
        new AccountLoginRequest("user@example.test", "password")).isEmpty());
  }

  @Test
  void resetRequest_partitionsMissingMalformedOversizedAndValidEmail() {
    var invalid = List.of(
        new AccountPasswordResetRequest(null),
        new AccountPasswordResetRequest(" "),
        new AccountPasswordResetRequest("not-an-email"),
        new AccountPasswordResetRequest("a".repeat(243) + "@example.test"));

    invalid.forEach(request -> assertFalse(validator.validate(request).isEmpty()));
    assertTrue(validator.validate(
        new AccountPasswordResetRequest("user@example.test")).isEmpty());
  }

  @Test
  void resetConfirmation_partitionsMissingBlankOversizedAndValidValues() {
    var invalid = List.of(
        new AccountPasswordResetConfirmRequest(null, null),
        new AccountPasswordResetConfirmRequest(" ", " "),
        new AccountPasswordResetConfirmRequest("t".repeat(513), "password"),
        new AccountPasswordResetConfirmRequest("token", "short"),
        new AccountPasswordResetConfirmRequest("token", "p".repeat(129)));

    invalid.forEach(request -> assertFalse(validator.validate(request).isEmpty()));
    assertTrue(validator.validate(
        new AccountPasswordResetConfirmRequest("token", "password")).isEmpty());
  }
}
