package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class PostgresqlMigrationCommandTest {
  @Test
  void parserAcceptsOnlyTheFourClosedLowercaseOperations() {
    assertThat(PostgresqlMigrationCommand.parse("shadow"))
        .isEqualTo(PostgresqlMigrationCommand.SHADOW);
    assertThat(PostgresqlMigrationCommand.parse("finalize"))
        .isEqualTo(PostgresqlMigrationCommand.FINALIZE);
    assertThat(PostgresqlMigrationCommand.parse("reconcile"))
        .isEqualTo(PostgresqlMigrationCommand.RECONCILE);
    assertThat(PostgresqlMigrationCommand.parse("status"))
        .isEqualTo(PostgresqlMigrationCommand.STATUS);

    assertThatThrownBy(() -> PostgresqlMigrationCommand.parse("SHADOW"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration command is invalid.");
    assertThatThrownBy(() -> PostgresqlMigrationCommand.parse("publish"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration command is invalid.");
    assertThatThrownBy(() -> PostgresqlMigrationCommand.parse(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration command is invalid.");
  }

  @Test
  void commandNamesRemainLocaleIndependentAndRedacted() {
    var prior = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(PostgresqlMigrationCommand.FINALIZE.externalName()).isEqualTo("finalize");
      assertThat(PostgresqlMigrationCommand.values())
          .extracting(PostgresqlMigrationCommand::externalName)
          .containsExactly("shadow", "finalize", "reconcile", "status");
    } finally {
      Locale.setDefault(prior);
    }
  }
}
