package dev.christopherbell.sharedfolder.audit;

import dev.christopherbell.sharedfolder.fs.SharedFolderPathResolver;
import dev.christopherbell.sharedfolder.fs.UnsafeSharedPathException;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Builds bounded indexed MongoDB queries for fresh ADMIN audit browsing. */
@Service
public final class SharedFolderAuditQueryService {
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]+");
  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final SharedFolderAccessService access;
  private final MongoTemplate mongo;

  public SharedFolderAuditQueryService(
      SharedFolderAccessService access, MongoTemplate mongo) {
    this.access = access;
    this.mongo = mongo;
  }

  /** Returns newest-first events matching only validated indexed/bounded fields. */
  public List<SharedFolderAuditEvent> search(SharedFolderAuditFilter filter) {
    access.requireAdmin();
    SharedFolderAuditFilter safe = filter == null
        ? new SharedFolderAuditFilter(null, null, null, null, null, null, null) : filter;
    validate(safe);
    List<Criteria> criteria = new ArrayList<>();
    addToken(criteria, "accountId", safe.accountId(), 128);
    addToken(criteria, "action", safe.action(), 64);
    addToken(criteria, "outcome", safe.outcome(), 64);
    if (hasText(safe.relativePath())) {
      try {
        SharedFolderPathResolver.safeRelativeSegments(safe.relativePath(), false);
      } catch (UnsafeSharedPathException exception) {
        throw badRequest();
      }
      criteria.add(Criteria.where("relativePath")
          .is(SharedFolderAuditCommand.boundedResource(safe.relativePath())));
    }
    if (safe.from() != null || safe.to() != null) {
      Criteria occurred = Criteria.where("occurredAt");
      if (safe.from() != null) occurred.gte(safe.from());
      if (safe.to() != null) occurred.lte(safe.to());
      criteria.add(occurred);
    }
    Query query = criteria.isEmpty() ? new Query()
        : Query.query(new Criteria().andOperator(criteria));
    int requested = safe.limit() == null || safe.limit() < 1 ? DEFAULT_LIMIT : safe.limit();
    query.limit(Math.min(requested, MAX_LIMIT));
    query.with(Sort.by(Sort.Direction.DESC, "occurredAt"));
    return List.copyOf(mongo.find(query, SharedFolderAuditEvent.class));
  }

  private void validate(SharedFolderAuditFilter filter) {
    if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
      throw badRequest();
    }
    validateToken(filter.accountId(), 128);
    validateToken(filter.action(), 64);
    validateToken(filter.outcome(), 64);
  }

  private void addToken(List<Criteria> criteria, String field, String value, int max) {
    if (hasText(value)) {
      validateToken(value, max);
      criteria.add(Criteria.where(field).is(value));
    }
  }

  private void validateToken(String value, int max) {
    if (value != null && (!hasText(value) || value.length() > max || !TOKEN.matcher(value).matches())) {
      throw badRequest();
    }
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private ResponseStatusException badRequest() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid audit filter");
  }
}
