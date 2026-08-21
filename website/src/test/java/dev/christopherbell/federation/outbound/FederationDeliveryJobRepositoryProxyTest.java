package dev.christopherbell.federation.outbound;

import dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsFactory;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;

class FederationDeliveryJobRepositoryProxyTest {
  @Test
  void repositoryCanBeProxiedForPersistenceExceptionTranslation() {
    assertDoesNotThrow(() -> {
      try (var context = new AnnotationConfigApplicationContext()) {
        var factory = mock(DomainMongoOperationsFactory.class);
        context.registerBean(
            PersistenceExceptionTranslationPostProcessor.class,
            () -> {
              var processor = new PersistenceExceptionTranslationPostProcessor();
              processor.setProxyTargetClass(true);
              return processor;
            });
        context.registerBean(
            FederationDeliveryStore.class,
            () -> new FederationDeliveryJobRepository(factory));
        context.refresh();
        context.getBean(FederationDeliveryStore.class);
      }
    });
  }
}
