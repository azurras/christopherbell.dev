package dev.christopherbell.configuration.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresqlTestSchemaNameTest {

  @Test
  void createsDistinctDisposableSchemaNamesWithTheConfiguredPrefix() {
    var first = PostgresqlTestSchemaName.create("cbtest_");
    var second = PostgresqlTestSchemaName.create("cbtest_");

    assertThat(first.value()).startsWith("cbtest_");
    assertThat(second.value()).startsWith("cbtest_");
    assertThat(first).isNotEqualTo(second);
  }
}
