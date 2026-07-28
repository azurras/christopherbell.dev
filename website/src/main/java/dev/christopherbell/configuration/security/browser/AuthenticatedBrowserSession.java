package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.account.model.Role;
import java.util.Optional;

/** Fresh account identity resolved from a valid opaque browser session. */
public record AuthenticatedBrowserSession(
    String accountId,
    Role role,
    Optional<String> rotatedToken) {
}
