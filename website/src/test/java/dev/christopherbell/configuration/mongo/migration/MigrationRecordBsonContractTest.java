package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class MigrationRecordBsonContractTest {
  private static final String TYPE =
      "dev.christopherbell.configuration.mongo.migration.MigrationRecord";

  @Test
  void appliedV014HasTheExactProductionConverterShape() throws Exception {
    var record = new MigrationRecord();
    record.setId("014-consolidate-music-runtime-state");
    record.setChecksum("11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb");
    record.setDescription("Consolidate Music queue and radio runtime state");
    record.setStatus(MigrationStatus.APPLIED);
    record.setOwnerToken("v014-owner");
    record.setStartedAt(Instant.parse("2026-08-10T00:00:00Z"));
    record.setCompletedAt(Instant.parse("2026-08-10T00:01:00Z"));

    var stored = new Document();
    converter().write(record, stored);

    assertThat(List.copyOf(stored.keySet())).containsExactly(
        "_id", "checksum", "description", "status", "ownerToken", "startedAt",
        "completedAt", "_class");
    assertThat(stored).containsEntry("_id", record.getId())
        .containsEntry("checksum", record.getChecksum())
        .containsEntry("description", record.getDescription())
        .containsEntry("status", "APPLIED")
        .containsEntry("ownerToken", record.getOwnerToken())
        .containsEntry("startedAt", Date.from(record.getStartedAt()))
        .containsEntry("completedAt", Date.from(record.getCompletedAt()))
        .containsEntry("_class", TYPE);
  }

  private static MappingMongoConverter converter() throws Exception {
    var context = new MongoMappingContext();
    context.setInitialEntitySet(Set.of(MigrationRecord.class));
    context.setSimpleTypeHolder(MongoCustomConversions.create(adapter -> {})
        .getSimpleTypeHolder());
    context.afterPropertiesSet();
    var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.afterPropertiesSet();
    return converter;
  }
}
