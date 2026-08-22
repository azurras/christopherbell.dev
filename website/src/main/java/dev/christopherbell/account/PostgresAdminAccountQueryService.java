package dev.christopherbell.account;

import dev.christopherbell.configuration.persistence.PostgresPersistence;

/** PostgreSQL administrative account search with bounded literal filters. */
@PostgresPersistence
public class PostgresAdminAccountQueryService implements AdminAccountQueryPort {
  private final PostgresAccountRepository accounts;
  private final AccountMapper mapper;

  public PostgresAdminAccountQueryService(
      PostgresAccountRepository accounts, AccountMapper mapper) {
    this.accounts = accounts;
    this.mapper = mapper;
  }

  @Override
  public AdminAccountPage getAccounts(AdminAccountQuery request) {
    var page = accounts.findAdminPage(request);
    return new AdminAccountPage(
        page.getContent().stream().map(mapper::toAccount).toList(),
        request.page(), request.size(), page.getTotalElements(), page.getTotalPages(),
        request.sort(), request.direction().name());
  }
}
