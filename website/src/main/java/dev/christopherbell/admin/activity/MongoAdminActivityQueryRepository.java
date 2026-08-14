package dev.christopherbell.admin.activity;

import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Mongo implementation of bounded stable admin activity queries. */
@MongoPersistence
public class MongoAdminActivityQueryRepository implements AdminActivityQueryPort {
  private final KindScopedMongoOperations<AdminActivity> activities;

  public MongoAdminActivityQueryRepository(DomainMongoOperationsFactory factory) {
    this.activities = factory.forType(AdminActivity.class);
  }

  @Override
  public AdminActivityPage query(AdminActivityQuery request) throws InvalidRequestException {
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
    var items = activities.find(pageQuery, Pageable.unpaged());
    int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / request.size());
    return new AdminActivityPage(items, request.page(), request.size(), total, totalPages);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
