package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import dev.christopherbell.configuration.mongo.domain.KindScopedMongoOperations;
import dev.christopherbell.configuration.persistence.MongoPersistence;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** Executes bounded, private-field-safe account searches for administrators. */
@MongoPersistence
public class AdminAccountQueryService implements AdminAccountQueryPort {
  private final KindScopedMongoOperations<Account> accounts;
  private final AccountMapper accountMapper;

  public AdminAccountQueryService(
      DomainMongoOperationsFactory factory, AccountMapper accountMapper) {
    this.accounts = factory.forType(Account.class);
    this.accountMapper = accountMapper;
  }

  /** Returns the requested account page and total counts. */
  @Override
  public AdminAccountPage getAccounts(AdminAccountQuery request) {
    var countQuery = new Query(buildCriteria(request));
    var totalElements = accounts.count(countQuery);
    var sort = Sort.by(request.direction(), request.sort())
        .and(Sort.by(request.direction(), "id"));
    var pageQuery = new Query(buildCriteria(request))
        .with(sort)
        .skip((long) request.page() * request.size())
        .limit(request.size());
    var items = accounts.find(pageQuery, org.springframework.data.domain.Pageable.unpaged()).stream()
        .map(accountMapper::toAccount)
        .toList();
    var totalPages = totalElements == 0
        ? 0
        : (int) Math.min(Integer.MAX_VALUE, ((totalElements - 1) / request.size()) + 1);
    return new AdminAccountPage(
        items,
        request.page(),
        request.size(),
        totalElements,
        totalPages,
        request.sort(),
        request.direction().name());
  }

  private Criteria buildCriteria(AdminAccountQuery request) {
    var criteria = new ArrayList<Criteria>();
    if (request.status() != null) {
      criteria.add(Criteria.where("status").is(request.status()));
    }
    if (request.role() != null) {
      criteria.add(Criteria.where("role").is(request.role()));
    }
    if (request.text() != null) {
      var literalSearch = Pattern.compile(Pattern.quote(request.text()), Pattern.CASE_INSENSITIVE);
      criteria.add(new Criteria().orOperator(
          Criteria.where("username").regex(literalSearch),
          Criteria.where("email").regex(literalSearch),
          Criteria.where("firstName").regex(literalSearch),
          Criteria.where("lastName").regex(literalSearch)));
    }
    if (criteria.isEmpty()) {
      return new Criteria();
    }
    return criteria.size() == 1
        ? criteria.get(0)
        : new Criteria().andOperator(criteria);
  }
}
