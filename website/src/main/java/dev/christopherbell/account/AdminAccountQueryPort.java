package dev.christopherbell.account;

/** Persistence-neutral administrative account query boundary. */
public interface AdminAccountQueryPort {
  AdminAccountPage getAccounts(AdminAccountQuery request);
}
