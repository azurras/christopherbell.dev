package dev.christopherbell.configuration.security.browser;

import dev.christopherbell.configuration.persistence.PostgresPersistence;

/** PostgreSQL persistence for revocable browser sessions. */
@PostgresPersistence
public class PostgresBrowserSessionRepository implements BrowserSessionRepository {
  private final BrowserSessionJpaRepository sessions;

  public PostgresBrowserSessionRepository(BrowserSessionJpaRepository sessions) {
    this.sessions = sessions;
  }

  @Override
  public BrowserSession save(BrowserSession session) {
    var entity = sessions.findById(session.getId())
        .map(existing -> {
          existing.apply(session);
          return existing;
        })
        .orElseGet(() -> BrowserSessionEntity.create(session));
    return sessions.save(entity).toDomain();
  }

  @Override
  public void delete(BrowserSession session) {
    deleteById(session.getId());
  }

  @Override
  public void deleteById(String id) {
    sessions.deleteById(id);
  }

  @Override
  public long deleteByAccountId(String accountId) {
    return sessions.deleteByAccountId(accountId);
  }
}
