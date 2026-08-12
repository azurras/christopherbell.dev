package dev.christopherbell.admin.activity;

import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Validates and reads stable append-only audit pages. */
@Service
public class AdminActivityQueryService {
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_FILTER_LENGTH = 64;
  private final KindScopedMongoOperations<AdminActivity> activities;

  public AdminActivityQueryService(DomainMongoOperationsFactory factory) {
    this.activities = factory.forType(AdminActivity.class);
  }

  /** Executes allowlisted equality and escaped actor filters. */
  public AdminActivityPage query(AdminActivityQuery request) throws InvalidRequestException {
    validate(request);
    var filters = new ArrayList<Criteria>();
    if (hasText(request.action())) filters.add(Criteria.where("action").is(request.action().strip()));
    if (hasText(request.targetType())) {
      filters.add(Criteria.where("targetType").is(request.targetType().strip()));
    }
    if (hasText(request.actor())) {
      filters.add(Criteria.where("actorUsername").regex(Pattern.compile(
          Pattern.quote(request.actor().strip()), Pattern.CASE_INSENSITIVE)));
    }
    if (request.from() != null) {
      filters.add(Criteria.where("createdOn").gte(request.from()).lte(request.to()));
    }
    Criteria criteria = filters.isEmpty()
        ? new Criteria()
        : new Criteria().andOperator(filters.toArray(Criteria[]::new));
    long total = activities.count(new Query(criteria));
    var pageQuery = new Query(criteria)
        .with(Sort.by(Sort.Direction.DESC, "createdOn", "id"))
        .skip((long) request.page() * request.size())
        .limit(request.size());
    var items = activities.find(pageQuery, org.springframework.data.domain.Pageable.unpaged());
    int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / request.size());
    return new AdminActivityPage(items, request.page(), request.size(), total, totalPages);
  }

  private void validate(AdminActivityQuery request) throws InvalidRequestException {
    if (request == null || request.page() < 0 || request.size() < 1
        || request.size() > MAX_PAGE_SIZE
        || tooLong(request.action()) || tooLong(request.targetType()) || tooLong(request.actor())) {
      throw new InvalidRequestException("Invalid audit query.");
    }
    if ((request.from() == null) != (request.to() == null)
        || (request.from() != null && request.from().isAfter(request.to()))) {
      throw new InvalidRequestException("Invalid audit date range.");
    }
  }

  private boolean tooLong(String value) {
    return value != null && value.strip().length() > MAX_FILTER_LENGTH;
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
