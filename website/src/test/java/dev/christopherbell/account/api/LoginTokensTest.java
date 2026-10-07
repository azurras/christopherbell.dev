package dev.christopherbell.account.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.account.auth.AccountSecurityFingerprint;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoginTokensTest {

  private static final Instant ISSUED_AT = Instant.parse("2026-10-06T12:00:00Z");
  private static final String CONFIGURED_SECRET = "test-jwt-secret-that-is-long-enough-for-hs256";
  private static final Account SIGNED_IN_ACCOUNT = Account.builder()
      .id("account-1")
      .role(Role.USER)
      .build();

  @Test
  void issuedTokenCarriesTheAccountIdRoleAndFingerprint() {
    LoginTokens loginTokens = tokensAt(ISSUED_AT);

    Claims verifiedClaims = loginTokens.verifiedClaimsOf(loginTokens.issueFor(SIGNED_IN_ACCOUNT));

    assertThat(verifiedClaims.getSubject()).isEqualTo("account-1");
    assertThat(verifiedClaims.get(Account.PROPERTY_ROLE, String.class)).isEqualTo("USER");
    assertThat(verifiedClaims.get(AccountSecurityFingerprint.CLAIM, String.class))
        .isEqualTo(AccountSecurityFingerprint.from(SIGNED_IN_ACCOUNT));
    assertThat(verifiedClaims.getId()).isNotBlank();
  }

  @Test
  void issuedTokenExpiresSevenDaysAfterIssue() {
    LoginTokens loginTokens = tokensAt(ISSUED_AT);

    Claims verifiedClaims = loginTokens.verifiedClaimsOf(loginTokens.issueFor(SIGNED_IN_ACCOUNT));

    assertThat(verifiedClaims.getIssuedAt().toInstant()).isEqualTo(ISSUED_AT);
    assertThat(verifiedClaims.getExpiration().toInstant())
        .isEqualTo(ISSUED_AT.plus(Duration.ofDays(7)));
  }

  @Test
  void acceptsATokenPresentedWithItsBearerPrefix() {
    LoginTokens loginTokens = tokensAt(ISSUED_AT);

    String bearerHeaderValue = "Bearer " + loginTokens.issueFor(SIGNED_IN_ACCOUNT);

    assertThat(loginTokens.verifiedClaimsOf(bearerHeaderValue).getSubject()).isEqualTo("account-1");
  }

  @Test
  void rejectsATokenAfterItsLifetime() {
    String issuedToken = tokensAt(ISSUED_AT).issueFor(SIGNED_IN_ACCOUNT);
    LoginTokens tokensEightDaysLater = tokensAt(ISSUED_AT.plus(Duration.ofDays(8)));

    assertThatThrownBy(() -> tokensEightDaysLater.verifiedClaimsOf(issuedToken))
        .isInstanceOf(ExpiredJwtException.class);
  }

  @Test
  void rejectsATokenSignedWithADifferentSecret() {
    String sameLengthForeignSecret = "TEST-JWT-SECRET-THAT-IS-LONG-ENOUGH-FOR-HS256";
    assertThat(sameLengthForeignSecret).hasSameSizeAs(CONFIGURED_SECRET);
    String foreignToken = LoginTokens.fromConfiguration(
            sameLengthForeignSecret, false, Map.of(), fixedClockAt(ISSUED_AT))
        .issueFor(SIGNED_IN_ACCOUNT);

    assertThatThrownBy(() -> tokensAt(ISSUED_AT).verifiedClaimsOf(foreignToken))
        .isInstanceOf(SignatureException.class);
  }

  @Test
  void tokensFromTheSameSecretVerifyAcrossInstances() {
    String issuedToken = LoginTokensFixture.localDevelopmentLoginTokens().issueFor(SIGNED_IN_ACCOUNT);

    Claims verifiedClaims =
        LoginTokensFixture.localDevelopmentLoginTokens().verifiedClaimsOf(issuedToken);

    assertThat(verifiedClaims.getSubject()).isEqualTo("account-1");
  }

  @Test
  void configuredSecretTakesPrecedenceOverEnvironmentSecrets() {
    Map<String, String> environmentVariables = Map.of(
        "APP_JWT_SECRET", "app-environment-secret-long-enough-for-hs256",
        "JWT_SECRET", "legacy-environment-secret-long-enough-for-hs256");

    assertThat(LoginTokens.resolveSecret(" " + CONFIGURED_SECRET + " ", true, environmentVariables))
        .isEqualTo(CONFIGURED_SECRET);
  }

  @Test
  void appEnvironmentSecretTakesPrecedenceOverTheLegacyName() {
    Map<String, String> environmentVariables = Map.of(
        "APP_JWT_SECRET", "app-environment-secret-long-enough-for-hs256",
        "JWT_SECRET", "legacy-environment-secret-long-enough-for-hs256");

    assertThat(LoginTokens.resolveSecret("", true, environmentVariables))
        .isEqualTo("app-environment-secret-long-enough-for-hs256");
    assertThat(LoginTokens.resolveSecret(
            null, true, Map.of("JWT_SECRET", "legacy-environment-secret-long-enough-for-hs256")))
        .isEqualTo("legacy-environment-secret-long-enough-for-hs256");
  }

  @Test
  void productionRefusesToFallBackToTheDevelopmentSecret() {
    assertThatIllegalStateException()
        .isThrownBy(() -> LoginTokens.resolveSecret("", true, Map.of()))
        .withMessageContaining("Production JWT secret must be configured");
  }

  @Test
  void developmentFallsBackToTheLocalSecret() {
    assertThat(LoginTokens.resolveSecret("", false, Map.of()))
        .isEqualTo(LoginTokens.LOCAL_DEVELOPMENT_SECRET);
  }

  @Test
  void rejectsASecretShorterThanThirtyTwoBytes() {
    assertThatIllegalStateException()
        .isThrownBy(() -> LoginTokens.fromConfiguration(
            "too-short", true, Map.of(), fixedClockAt(ISSUED_AT)))
        .withMessage("JWT secret must be at least 32 bytes for HS256 signing.");
  }

  private static LoginTokens tokensAt(Instant now) {
    return LoginTokens.fromConfiguration(CONFIGURED_SECRET, false, Map.of(), fixedClockAt(now));
  }

  private static Clock fixedClockAt(Instant now) {
    return Clock.fixed(now, ZoneOffset.UTC);
  }
}
