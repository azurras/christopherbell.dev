package dev.christopherbell.configuration.mongo.runtime;

import dev.christopherbell.configuration.persistence.MongoPersistence;
import dev.christopherbell.libs.mongo.lease.MongoLeaseService;
import dev.christopherbell.libs.mongo.lease.MongoLeaseStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Transition-only wiring for migration callers that still use the legacy Mongo lease facade. */
@MongoPersistence
@Configuration(proxyBeanMethods = false)
public class MongoLeaseConfiguration {
  @Bean
  MongoLeaseService mongoLeaseService(MongoLeaseStore store) {
    return new MongoLeaseService(store);
  }
}
