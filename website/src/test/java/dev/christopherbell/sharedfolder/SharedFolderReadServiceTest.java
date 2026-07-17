package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.configuration.SharedFolderProperties;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind;
import dev.christopherbell.sharedfolder.service.SharedFolderBrowserService;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class SharedFolderReadServiceTest {
  @TempDir Path temp;
  private Path root;
  private SharedFolderProperties properties;

  @BeforeEach
  void setUp() throws Exception {
    root = Files.createDirectories(temp.resolve("shared"));
    Files.createDirectories(temp.resolve("system"));
    properties = new SharedFolderProperties(
        root,
        temp.resolve("system"),
        DataSize.ofGigabytes(10),
        DataSize.ofMegabytes(8),
        DataSize.ofGigabytes(100),
        DataSize.ofGigabytes(250),
        Duration.ofDays(30),
        Duration.ofDays(180),
        true);
  }

  @Test
  void listingReturnsOnlyRelativeMetadataAndSafePreviewKinds() throws Exception {
    Path music = Files.createDirectories(root.resolve("music"));
    Files.writeString(music.resolve("track.flac"), "FLAC fixture");
    Files.writeString(music.resolve("notes.txt"), "notes");

    var response = new SharedFolderBrowserService(properties).list("music");

    assertThat(response.path()).isEqualTo("music");
    assertThat(response.entries()).extracting("name")
        .containsExactly("notes.txt", "track.flac");
    assertThat(response.entries()).extracting("path")
        .containsExactly("music/notes.txt", "music/track.flac");
    assertThat(response.entries()).extracting("previewKind")
        .containsExactly(SharedFolderPreviewKind.TEXT, SharedFolderPreviewKind.AUDIO);
    assertThat(response.entries()).extracting("observedToken")
        .allSatisfy(token -> assertThat((String) token).isNotBlank());
    assertThat(response.toString()).doesNotContain(root.toString());
  }

  @Test
  void rangeDownloadSelectsOneByteRegionWithoutReadingTheWholeFile() throws Exception {
    Files.write(root.resolve("sample.bin"), "0123456789".getBytes(StandardCharsets.UTF_8));

    var transfer = new SharedFolderDownloadService(properties)
        .open("sample.bin", "bytes=2-5");

    assertThat(transfer.partial()).isTrue();
    assertThat(transfer.start()).isEqualTo(2);
    assertThat(transfer.length()).isEqualTo(4);
    assertThat(transfer.totalLength()).isEqualTo(10);
    assertThat(transfer.disposition().toString()).startsWith("attachment;");
    try (var input = transfer.resource().getInputStream()) {
      input.skipNBytes(transfer.start());
      assertThat(new String(input.readNBytes((int) transfer.length()), StandardCharsets.UTF_8))
          .isEqualTo("2345");
    }
  }

  @Test
  void malformedMultipleAndUnsatisfiedRangesAreRejected() throws Exception {
    Files.writeString(root.resolve("sample.bin"), "0123456789");
    var downloads = new SharedFolderDownloadService(properties);

    assertRangeRejected(downloads, "bytes=broken");
    assertRangeRejected(downloads, "");
    assertRangeRejected(downloads, "bytes=0-1,4-5");
    assertRangeRejected(downloads, "bytes=20-30");
  }

  @Test
  void textPreviewIsUtf8BoundedAndActiveContentIsAttachmentOnly() throws Exception {
    Files.writeString(root.resolve("notes.txt"), "<script>alert('no')</script>\nsecond");
    Files.writeString(root.resolve("active.html"), "<script>alert('no')</script>");
    var previews = new SharedFolderPreviewService(properties);

    var text = previews.open("notes.txt");
    assertThat(text.kind()).isEqualTo(SharedFolderPreviewKind.TEXT);
    assertThat(text.text()).contains("<script>");
    assertThat(text.resource()).isNull();
    assertThat(text.truncated()).isFalse();

    var active = previews.open("active.html");
    assertThat(active.kind()).isEqualTo(SharedFolderPreviewKind.NONE);
    assertThat(active.text()).isNull();
    assertThat(active.disposition().toString()).startsWith("attachment;");
    assertThat(active.mediaType().toString()).isEqualTo("application/octet-stream");
  }

  @Test
  void previewBoundsTextAndUsesTheRelativeSafeFilenameInDispositionHeaders() throws Exception {
    Files.writeString(root.resolve("long.txt"), "x".repeat(70 * 1024));
    Files.writeString(root.resolve("summer plan.pdf"), "%PDF-safe");

    var text = new SharedFolderPreviewService(properties).open("long.txt");
    var download = new SharedFolderDownloadService(properties).open("summer plan.pdf", null);
    var pdf = new SharedFolderPreviewService(properties).open("summer plan.pdf");

    assertThat(text.truncated()).isTrue();
    assertThat(text.text()).hasSize(64 * 1024);
    assertThat(download.disposition()).startsWith("attachment;")
        .contains("filename")
        .doesNotContain(root.toString(), "\r", "\n");
    assertThat(pdf.kind()).isEqualTo(SharedFolderPreviewKind.PDF);
    assertThat(pdf.disposition()).startsWith("inline;")
        .doesNotContain(root.toString(), "\r", "\n");
  }

  @Test
  void missingFilesReturnNotFoundWithoutAbsolutePathDetails() {
    assertThatThrownBy(() -> new SharedFolderDownloadService(properties)
        .open("missing.bin", null))
        .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
          assertThat(exception.getStatusCode().value()).isEqualTo(404);
          assertThat(exception.getMessage()).doesNotContain(root.toString());
        });
  }

  @Test
  void downloadResourceRejectsLeafSubstitutionWhenTheStreamActuallyOpens() throws Exception {
    Path file = Files.writeString(root.resolve("sample.bin"), "original");
    var transfer = new SharedFolderDownloadService(properties).open("sample.bin", null);

    Files.move(file, temp.resolve("original-sample.bin"));
    Files.writeString(file, "replacement");

    assertThatThrownBy(() -> transfer.resource().getInputStream())
        .isInstanceOf(IOException.class)
        .hasMessageNotContaining(root.toString());
  }

  @Test
  void binaryPreviewResourceRejectsLeafSubstitutionWhenTheStreamActuallyOpens() throws Exception {
    Path file = Files.writeString(root.resolve("sample.pdf"), "%PDF-original");
    var preview = new SharedFolderPreviewService(properties).open("sample.pdf");

    Files.move(file, temp.resolve("original-sample.pdf"));
    Files.writeString(file, "%PDF-replacement");

    assertThatThrownBy(() -> preview.resource().getInputStream())
        .isInstanceOf(IOException.class)
        .hasMessageNotContaining(root.toString());
  }

  private void assertRangeRejected(SharedFolderDownloadService downloads, String range) {
    assertThatThrownBy(() -> downloads.open("sample.bin", range))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            exception -> assertThat(exception.getStatusCode().value()).isEqualTo(416));
  }
}
