package dev.christopherbell.sharedfolder.audit;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.permission.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

/** Creates safe best-effort audit commands and deduplicates logical ranged content access. */
@Component
@Slf4j
public final class SharedFolderAuditRecorder {
  private static final Duration LOGICAL_ACCESS_WINDOW = Duration.ofMinutes(5);
  private static final int MAX_LOGICAL_ACCESS_ENTRIES = 10_000;

  private final SharedFolderAuditSink sink;
  private final PermissionService permissions;
  private final ClientIpResolver clientIps;
  private final Clock clock;
  private final Map<String, Instant> logicalAccess = new LinkedHashMap<>();

  public SharedFolderAuditRecorder(
      SharedFolderAuditSink sink,
      PermissionService permissions,
      ClientIpResolver clientIps,
      Clock clock) {
    this.sink = sink;
    this.permissions = permissions;
    this.clientIps = clientIps;
    this.clock = clock;
  }

  public void recordCurrent(
      String action, String resource, Long size, String outcome, String failureCategory) {
    String accountId;
    try {
      accountId = permissions.getSelfId();
    } catch (RuntimeException exception) {
      accountId = "unknown";
    }
    record(accountId, action, resource, size, outcome, failureCategory);
  }

  public void recordFor(
      Account account, String action, String resource, Long size,
      String outcome, String failureCategory) {
    record(account == null ? "unknown" : account.getId(), action, resource, size,
        outcome, failureCategory);
  }

  /** Records unattended lifecycle work under a fixed non-user account id. */
  public void recordSystem(
      String action, String resource, Long size, String outcome, String failureCategory) {
    record("system", action, resource, size, outcome, failureCategory);
  }

  /** Records an unattended failure with the same closed failure categories as request work. */
  public void recordSystemFailure(
      String action, String resource, Long size, RuntimeException failure) {
    record("system", action, safeRejectedResource(resource), size, "rejected",
        failureCategory(failure));
  }

  /** Records only the first content/range request in a short logical playback/download window. */
  public void recordLogicalAccess(String action, String resource, Long size) {
    Instant now = clock.instant();
    String accountId;
    try {
      accountId = permissions.getSelfId();
    } catch (RuntimeException exception) {
      accountId = "unknown";
    }
    String key = accountId + "\n" + action + "\n" + resource;
    boolean shouldRecord;
    synchronized (logicalAccess) {
      Instant previous = logicalAccess.get(key);
      shouldRecord = previous == null || !previous.plus(LOGICAL_ACCESS_WINDOW).isAfter(now);
      if (shouldRecord) {
        if (previous != null) logicalAccess.remove(key);
        while (logicalAccess.size() >= MAX_LOGICAL_ACCESS_ENTRIES) {
          var oldest = logicalAccess.entrySet().iterator();
          if (!oldest.hasNext()) break;
          oldest.next();
          oldest.remove();
        }
        logicalAccess.put(key, now);
      }
    }
    if (shouldRecord) record(accountId, action, resource, size, "accepted", null);
  }

  /** Records a rejected attempt without allowing an unsafe raw path into persistence. */
  public void recordRejected(String action, String resource, String failureCategory) {
    String safeResource = resource;
    try {
      SharedFolderPathResolver.safeRelativeSegments(resource, true);
    } catch (RuntimeException exception) {
      safeResource = "invalid-path";
    }
    recordCurrent(action, safeResource, null, "rejected", failureCategory);
  }

  /** Maps an operation failure to a fixed safe category without persisting exception text. */
  public void recordFailure(String action, String resource, RuntimeException failure) {
    recordRejected(action, resource, failureCategory(failure));
  }

  private String failureCategory(RuntimeException failure) {
    String category = "failure";
    if (failure instanceof AccessDeniedException) {
      category = "access_denied";
    } else if (failure instanceof ResponseStatusException status) {
      category = switch (status.getStatusCode().value()) {
        case 400 -> "invalid_request";
        case 403 -> "access_denied";
        case 404 -> "not_found";
        case 409 -> "conflict";
        case 413 -> "too_large";
        case 416 -> "invalid_range";
        case 429 -> "rate_limited";
        case 507 -> "insufficient_storage";
        case 503 -> "unavailable";
        default -> "failure";
      };
    }
    return category;
  }

  private void record(
      String accountId, String action, String resource, Long size,
      String outcome, String failureCategory) {
    try {
      sink.record(new SharedFolderAuditCommand(
          safeAccountId(accountId), action, safeResource(resource), clock.instant(),
          currentClientIp(), size, outcome, failureCategory));
    } catch (RuntimeException exception) {
      // Audit degradation must not expose persistence or filesystem diagnostics to the caller.
      log.warn("Shared-folder audit record could not be persisted");
    }
  }

  private String safeAccountId(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private String safeResource(String value) {
    return value == null || value.isEmpty() ? "root" : value;
  }

  private String safeRejectedResource(String resource) {
    try {
      SharedFolderPathResolver.safeRelativeSegments(resource, true);
      return safeResource(resource);
    } catch (RuntimeException exception) {
      return "invalid-path";
    }
  }

  private String currentClientIp() {
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes instanceof ServletRequestAttributes servlet) {
      HttpServletRequest request = servlet.getRequest();
      String value = clientIps.resolveClientIp(request);
      return value == null || value.isBlank() ? "unknown" : value;
    }
    return "unknown";
  }
}
