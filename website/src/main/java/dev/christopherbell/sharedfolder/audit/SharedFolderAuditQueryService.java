package dev.christopherbell.sharedfolder.audit;

import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Builds bounded indexed queries for fresh ADMIN audit browsing. */
@Service
public final class SharedFolderAuditQueryService {
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]+");
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final SharedFolderAccessService access;
  private final SharedFolderAuditRepository repository;

  public SharedFolderAuditQueryService(
      SharedFolderAccessService access, SharedFolderAuditRepository repository) {
    this.access = access;
    this.repository = repository;
  }

  /** Returns newest-first events matching only validated indexed/bounded fields. */
  public List<SharedFolderAuditEvent> search(SharedFolderAuditFilter filter) {
    access.requireAdmin();
    SharedFolderAuditFilter safe = filter == null
        ? new SharedFolderAuditFilter(null, null, null, null, null, null, null) : filter;
    validate(safe);
    validateToken(safe.accountId(), 128);
    validateToken(safe.action(), 64);
    validateToken(safe.outcome(), 64);
    String relativePath = null;
    if (hasText(safe.relativePath())) {
      try {
        SharedFolderPathResolver.safeRelativeSegments(safe.relativePath(), false);
      } catch (UnsafeSharedPathException exception) {
        throw badRequest();
      }
      relativePath = SharedFolderAuditCommand.boundedResource(safe.relativePath());
    }
    int requested = safe.limit() == null || safe.limit() < 1 ? DEFAULT_LIMIT : safe.limit();
    return repository.search(
        textOrNull(safe.accountId()), textOrNull(safe.action()), textOrNull(safe.outcome()),
        relativePath, safe.from(), safe.to(), Math.min(requested, MAX_LIMIT));
  }

  private void validate(SharedFolderAuditFilter filter) {
    if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
      throw badRequest();
    }
    validateToken(filter.accountId(), 128);
    validateToken(filter.action(), 64);
    validateToken(filter.outcome(), 64);
  }

  private void validateToken(String value, int max) {
    if (value != null && (!hasText(value) || value.length() > max || !TOKEN.matcher(value).matches())) {
      throw badRequest();
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private String textOrNull(String value) {
    return hasText(value) ? value : null;
  }

  private ResponseStatusException badRequest() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audit filter");
  }
}
