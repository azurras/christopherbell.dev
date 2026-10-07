package dev.christopherbell.permission;

import dev.christopherbell.account.model.Role;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Answers authorization questions about the current request's authenticated account.
 *
 * <p>Controllers call {@link #hasAuthority} from {@code @PreAuthorize} expressions such as
 * {@code @permissionService.hasAuthority('ADMIN')}. Login tokens live in {@link LoginTokens}.</p>
 */
@Service
public class PermissionService {

  /**
   * Returns the authenticated account id of the current request.
   *
   * <p>Static only for the two remaining callers that do not use an injected instance; the
   * account and post slices of the style migration move them to {@link #getSelfId()}.</p>
   *
   * @return the authenticated account id
   * @throws IllegalStateException if the request is not authenticated with an account id
   */
  public static String getSelf() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()
        || authentication.getName() == null || authentication.getName().isBlank()) {
      throw new IllegalStateException("Authenticated account id is unavailable.");
    }
    return authentication.getName();
  }

  /**
   * Returns the authenticated account id of the current request.
   *
   * @return the authenticated account id
   * @throws IllegalStateException if the request is not authenticated with an account id
   */
  public String getSelfId() {
    return getSelf();
  }

  /**
   * Whether the current request's account holds a role at least as high as the required one.
   *
   * <p>Roles rank {@code USER < MOD < ADMIN}. An unauthenticated request, a missing or unknown
   * required role, and authorities that are not role names all deny.</p>
   *
   * @param requiredRoleName the minimum role name, such as {@code "ADMIN"}
   * @return {@code true} when a held role ranks at or above the required role
   */
  public boolean hasAuthority(String requiredRoleName) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    Optional<Role> requiredRole = roleNamed(requiredRoleName);
    if (requiredRole.isEmpty()) {
      return false;
    }
    int requiredRank = rankOf(requiredRole.get());
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .map(PermissionService::roleNamed)
        .flatMap(Optional::stream)
        .anyMatch(heldRole -> rankOf(heldRole) >= requiredRank);
  }

  private static Optional<Role> roleNamed(String roleName) {
    return Arrays.stream(Role.values())
        .filter(role -> role.name().equals(roleName))
        .findFirst();
  }

  private static int rankOf(Role role) {
    return switch (role) {
      case USER -> 1;
      case MOD -> 2;
      case ADMIN -> 3;
    };
  }
}
