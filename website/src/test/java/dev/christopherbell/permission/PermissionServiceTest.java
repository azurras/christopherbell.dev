package dev.christopherbell.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.api.exception.InvalidTokenException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PermissionServiceTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
    PermissionService.configureSigningKey(null);
  }

  @Test
  @DisplayName("Has authority allows higher roles to satisfy lower requirements")
  void hasAuthority_whenRoleLevelIsHighEnough_returnsTrue() {
    setTokenForRole(Role.ADMIN);

    assertTrue(new PermissionService().hasAuthority("USER"));
    assertTrue(new PermissionService().hasAuthority("MOD"));
    assertTrue(new PermissionService().hasAuthority("ADMIN"));
  }

  @Test
  @DisplayName("Has authority denies lower roles for higher requirements")
  void hasAuthority_whenRoleLevelIsTooLow_returnsFalse() {
    setTokenForRole(Role.USER);

    assertFalse(new PermissionService().hasAuthority("MOD"));
    assertFalse(new PermissionService().hasAuthority("ADMIN"));
  }

  @Test
  @DisplayName("Has authority denies missing authentication and invalid role names")
  void hasAuthority_whenAuthenticationOrRoleInvalid_returnsFalse() {
    var service = new PermissionService();

    assertFalse(service.hasAuthority("USER"));

    setTokenForRole(Role.USER);
    assertFalse(service.hasAuthority(null));
    assertFalse(service.hasAuthority("OWNER"));
  }

  @Test
  @DisplayName("Configured JWT secret signs and validates tokens")
  void configuredSecret_isUsedForTokenSigningAndValidation() {
    PermissionService.configureSigningKey("test-jwt-secret-that-is-long-enough-for-hs256");

    var token = PermissionService.generateToken(Account.builder()
        .id("account-1")
        .role(Role.USER)
        .build());

    assertEquals("account-1", PermissionService.validateToken(token).getSubject());
  }

  @Test
  @DisplayName("Approved accounts pass and unapproved accounts throw")
  void isAccountApproved_validatesApprovalFlag() throws Exception {
    assertTrue(PermissionService.isAccountApproved(Account.builder().isApproved(true).build()));
    assertThrows(
        InvalidTokenException.class,
        () -> PermissionService.isAccountApproved(Account.builder().isApproved(false).build()));
  }

  @Test
  @DisplayName("Active status is required for active-account checks")
  void isAccountActive_requiresActiveStatus() throws Exception {
    assertTrue(PermissionService.isAccountActive(AccountStatus.ACTIVE));
    assertFalse(PermissionService.isAccountActive(AccountStatus.INACTIVE));
    assertFalse(PermissionService.isAccountActive(AccountStatus.SUSPENDED));
  }

  private void setTokenForRole(Role role) {
    var token = PermissionService.generateToken(Account.builder()
        .id("account-1")
        .role(role)
        .build());
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            "account-1",
            token,
            List.of(new SimpleGrantedAuthority(role.name()))));
  }
}
