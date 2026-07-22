package dev.christopherbell.sharedfolder.service;

import dev.christopherbell.account.model.Account;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Applies a bounded fixed-window mutation burst limit to freshly loaded accounts. */
@Service
public final class SharedFolderAccountMutationLimiter {
  private static final int DEFAULT_MAX_ACCOUNTS = 10_000;
  private static final int DEFAULT_CAPACITY = 60;
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Clock clock;
  private final int maxAccounts;
  private final int capacity;
  private final Map<String, Window> windows = new LinkedHashMap<>(128, 0.75f, true);

  @Autowired
  public SharedFolderAccountMutationLimiter(Clock clock) {
    this(clock, DEFAULT_MAX_ACCOUNTS, DEFAULT_CAPACITY);
  }

  public SharedFolderAccountMutationLimiter(Clock clock, int maxAccounts, int capacity) {
    if (maxAccounts < 1 || capacity < 1) throw new IllegalArgumentException("Invalid limit");
    this.clock = clock;
    this.maxAccounts = maxAccounts;
    this.capacity = capacity;
  }

  /** Consumes one account mutation or rejects it with a stable retryable response. */
  public synchronized void requireMutation(Account account) {
    String accountId = requiredAccountId(account);
    Instant now = clock.instant();
    Window current = windows.get(accountId);
    if (current == null || !current.startedAt().plus(WINDOW).isAfter(now)) {
      admitNewWindow(accountId, now);
      return;
    }
    if (current.used() >= capacity) throw rateLimited();
    windows.put(accountId, new Window(current.startedAt(), current.used() + 1));
  }

  private void admitNewWindow(String accountId, Instant now) {
    while (!windows.containsKey(accountId) && windows.size() >= maxAccounts) {
      var eldest = windows.entrySet().iterator();
      if (!eldest.hasNext()) break;
      eldest.next();
      eldest.remove();
    }
    windows.put(accountId, new Window(now, 1));
  }

  private String requiredAccountId(Account account) {
    String id = account == null ? null : account.getId();
    if (id == null || id.isBlank() || id.length() > 128) {
      throw new org.springframework.security.access.AccessDeniedException(
          "Shared-folder access denied");
    }
    return id;
  }

  private ResponseStatusException rateLimited() {
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
        "Too many shared-folder changes. Try again later.");
  }

  private record Window(Instant startedAt, int used) {}
}
