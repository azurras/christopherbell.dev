package dev.christopherbell.account.auth;

/** Revokes browser credentials after a durable account-security state change. */
@FunctionalInterface
public interface AccountSessionRevoker {
  void revokeAll(String accountId);
}
