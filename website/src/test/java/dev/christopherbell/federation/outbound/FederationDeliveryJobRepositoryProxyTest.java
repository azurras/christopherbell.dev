package dev.christopherbell.federation.outbound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.MongoTemplate;

class FederationDeliveryJobRepositoryProxyTest {
  @Test
  void repositoryCanBeProxiedForPersistenceExceptionTranslation() {
    assertDoesNotThrow(() -> {
      try (var context = new AnnotationConfigApplicationContext()) {
        context.registerBean(MongoTemplate.class, () -> mock(MongoTemplate.class));
        context.registerBean(
            PersistenceExceptionTranslationPostProcessor.class,
            () -> {
              var processor = new PersistenceExceptionTranslationPostProcessor();
              processor.setProxyTargetClass(true);
              return processor;
            });
        context.registerBean(
            FederationDeliveryJobRepository.class,
            () -> new FederationDeliveryJobRepository(context.getBean(MongoTemplate.class)));
        context.refresh();
        context.getBean(FederationDeliveryJobRepository.class);
      }
    });
  }
}
