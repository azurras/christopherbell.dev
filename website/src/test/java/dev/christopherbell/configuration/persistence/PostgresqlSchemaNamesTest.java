package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.hibernate.boot.model.naming.Identifier;
import java.util.HashMap;

class PostgresqlSchemaNamesTest {
  @Test
  void productionUsesCanonicalSchemas() {
    var schemas = PostgresqlSchemaNames.production();

    assertThat(schemas.schema("identity")).isEqualTo("identity");
    assertThat(schemas.qualifiedTable("identity", "account"))
        .isEqualTo("\"identity\".\"account\"");
  }

  @Test
  void testsUseOnlyAnOwnedPrefix() {
    var schemas = PostgresqlSchemaNames.testOwned("cbtest_spring_data_");

    assertThat(schemas.schema("identity")).isEqualTo("cbtest_spring_data_identity");
    assertThat(schemas.qualifiedTable("social", "post"))
        .isEqualTo("\"cbtest_spring_data_social\".\"post\"");
  }

  @Test
  void invalidPrefixesAndIdentifiersFailClosed() {
    assertThatThrownBy(() -> PostgresqlSchemaNames.testOwned("public_"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PostgresqlSchemaNames.testOwned("cbtest_bad-name_"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PostgresqlSchemaNames.production().schema("unknown"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PostgresqlSchemaNames.production()
        .qualifiedTable("identity", "account; drop table account"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hibernateMapsOnlySchemaNames() {
    var strategy = new PostgresqlPhysicalNamingStrategy(
        PostgresqlSchemaNames.testOwned("cbtest_spring_data_"));

    assertThat(strategy.toPhysicalSchemaName(Identifier.toIdentifier("identity"), null).getText())
        .isEqualTo("cbtest_spring_data_identity");
    assertThat(strategy.toPhysicalTableName(Identifier.toIdentifier("account"), null).getText())
        .isEqualTo("account");
    assertThat(strategy.toPhysicalColumnName(Identifier.toIdentifier("account_id"), null).getText())
        .isEqualTo("account_id");
  }

  @Test
  void hibernateUsesFlywaySchemasWithoutGeneratingDdl() {
    var configuration = new PostgresqlDataAccessConfiguration();
    var properties = new HashMap<String, Object>();

    configuration.postgresqlHibernateProperties(
        PostgresqlSchemaNames.testOwned("cbtest_spring_data_")).customize(properties);

    assertThat(properties).containsEntry("hibernate.hbm2ddl.auto", "none");
    assertThat(properties.get("hibernate.physical_naming_strategy"))
        .isInstanceOf(PostgresqlPhysicalNamingStrategy.class);
  }
}
