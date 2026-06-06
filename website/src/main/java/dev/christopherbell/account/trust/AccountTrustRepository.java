package dev.christopherbell.account.trust;

import dev.christopherbell.account.trust.model.AccountTrustType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence boundary for account mute and block relationships. */
public interface AccountTrustRepository extends MongoRepository<AccountTrustRelationship, String> {
  Optional<AccountTrustRelationship> findByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId,
      String targetAccountId,
      AccountTrustType type);

  List<AccountTrustRelationship> findByOwnerAccountIdAndTypeIn(
      String ownerAccountId,
      Collection<AccountTrustType> types);

  boolean existsByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId,
      String targetAccountId,
      AccountTrustType type);

  void deleteByOwnerAccountIdAndTargetAccountIdAndType(
      String ownerAccountId,
      String targetAccountId,
      AccountTrustType type);
}
