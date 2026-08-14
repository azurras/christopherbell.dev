package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.christopherbell.post.like.PostgresPostLikeStore;
import dev.christopherbell.sharedfolder.maintenance.PostgresSharedFolderMaintenanceLeaseStore;
import dev.christopherbell.sharedfolder.maintenance.SharedFolderMaintenanceLeaseStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PostgresBackendComponentContextTest {

  @Test
  void postgresqlBackendProxiesTaskThreeAndTaskFourRepositoryAdapters() {
    try (var context = contextFor("postgresql")) {
      var taskThreeAdapter = context.getBean(PostgresPostLikeStore.class);
      var taskFourAdapter = context.getBean(SharedFolderMaintenanceLeaseStore.class);

      assertThat(AopUtils.isAopProxy(taskThreeAdapter)).isTrue();
      assertThat(AopUtils.getTargetClass(taskThreeAdapter)).isEqualTo(PostgresPostLikeStore.class);
      assertThat(AopUtils.isAopProxy(taskFourAdapter)).isTrue();
      assertThat(AopUtils.getTargetClass(taskFourAdapter))
          .isEqualTo(PostgresSharedFolderMaintenanceLeaseStore.class);
    }
  }

  @Test
  void mongodbBackendExcludesPostgresqlRepositoryAdapters() {
    try (var context = contextFor("mongodb")) {
      assertThat(context.getBeansOfType(PostgresPostLikeStore.class)).isEmpty();
      assertThat(context.getBeansOfType(SharedFolderMaintenanceLeaseStore.class)).isEmpty();
    }
  }

  private static AnnotationConfigApplicationContext contextFor(String backend) {
    var context = new AnnotationConfigApplicationContext();
    TestPropertyValues.of("app.persistence.backend=" + backend).applyTo(context);
    context.registerBean(DSLContext.class, () -> mock(DSLContext.class));
    context.registerBean(
        PersistenceExceptionTranslator.class, () -> mock(PersistenceExceptionTranslator.class));
    context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
      var processor = new PersistenceExceptionTranslationPostProcessor();
      processor.setProxyTargetClass(true);
      return processor;
    });
    context.register(
        PostgresPostLikeStore.class,
        PostgresSharedFolderMaintenanceLeaseStore.class);
    context.refresh();
    return context;
  }
}
