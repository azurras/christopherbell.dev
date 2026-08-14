package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;

class CanonicalMigrationHasherTest {
  @Test
  void mapOrderAndEquivalentJsonNumbersProduceOneDigest() {
    var first = new LinkedHashMap<String, Object>();
    first.put("z", new BigDecimal("1.000"));
    first.put("a", List.of("é", 2L, true));
    var second = new LinkedHashMap<String, Object>();
    second.put("a", List.of("é", 2, true));
    second.put("z", 1);

    assertThat(CanonicalMigrationHasher.sha256(first))
        .isEqualTo(CanonicalMigrationHasher.sha256(second))
        .matches("[0-9a-f]{64}");
  }

  @Test
  void missingNullArrayOrderAndBinaryValuesRemainDistinct() {
    assertThat(CanonicalMigrationHasher.sha256(Map.of("value", "null")))
        .isNotEqualTo(CanonicalMigrationHasher.sha256(singleNullValue()));
    assertThat(CanonicalMigrationHasher.sha256(Map.of()))
        .isNotEqualTo(CanonicalMigrationHasher.sha256(singleNullValue()));
    assertThat(CanonicalMigrationHasher.sha256(Map.of("value", List.of("a", "b"))))
        .isNotEqualTo(CanonicalMigrationHasher.sha256(Map.of("value", List.of("b", "a"))));
    assertThat(CanonicalMigrationHasher.sha256(Map.of("value", new byte[] {0, 1, -1})))
        .isNotEqualTo(CanonicalMigrationHasher.sha256(Map.of("value", "AAH/")));
  }

  @Test
  void bsonBinaryAndItsByteArrayRepresentationHaveOneCanonicalDigest() {
    var bytes = new byte[] {0, 1, -1, 42};

    assertThat(CanonicalMigrationHasher.sha256(Map.of("value", new Binary(bytes))))
        .isEqualTo(CanonicalMigrationHasher.sha256(Map.of("value", bytes)));
  }

  @Test
  void supportsUtcInstantsAndRejectsNonFiniteOrUnsupportedValues() {
    assertThat(CanonicalMigrationHasher.sha256(
        Map.of("when", Instant.parse("2026-08-14T00:00:00Z"))))
        .matches("[0-9a-f]{64}");
    assertThatThrownBy(() -> CanonicalMigrationHasher.sha256(Map.of("value", Double.NaN)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration canonical value is invalid.");
    assertThatThrownBy(() -> CanonicalMigrationHasher.sha256(Map.of("value", new Object())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration canonical value is invalid.");
  }

  @Test
  void hashesAstralUnicodeAsItsUtf8CodePointAndEscapesUnpairedSurrogates() {
    assertThat(CanonicalMigrationHasher.sha256("🛰"))
        .isEqualTo("9492948d4a8fabd491d7b9c1ac48bf7525dae351eb1ca3640d8d426c1c81bce8");
    assertThat(CanonicalMigrationHasher.sha256("\ud83d"))
        .isNotEqualTo(CanonicalMigrationHasher.sha256("🛰"));
  }

  private static Map<String, Object> singleNullValue() {
    var value = new LinkedHashMap<String, Object>();
    value.put("value", null);
    return value;
  }
}
