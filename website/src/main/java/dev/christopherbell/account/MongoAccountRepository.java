package dev.christopherbell.account;

import dev.christopherbell.configuration.persistence.MongoPersistence;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedRepositorySupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/** Kind-scoped Mongo implementation of the account persistence port. */
@MongoPersistence
@Repository
public class MongoAccountRepository extends KindScopedRepositorySupport<Account>
    implements AccountRepository {
  public MongoAccountRepository(DomainMongoOperationsFactory factory) {
    super(factory, Account.class);
  }

  @Override
  public Account save(Account account) {
    try {
      return saveValue(account);
    } catch (DuplicateKeyException failure) {
      throw new DuplicateKeyException("MongoDB rejected a duplicate account identity", failure);
    }
  }
  @Override public Optional<Account> findById(String id) { return findValueById(id); }
  @Override public boolean existsById(String id) {
    return mongo.exists(Query.query(Criteria.where("id").is(id)));
  }
  @Override public void deleteById(String id) { super.deleteById(id); }
  @Override public Page<Account> findAll(Pageable pageable) { return page(new Query(), pageable); }

  @Override
  public List<Account> findAllById(Iterable<String> ids) {
    var values = new ArrayList<String>();
    ids.forEach(values::add);
    return values.isEmpty() ? List.of() : find(Query.query(Criteria.where("id").in(values)));
  }

  @Override public Optional<Account> findByEmail(String email) {
    return findOne(Query.query(Criteria.where("email").is(email)));
  }
  @Override public Optional<Account> findByEmailIgnoreCase(String email) {
    return findOne(Query.query(Criteria.where("email").regex(exactIgnoreCase(email))));
  }
  @Override public Optional<Account> findByPasswordResetTokenHash(String hash) {
    return findOne(Query.query(Criteria.where("passwordResetTokenHash").is(hash)));
  }
  @Override public Optional<Account> findByUsername(String username) {
    return findOne(Query.query(Criteria.where("username").is(username)));
  }
  @Override public Optional<Account> findByUsernameAndStatus(String username, AccountStatus status) {
    return findOne(Query.query(Criteria.where("username").is(username).and("status").is(status)));
  }
  @Override public Optional<Account> findByUsernameIgnoreCase(String username) {
    return findUnique(Query.query(Criteria.where("username").regex(exactIgnoreCase(username))));
  }
  @Override
  public Optional<Account> findByUsernameIgnoreCaseAndStatusAndFederationEnabledTrue(
      String username, AccountStatus status) {
    return findUnique(Query.query(Criteria.where("username").regex(exactIgnoreCase(username))
        .and("status").is(status).and("federationEnabled").is(true)));
  }
  @Override public long countByStatus(AccountStatus status) {
    return mongo.count(Query.query(Criteria.where("status").is(status)));
  }
  @Override public Page<Account> findByStatus(AccountStatus status, Pageable pageable) {
    return page(Query.query(Criteria.where("status").is(status)), pageable);
  }
  @Override
  public List<Account> findByUsernameStartingWithIgnoreCaseAndStatusOrderByUsernameAsc(
      String prefix, AccountStatus status, Pageable pageable) {
    var query = Query.query(Criteria.where("username").regex(Pattern.compile(
        "^" + Pattern.quote(prefix), Pattern.CASE_INSENSITIVE)).and("status").is(status))
        .with(Sort.by(Sort.Direction.ASC, "username"));
    return find(query, pageable);
  }
  @Override
  public List<Account> findByIdInAndStatusAndFederationEnabledTrueOrderByUsernameAsc(
      Collection<String> ids, AccountStatus status, Pageable pageable) {
    if (ids.isEmpty()) return List.of();
    var query = Query.query(Criteria.where("id").in(ids).and("status").is(status)
        .and("federationEnabled").is(true))
        .with(Sort.by(Sort.Direction.ASC, "username"));
    return find(query, pageable);
  }

  private static Pattern exactIgnoreCase(String value) {
    return Pattern.compile("^" + Pattern.quote(value) + "$", Pattern.CASE_INSENSITIVE);
  }

  private Optional<Account> findUnique(Query query) {
    query.limit(2);
    var matches = find(query);
    if (matches.size() > 1) {
      throw new IncorrectResultSizeDataAccessException(1);
    }
    return matches.stream().findFirst();
  }
}
