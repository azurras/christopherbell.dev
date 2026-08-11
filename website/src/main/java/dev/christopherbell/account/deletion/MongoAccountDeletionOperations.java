package dev.christopherbell.account.deletion;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.configuration.mongo.domain.DomainAccountDeletionStore;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Concrete idempotent Mongo effects for comprehensive account deletion. */
@Component
public final class MongoAccountDeletionOperations implements AccountDeletionOperations {
  private static final String TOMBSTONE = AccountDeletionService.TOMBSTONE_ID;

  private final KindScopedMongoOperations<Account> accounts;
  private final DomainAccountDeletionStore domainData;
  private final AccountDeletionResourceCleaner resources;

  public MongoAccountDeletionOperations(
      DomainMongoOperationsFactory factory, AccountDeletionResourceCleaner resources) {
    this.accounts = factory.forType(Account.class);
    this.domainData = new DomainAccountDeletionStore(factory);
    this.resources = resources;
  }

  @Override
  public boolean accountExists(String accountId) {
    return accounts.exists(exactId(accountId));
  }

  @Override
  public void ensureTombstone() {
    if (accounts.findById(TOMBSTONE).isPresent()) {
      return;
    }
    try {
      accounts.insert(Account.builder().id(TOMBSTONE).username(TOMBSTONE)
          .email("deleted-user@invalid.local").firstName("Deleted").lastName("User")
          .role(Role.USER).status(AccountStatus.INACTIVE).permissions(Set.of()).build());
    } catch (DuplicateKeyException ignored) {
      // A concurrent deletion created the same fixed tombstone.
    }
  }

  @Override
  public void anonymizePublicPosts(String accountId, String pseudonym) {
    domainData.anonymizePublicPosts(accountId, pseudonym, TOMBSTONE);
  }

  @Override
  public void removePrivateData(String accountId) {
    domainData.removePrivateData(accountId);
  }

  @Override
  public void cleanSharedFolderState(String accountId) {
    resources.deleteOwnedResources(accountId);
  }

  @Override
  public void pseudonymizeRetainedRecords(String accountId, String pseudonym) {
    domainData.pseudonymizeRetainedRecords(accountId, pseudonym, TOMBSTONE);
  }

  @Override
  public void removeReferencesAndAccount(String accountId) {
    accounts.remove(exactId(accountId));
  }

  private static Query exactId(String accountId) {
    return Query.query(Criteria.where("id").is(accountId));
  }
}
