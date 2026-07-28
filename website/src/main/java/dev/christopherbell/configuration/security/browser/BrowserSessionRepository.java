package dev.christopherbell.configuration.security.browser;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence boundary for revocable browser sessions. */
public interface BrowserSessionRepository extends MongoRepository<BrowserSession, String> {
  long deleteByAccountId(String accountId);
}
