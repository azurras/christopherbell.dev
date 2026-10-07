package dev.christopherbell.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.christopherbell.account.model.Role;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PermissionServiceTest {

  private final PermissionService permissionService = new PermissionService();

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest(name = "{0} satisfies {1}: {2}")
  @CsvSource({
      "USER, USER, true",
      "USER, MOD, false",
      "USER, ADMIN, false",
      "MOD, USER, true",
      "MOD, MOD, true",
      "MOD, ADMIN, false",
      "ADMIN, USER, true",
      "ADMIN, MOD, true",
      "ADMIN, ADMIN, true",
  })
  void heldRoleSatisfiesEveryRequiredRoleAtOrBelowItsRank(
      Role heldRole, String requiredRoleName, boolean expectedDecision) {
    signInAs("account-1", heldRole.name());

    assertThat(permissionService.hasAuthority(requiredRoleName)).isEqualTo(expectedDecision);
  }

  @Test
  void deniesARequestWithoutAuthentication() {
    assertThat(permissionService.hasAuthority("USER")).isFalse();
  }

  @Test
  void deniesAnUnauthenticatedAnonymousToken() {
    SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
        "anonymous-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ADMIN"))));
    SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);

    assertThat(permissionService.hasAuthority("USER")).isFalse();
  }

  @Test
  void deniesAMissingOrUnknownRequiredRole() {
    signInAs("account-1", Role.ADMIN.name());

    assertThat(permissionService.hasAuthority(null)).isFalse();
    assertThat(permissionService.hasAuthority("OWNER")).isFalse();
    assertThat(permissionService.hasAuthority("admin")).isFalse();
  }

  @Test
  void ignoresAuthoritiesThatAreNotRoleNames() {
    signInAs("account-1", "SCOPE_read", "ROLE_ADMIN");

    assertThat(permissionService.hasAuthority("USER")).isFalse();
  }

  @Test
  void opaqueBrowserAuthenticationUsesItsPrincipalAndAuthorities() {
    signInAs("account-42", Role.MOD.name());

    assertThat(permissionService.getSelfId()).isEqualTo("account-42");
    assertThat(PermissionService.getSelf()).isEqualTo("account-42");
    assertThat(permissionService.hasAuthority("MOD")).isTrue();
    assertThat(permissionService.hasAuthority("ADMIN")).isFalse();
  }

  @Test
  void reportsAMissingAccountIdAsUnavailable() {
    assertThatIllegalStateException()
        .isThrownBy(permissionService::getSelfId)
        .withMessage("Authenticated account id is unavailable.");
  }

  private static void signInAs(String accountId, String... authorityNames) {
    List<SimpleGrantedAuthority> authorities = Arrays.stream(authorityNames)
        .map(SimpleGrantedAuthority::new)
        .toList();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(accountId, null, authorities));
  }
}
