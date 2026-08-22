package dev.christopherbell.configuration.security.browser;

import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

interface BrowserSessionJpaRepository extends CrudRepository<BrowserSessionEntity, String> {
  @Transactional
  long deleteByAccountId(String accountId);
}
