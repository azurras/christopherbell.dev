package dev.christopherbell.admin.commandcenter.logs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.admin.commandcenter.CommandCenterProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandCenterLogServiceTest {

  @TempDir Path tempDir;

  @Test
  void initialReadReturnsOnlyTheMostRecentConfiguredLines() throws IOException {
    Path log = writeLog("INFO one\nWARN two\nERROR three\nDEBUG four\n");

    var page = service(log, 2, 1_024).read(null, null, null);

    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("ERROR three", "DEBUG four");
    assertThat(page.nextCursor()).isNotBlank();
    assertThat(page.reset()).isFalse();
    assertThat(page.status()).isEqualTo("ok");
  }

  @Test
  void cursorAdvancesAndReturnsOnlyNewRecords() throws IOException {
    Path log = writeLog("INFO first\n");
    var service = service(log, 20, 1_024);
    var first = service.read(null, null, null);
    Files.writeString(log, "WARN second\n", UTF_8, StandardOpenOption.APPEND);

    var second = service.read(first.nextCursor(), null, null);

    assertThat(second.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("WARN second");
    assertThat(second.nextCursor()).isNotEqualTo(first.nextCursor());
    assertThat(second.reset()).isFalse();
  }

  @Test
  void outputIsBoundedByLinesAndUtf8Bytes() throws IOException {
    Path log = writeLog("INFO alpha-alpha\nINFO beta-beta\nINFO gamma-gamma\n");

    var page = service(log, 20, 28).read(null, null, null);

    assertThat(page.records()).isNotEmpty();
    assertThat(page.records()).hasSizeLessThanOrEqualTo(20);
    assertThat(renderedBytes(page)).isLessThanOrEqualTo(28);
    assertThat(page.records().getLast().text()).isEqualTo("INFO gamma-gamma");
  }

  @Test
  void queryIsLiteralAndAppliedAfterRedaction() throws IOException {
    Path log = writeLog(
        "INFO value a.b\nINFO value axb\nINFO token=hidden a.b\n");
    var service = service(log, 20, 1_024);

    var literal = service.read(null, null, "a.b");
    var secret = service.read(null, null, "hidden");

    assertThat(literal.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("INFO value a.b", "INFO token=[REDACTED] a.b");
    assertThat(secret.records()).isEmpty();
  }

  @Test
  void redactsQuotedJsonAndLogfmtSecretsWithoutLeavingBearerValues() throws IOException {
    Path log = writeLog("""
        {"password":"json-secret","token":"token-secret","authorization":"Bearer bearer-secret"}
        password='quoted secret' token=plain-secret authorization=Bearer logfmt-secret safe=value
        """);

    var page = service(log, 20, 2_048).read(null, null, null);

    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .allSatisfy(text -> assertThat(text)
            .doesNotContain("json-secret", "token-secret", "bearer-secret", "quoted secret",
                "plain-secret", "logfmt-secret"))
        .allSatisfy(text -> assertThat(text).contains("[REDACTED]"));
  }

  @Test
  void redactsEscapeAwareStructuredSecretsWithQuotesAndBackslashes() throws IOException {
    Path log = writeLog("""
        {"password":"before\\\"quoted\\\\path-after","token":"tok\\\\en"}
        authorization="Bearer abc\\\"def" password='single\\'quoted'
        """);

    var texts = service(log, 20, 2_048).read(null, null, null).records().stream()
        .map(CommandCenterLogService.LogRecord::text).toList();

    assertThat(texts).allSatisfy(text -> assertThat(text)
        .doesNotContain("before", "quoted", "path-after", "tok\\\\en", "abc", "def", "single"));
    assertThat(texts).allSatisfy(text -> assertThat(text).contains("[REDACTED]"));
  }

  @Test
  void literalQueryIsCaseInsensitive() throws IOException {
    Path log = writeLog("INFO MiXeD-CaSe value\nINFO unrelated\n");

    var page = service(log, 20, 1_024).read(null, null, "mixed-CASE");

    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("INFO MiXeD-CaSe value");
  }

  @Test
  void filtersOnlyBySupportedSeverity() throws IOException {
    Path log = writeLog("TRACE t\nDEBUG d\nINFO i\nWARN w\nERROR e\nNOTICE n\n");
    var service = service(log, 20, 1_024);

    var page = service.read(null, "warn", null);

    assertThat(page.records()).singleElement().satisfies(record -> {
      assertThat(record.level()).isEqualTo("WARN");
      assertThat(record.text()).isEqualTo("WARN w");
    });
    assertThatThrownBy(() -> service.read(null, "NOTICE", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("level");
  }

  @Test
  void allLevelAppliesNoSeverityFilter() throws IOException {
    Path log = writeLog("TRACE t\nDEBUG d\nINFO i\nWARN w\nERROR e\nNOTICE n\n");

    var page = service(log, 20, 1_024).read(null, "ALL", null);

    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("TRACE t", "DEBUG d", "INFO i", "WARN w", "ERROR e", "NOTICE n");
  }

  @Test
  void invalidCursorResetsToTheCurrentTail() throws IOException {
    Path log = writeLog("INFO current\n");

    var page = service(log, 20, 1_024).read("not-a-valid-cursor", null, null);

    assertThat(page.reset()).isTrue();
    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("INFO current");
  }

  @Test
  void rejectsOversizedQueries() throws IOException {
    var service = service(writeLog("INFO current\n"), 20, 1_024);

    assertThatThrownBy(() -> service.read(null, null, "x".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
  }

  @Test
  void rotationResetsCursorAndCannotRedirectReadsToAnotherFile() throws IOException {
    Path log = writeLog("INFO old\n");
    Path other = tempDir.resolve("other.log");
    Files.writeString(other, "ERROR other-secret\n", UTF_8);
    var primary = service(log, 20, 1_024);
    var cursor = primary.read(null, null, null).nextCursor();
    var otherCursor = service(other, 20, 1_024).read(null, null, null).nextCursor();

    Files.move(log, tempDir.resolve("application.log.1"));
    Files.writeString(log, "WARN rotated\n", UTF_8);

    var rotated = primary.read(cursor, null, null);
    var crafted = primary.read(otherCursor, null, null);

    assertThat(rotated.reset()).isTrue();
    assertThat(rotated.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("WARN rotated");
    assertThat(crafted.reset()).isTrue();
    assertThat(crafted.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("WARN rotated");
    assertThat(crafted.records()).allSatisfy(
        record -> assertThat(record.text()).doesNotContain("other-secret"));
  }

  @Test
  void replacementOfEmptyFileResetsEvenWhenCreationTimeIsReused() throws IOException {
    Path log = writeLog("");
    var service = service(log, 20, 32);
    var cursor = service.read(null, null, null).nextCursor();
    BasicFileAttributes original = Files.readAttributes(log, BasicFileAttributes.class);

    Files.move(log, tempDir.resolve("empty.log.1"));
    Files.write(log, new byte[0]);
    Files.getFileAttributeView(log, BasicFileAttributeView.class)
        .setTimes(null, null, original.creationTime());

    var page = service.read(cursor, null, null);

    assertThat(page.reset()).isTrue();
    assertThat(page.records()).isEmpty();
  }

  @Test
  void replacementWithSameSuffixAndCreationTimeResets() throws IOException {
    String commonSuffix = "S".repeat(160) + "\n";
    Path log = writeLog("INFO " + "A".repeat(160) + commonSuffix);
    var service = service(log, 20, 512);
    var cursor = service.read(null, null, null).nextCursor();
    BasicFileAttributes original = Files.readAttributes(log, BasicFileAttributes.class);

    Files.move(log, tempDir.resolve("same-suffix.log.1"));
    Files.writeString(log, "WARN " + "B".repeat(160) + commonSuffix, UTF_8);
    Files.getFileAttributeView(log, BasicFileAttributeView.class)
        .setTimes(null, null, original.creationTime());

    var page = service.read(cursor, null, null);

    assertThat(page.reset()).isTrue();
    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("WARN " + "B".repeat(160) + "S".repeat(160));
  }

  @Test
  void truncationResetsCursorAndReadsTheReplacementContent() throws IOException {
    Path log = writeLog("INFO a-long-original-line\nWARN second-original-line\n");
    var service = service(log, 20, 1_024);
    var cursor = service.read(null, null, null).nextCursor();
    Files.writeString(log, "ERROR new\n", UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

    var page = service.read(cursor, null, null);

    assertThat(page.reset()).isTrue();
    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("ERROR new");
  }

  @Test
  void missingFileReturnsAnEmptyUnavailablePage() {
    Path missing = tempDir.resolve("missing.log");

    var page = service(missing, 20, 1_024).read(null, null, null);

    assertThat(page.records()).isEmpty();
    assertThat(page.nextCursor()).isEmpty();
    assertThat(page.status()).isEqualTo("missing");
  }

  @Test
  void malformedUtf8IsDecodedWithReplacementCharacters() throws IOException {
    Path log = tempDir.resolve("application.log");
    Files.write(log, new byte[] {'I', 'N', 'F', 'O', ' ', (byte) 0xC3, (byte) 0x28, '\n'});

    var page = service(log, 20, 1_024).read(null, null, null);

    assertThat(page.records()).singleElement().satisfies(
        record -> assertThat(record.text()).contains("\uFFFD("));
  }

  @Test
  void trailingIncompleteLineIsHeldUntilItsNewlineArrives() throws IOException {
    Path log = writeLog("INFO ready\n");
    var service = service(log, 20, 64);
    var cursor = service.read(null, null, null).nextCursor();
    Files.writeString(log, "WARN pending", UTF_8, StandardOpenOption.APPEND);

    var pending = service.read(cursor, null, null);
    Files.writeString(log, " completed\n", UTF_8, StandardOpenOption.APPEND);
    var completed = service.read(pending.nextCursor(), null, null);

    assertThat(pending.records()).isEmpty();
    assertThat(completed.records()).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("WARN pending completed");
  }

  @Test
  void splitSecretLinesAreDiscardedWithoutReturningFragments() throws IOException {
    assertSplitSecretIsNeverReturned("INFO token=" + "t".repeat(80));
    assertSplitSecretIsNeverReturned("INFO Authorization: Bearer " + "b".repeat(80));
    assertSplitSecretIsNeverReturned(
        "INFO eyJ" + "a".repeat(40) + "." + "b".repeat(40) + "." + "c".repeat(40));
  }

  @Test
  void oversizedLineWithNoNewlineMakesProgressAndRecoversAtNextCompleteLine()
      throws IOException {
    Path log = writeLog("INFO ready\n");
    var service = service(log, 20, 16);
    var cursor = service.read(null, null, null).nextCursor();
    Files.writeString(log, "X".repeat(96), UTF_8, StandardOpenOption.APPEND);

    List<String> cursors = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      var page = service.read(cursor, null, null);
      assertThat(page.records()).isEmpty();
      assertThat(page.nextCursor()).isNotEqualTo(cursor);
      cursor = page.nextCursor();
      cursors.add(cursor);
    }

    Files.writeString(log, "X".repeat(32) + "\nINFO safe\n", UTF_8, StandardOpenOption.APPEND);
    var recoveredRecords = readUntilRecord(service, cursor, 10);

    assertThat(cursors).doesNotHaveDuplicates();
    assertThat(recoveredRecords).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("INFO safe");
  }

  @Test
  void redactsAuthorizationCredentialsNamedSecretsAndJwts() throws IOException {
    Path log = writeLog(
        "INFO Authorization: Bearer bearer-value\n"
            + "INFO password=hunter2 api_key: abc123 secret = hush token=tok-value\n"
            + "INFO eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature\n");

    var page = service(log, 20, 4_096).read(null, null, null);

    assertThat(page.records()).extracting(CommandCenterLogService.LogRecord::text)
        .allSatisfy(text -> assertThat(text)
            .doesNotContain("bearer-value", "hunter2", "abc123", "hush", "tok-value", "eyJ"));
    assertThat(page.records().get(0).text()).contains("Authorization: Bearer [REDACTED]");
    assertThat(page.records().get(1).text())
        .contains("password=[REDACTED]", "api_key: [REDACTED]", "secret = [REDACTED]", "token=[REDACTED]");
    assertThat(page.records().get(2).text()).contains("[REDACTED]");
  }

  private Path writeLog(String content) throws IOException {
    Path log = tempDir.resolve("application.log");
    Files.writeString(log, content, UTF_8);
    return log;
  }

  private void assertSplitSecretIsNeverReturned(String secretLine) throws IOException {
    Path log = tempDir.resolve("split-" + Math.abs(secretLine.hashCode()) + ".log");
    Files.writeString(log, "INFO ready\n", UTF_8);
    var service = service(log, 20, 16);
    var cursor = service.read(null, null, null).nextCursor();
    Files.writeString(log, secretLine, UTF_8, StandardOpenOption.APPEND);

    for (int index = 0; index < 4; index++) {
      var page = service.read(cursor, null, null);
      assertThat(page.records()).as("secret fragments must stay hidden").isEmpty();
      assertThat(page.nextCursor()).isNotEqualTo(cursor);
      cursor = page.nextCursor();
    }

    Files.writeString(log, "\nINFO safe\n", UTF_8, StandardOpenOption.APPEND);
    var recoveredRecords = readUntilRecord(service, cursor, 16);
    assertThat(recoveredRecords).extracting(CommandCenterLogService.LogRecord::text)
        .containsExactly("INFO safe");
  }

  private List<CommandCenterLogService.LogRecord> readUntilRecord(
      CommandCenterLogService service, String initialCursor, int attempts) {
    String cursor = initialCursor;
    for (int index = 0; index < attempts; index++) {
      var page = service.read(cursor, null, null);
      if (!page.records().isEmpty()) {
        return page.records();
      }
      cursor = page.nextCursor();
    }
    return List.of();
  }

  private CommandCenterLogService service(Path logPath, int maxLines, int maxBytes) {
    var properties = new CommandCenterProperties();
    properties.setLogPath(logPath);
    properties.setMaxLogLines(maxLines);
    properties.setMaxLogBytes(maxBytes);
    return new CommandCenterLogService(properties);
  }

  private int renderedBytes(CommandCenterLogService.LogPage page) {
    return page.records().stream().mapToInt(record -> (record.text() + "\n").getBytes(UTF_8).length).sum();
  }
}
