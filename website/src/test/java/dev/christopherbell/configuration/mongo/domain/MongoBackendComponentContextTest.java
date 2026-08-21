package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import dev.christopherbell.sharedfolder.maintenance.MongoSharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import dev.christopherbell.configuration.mongo.runtime.MongoLeaseConfiguration;
import dev.christopherbell.libs.mongo.lease.MongoLeaseService;
import dev.christopherbell.libs.mongo.lease.MongoLeaseStore;
import dev.christopherbell.music.radio.MusicRuntimeStateMigrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MongoBackendComponentContextTest {

  @Test
  void mongodbBackendKeepsInfrastructureUnproxiedAndRepositoryAdaptersTranslated() {
    try (var context = contextFor("mongodb")) {
      var factory = context.getBean(
          "domainMongoOperationsFactory", DomainMongoOperationsFactory.class);
      var leaseStore = context.getBean(SharedFolderMaintenanceLeaseStore.class);
      var migrationSupport = context.getBean(MusicRuntimeStateMigrationSupport.class);

      assertThat(AopUtils.isAopProxy(factory)).isFalse();
      assertThat(AopUtils.isAopProxy(migrationSupport)).isFalse();
      assertThat(context.getBean(MongoLeaseService.class)).isNotNull();
      assertThat(AopUtils.isAopProxy(leaseStore)).isTrue();
      assertThat(AopUtils.getTargetClass(leaseStore))
          .isEqualTo(MongoSharedFolderMaintenanceLeaseStore.class);
    }
  }

  @Test
  void postgresqlBackendExcludesMongoInfrastructureAndAdapters() {
    try (var context = contextFor("postgresql")) {
      assertThat(context.containsBean("domainMongoOperationsFactory")).isFalse();
      assertThat(context.getBeansOfType(SharedFolderMaintenanceLeaseStore.class)).isEmpty();
      assertThat(context.getBeansOfType(MusicRuntimeStateMigrationSupport.class)).isEmpty();
      assertThat(context.getBeansOfType(MongoLeaseService.class)).isEmpty();
    }
  }

  private static AnnotationConfigApplicationContext contextFor(String backend) {
    var context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of("app.persistence.backend=" + backend).applyTo(context);
    context.registerBean(MongoTemplate.class, () -> {
      var mongo = mock(MongoTemplate.class);
      org.mockito.Mockito.when(mongo.getConverter()).thenReturn(mock(MongoConverter.class));
      return mongo;
    });
    context.registerBean(MongoLeaseStore.class, () -> mock(MongoLeaseStore.class));
    if (backend.equals("mongodb")) {
      var adapterFactory = mock(DomainMongoOperationsFactory.class);
      doReturn(mock(KindScopedMongoOperations.class)).when(adapterFactory).forType(any());
      context.registerBean(
          "adapterFactory",
          DomainMongoOperationsFactory.class,
          () -> adapterFactory,
          definition -> definition.setPrimary(true));
    }
    context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
      var processor = new PersistenceExceptionTranslationPostProcessor();
      processor.setProxyTargetClass(true);
      return processor;
    });
    context.register(
        DomainMongoOperationsFactory.class,
        MongoSharedFolderMaintenanceLeaseStore.class,
        MusicRuntimeStateMigrationSupport.class,
        MongoLeaseConfiguration.class);
    context.refresh();
    return context;
  }
}
