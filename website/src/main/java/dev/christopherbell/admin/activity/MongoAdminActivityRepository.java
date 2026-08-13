package dev.christopherbell.admin.activity;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.admin.model.AdminActivity;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@MongoPersistence
@Repository
class MongoAdminActivityRepository extends KindScopedRepositorySupport<AdminActivity>
    implements AdminActivityRepository {
  MongoAdminActivityRepository(DomainMongoOperationsFactory factory) {
    super(factory, AdminActivity.class);
  }
  @Override public AdminActivity insert(AdminActivity value) { return insertValue(value); }
  @Override public AdminActivity save(AdminActivity value) { return saveValue(value); }
  @Override public Optional<AdminActivity> findById(String id) { return findValueById(id); }
  @Override public List<AdminActivity> findTop25ByOrderByCreatedOnDesc() {
    return find(new Query().with(Sort.by(Sort.Direction.DESC, "createdOn")), PageRequest.of(0, 25));
  }
}
