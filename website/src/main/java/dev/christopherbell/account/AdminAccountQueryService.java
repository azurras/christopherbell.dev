package dev.christopherbell.account;

import dev.christopherbell.account.model.Account;
import java.util.ArrayList;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Executes bounded, private-field-safe account searches for administrators. */
@RequiredArgsConstructor
@Service
public class AdminAccountQueryService {
  private final MongoTemplate mongoTemplate;
  private final AccountMapper accountMapper;

  /** Returns the requested account page and total counts. */
  public AdminAccountPage getAccounts(AdminAccountQuery request) {
    var countQuery = new Query(buildCriteria(request));
    var totalElements = mongoTemplate.count(countQuery, Account.class);
    var sort = Sort.by(request.direction(), request.sort())
        .and(Sort.by(request.direction(), "id"));
    var pageQuery = new Query(buildCriteria(request))
        .with(sort)
        .skip((long) request.page() * request.size())
        .limit(request.size());
    var items = mongoTemplate.find(pageQuery, Account.class).stream()
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
