package dev.christopherbell.configuration.security.browser;

/** Persistence boundary for revocable browser sessions. */
public interface BrowserSessionRepository {
  BrowserSession save(BrowserSession session);
  void delete(BrowserSession session);
  void deleteById(String id);
  long deleteByAccountId(String accountId);
}
