package dev.christopherbell.account.deletion;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class MongoAccountDeletionJobRepository
    extends KindScopedRepositorySupport<AccountDeletionJob>
    implements AccountDeletionJobRepository {
  MongoAccountDeletionJobRepository(DomainMongoOperationsFactory factory) {
    super(factory, AccountDeletionJob.class);
  }

  @Override public Optional<AccountDeletionJob> findById(String id) { return findValueById(id); }
  @Override public AccountDeletionJob save(AccountDeletionJob job) { return saveValue(job); }
}
