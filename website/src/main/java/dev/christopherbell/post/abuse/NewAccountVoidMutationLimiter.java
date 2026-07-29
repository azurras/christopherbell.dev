package dev.christopherbell.post.abuse;

import dev.christopherbell.account.model.Account;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Applies independent bounded hourly budgets to add actions by young accounts. */
@Service
public final class NewAccountVoidMutationLimiter {
  private static final String RATE_LIMIT_MESSAGE =
      "Too many changes from this new account. Try again in a little while.";

  private final Clock clock;
  private final NewAccountVoidMutationLimitProperties properties;
  private final Map<MutationKey, Window> windows = new LinkedHashMap<>(128, 0.75f, true);

  public NewAccountVoidMutationLimiter(
      Clock clock, NewAccountVoidMutationLimitProperties properties) {
    this.clock = clock;
    this.properties = properties;
  }

  /** Consumes one new-account add action or rejects it before the domain write. */
  public synchronized void require(Account account, VoidMutationKind kind) {
    var now = clock.instant();
    if (!isNewAccount(account, now)) {
      return;
    }
    var key = new MutationKey(requiredAccountId(account), Objects.requireNonNull(kind));
    var current = windows.get(key);
    if (current == null || !current.startedOn().plus(properties.window()).isAfter(now)) {
      admitNewWindow(key, now);
      return;
    }
    if (current.used() >= properties.capacity(kind)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMIT_MESSAGE);
    }
    windows.put(key, new Window(current.startedOn(), current.used() + 1));
  }

  synchronized int trackedWindowCount() {
    return windows.size();
  }

  private boolean isNewAccount(Account account, Instant now) {
    return account != null
        && account.getCreatedOn() != null
        && account.getCreatedOn().plus(properties.accountAge()).isAfter(now);
  }

  private void admitNewWindow(MutationKey key, Instant now) {
    while (!windows.containsKey(key) && windows.size() >= properties.maxTrackedKeys()) {
      var eldest = windows.entrySet().iterator();
      if (!eldest.hasNext()) {
        break;
      }
      eldest.next();
      eldest.remove();
    }
    windows.put(key, new Window(now, 1));
  }

  private static String requiredAccountId(Account account) {
    String id = account == null ? null : account.getId();
    if (id == null || id.isBlank() || id.length() > 128
        || id.codePoints().anyMatch(Character::isISOControl)) {
      throw new AccessDeniedException("Account is unavailable.");
    }
    return id;
  }

  private record MutationKey(String accountId, VoidMutationKind kind) {}

  private record Window(Instant startedOn, int used) {}
}
