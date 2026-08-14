package dev.christopherbell.account;

import static dev.christopherbell.persistence.jooq.identity.Tables.ACCOUNT;

import dev.christopherbell.configuration.persistence.PostgresPersistence;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.impl.DSL;
import org.springframework.data.domain.Sort;

/** PostgreSQL administrative account search with bounded literal filters. */
@PostgresPersistence
public class PostgresAdminAccountQueryService implements AdminAccountQueryPort {
  private final DSLContext database;
  private final AccountMapper mapper;

  public PostgresAdminAccountQueryService(DSLContext database, AccountMapper mapper) {
    this.database = database;
    this.mapper = mapper;
  }

  @Override
  public AdminAccountPage getAccounts(AdminAccountQuery request) {
    var condition = condition(request);
    long total = database.fetchCount(ACCOUNT, condition);
    var records = database.selectFrom(ACCOUNT)
        .where(condition)
        .orderBy(order(request))
        .limit(request.size())
        .offset(Math.multiplyExact(request.page(), request.size()))
        .fetch();
    var items = PostgresAccountRepository.mapAll(database, records).stream()
        .map(mapper::toAccount).toList();
    int pages = total == 0 ? 0 : Math.toIntExact(((total - 1) / request.size()) + 1);
    return new AdminAccountPage(items, request.page(), request.size(), total, pages,
        request.sort(), request.direction().name());
  }

  private static Condition condition(AdminAccountQuery request) {
    var condition = DSL.noCondition();
    if (request.status() != null) condition = condition.and(ACCOUNT.STATUS.eq(request.status().name()));
    if (request.role() != null) condition = condition.and(ACCOUNT.ROLE.eq(request.role().name()));
    if (request.text() != null) {
      var pattern = "%" + escapeLike(request.text().toLowerCase(java.util.Locale.ROOT)) + "%";
      condition = condition.and(DSL.lower(ACCOUNT.USERNAME).like(pattern, '\\')
          .or(DSL.lower(ACCOUNT.EMAIL).like(pattern, '\\'))
          .or(DSL.lower(ACCOUNT.FIRST_NAME).like(pattern, '\\'))
          .or(DSL.lower(ACCOUNT.LAST_NAME).like(pattern, '\\')));
    }
    return condition;
  }

  private static List<SortField<?>> order(AdminAccountQuery request) {
    Field<?> field = switch (request.sort()) {
      case "createdOn" -> ACCOUNT.CREATED_ON;
      case "lastUpdatedOn" -> ACCOUNT.LAST_UPDATED_ON;
      case "lastLoginOn" -> ACCOUNT.LAST_LOGIN_ON;
      case "username" -> ACCOUNT.USERNAME;
      case "email" -> ACCOUNT.EMAIL;
      case "status" -> ACCOUNT.STATUS;
      case "role" -> ACCOUNT.ROLE;
      default -> throw new IllegalArgumentException("Unsupported account sort field.");
    };
    var result = new ArrayList<SortField<?>>();
    result.add(request.direction() == Sort.Direction.ASC ? field.asc() : field.desc());
    result.add(request.direction() == Sort.Direction.ASC
        ? ACCOUNT.ACCOUNT_ID.asc() : ACCOUNT.ACCOUNT_ID.desc());
    return List.copyOf(result);
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
