package dev.christopherbell.configuration.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Shared PostgreSQL identifier configuration for JPA and native Spring JDBC adapters. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.persistence", name = "backend", havingValue = "postgresql")
public class PostgresqlDataAccessConfiguration {
  @Bean
  PostgresqlSchemaNames postgresqlSchemaNames(
      @Value("${app.persistence.schema-prefix:}") String schemaPrefix) {
    return schemaPrefix == null || schemaPrefix.isBlank()
        ? PostgresqlSchemaNames.production()
        : PostgresqlSchemaNames.testOwned(schemaPrefix);
  }

  @Bean
  HibernatePropertiesCustomizer postgresqlHibernateProperties(PostgresqlSchemaNames schemas) {
    return properties -> {
      properties.put("hibernate.hbm2ddl.auto", "none");
      properties.put("hibernate.physical_naming_strategy",
          new PostgresqlPhysicalNamingStrategy(schemas));
    };
  }
}
