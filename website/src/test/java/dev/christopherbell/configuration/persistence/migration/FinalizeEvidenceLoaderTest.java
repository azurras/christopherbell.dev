package dev.christopherbell.configuration.persistence.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FinalizeEvidenceLoaderTest {
  private static final String KEY = "independent-test-authority-key-00000001";

  @Test
  void verifiesPersistedAuthorityAndRejectsTampering(@TempDir Path directory) throws Exception {
    var evidence = evidence();
    var properties = properties(evidence);
    properties.setProperty("signature", signature(evidence.evidenceDigest()));
    var path = directory.resolve("finalize.properties");
    try (var output = Files.newOutputStream(path)) {
      properties.store(output, null);
    }

    assertThat(FinalizeEvidenceLoader.load(path, KEY)).isEqualTo(evidence);

    properties.setProperty("sourceDigest", "f".repeat(64));
    try (var output = Files.newOutputStream(path)) {
      properties.store(output, null);
    }
    assertThatThrownBy(() -> FinalizeEvidenceLoader.load(path, KEY))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("PostgreSQL migration finalization evidence is invalid.")
        .hasMessageNotContaining("f".repeat(64));
  }

  private static FrozenSourceEvidence evidence() {
    var unsigned = new FrozenSourceEvidence(
        "release-6", "a".repeat(64), "test", "test", "b".repeat(64), "c".repeat(64),
        UUID.fromString("00000000-0000-0000-0000-000000000016"),
        "mongodb://127.0.0.1:57018/test", "jdbc:postgresql://127.0.0.1:55432/test",
        "christopherbell_test", "d".repeat(64), "e".repeat(64));
    return new FrozenSourceEvidence(
        unsigned.release(), unsigned.catalogDigest(), unsigned.sourceDatabase(),
        unsigned.targetDatabase(), unsigned.sourceDigest(), unsigned.backupDigest(),
        unsigned.lockToken(), unsigned.sourceUri(), unsigned.targetJdbcUrl(), unsigned.targetRole(),
        unsigned.writerLockDigest(), unsigned.reconstructedDigest());
  }

  private static Properties properties(FrozenSourceEvidence evidence) {
    var result = new Properties();
    result.setProperty("release", evidence.release());
    result.setProperty("catalogDigest", evidence.catalogDigest());
    result.setProperty("sourceDatabase", evidence.sourceDatabase());
    result.setProperty("targetDatabase", evidence.targetDatabase());
    result.setProperty("sourceDigest", evidence.sourceDigest());
    result.setProperty("backupDigest", evidence.backupDigest());
    result.setProperty("lockToken", evidence.lockToken().toString());
    result.setProperty("sourceUri", evidence.sourceUri());
    result.setProperty("targetJdbcUrl", evidence.targetJdbcUrl());
    result.setProperty("targetRole", evidence.targetRole());
    result.setProperty("writerLockDigest", evidence.writerLockDigest());
    result.setProperty("evidenceDigest", evidence.evidenceDigest());
    return result;
  }

  private static String signature(String evidenceDigest) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(evidenceDigest.getBytes(StandardCharsets.US_ASCII)));
  }
}
