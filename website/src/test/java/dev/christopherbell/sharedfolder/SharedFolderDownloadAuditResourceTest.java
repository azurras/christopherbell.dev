package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.christopherbell.sharedfolder.service.SharedFolderDownloadAuditResource;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.ByteArrayResource;

class SharedFolderDownloadAuditResourceTest {
  @Test
  void exactBodyCloseRecordsCompletedWithActualBytesOnce() throws Exception {
    List<String> events = new ArrayList<>();
    var resource = new SharedFolderDownloadAuditResource(
        new ByteArrayResource(new byte[] {1, 2, 3, 4}), 4,
        (outcome, bytes) -> events.add(outcome + ":" + bytes));

    try (InputStream stream = resource.getInputStream()) {
      assertThat(stream.readNBytes(4)).hasSize(4);
    }

    assertThat(events).containsExactly("COMPLETED:4");
  }

  @Test
  void earlyCloseRecordsAbortedWithOnlyDeliveredBytes() throws Exception {
    List<String> events = new ArrayList<>();
    var resource = new SharedFolderDownloadAuditResource(
        new ByteArrayResource(new byte[] {1, 2, 3, 4}), 4,
        (outcome, bytes) -> events.add(outcome + ":" + bytes));

    try (InputStream stream = resource.getInputStream()) {
      assertThat(stream.readNBytes(2)).hasSize(2);
    }

    assertThat(events).containsExactly("ABORTED:2");
  }

  @Test
  void streamFailureRecordsFailedOnceBeforePropagating() throws Exception {
    List<String> events = new ArrayList<>();
    AbstractResource failing = new AbstractResource() {
      @Override public String getDescription() { return "failing"; }
      @Override public InputStream getInputStream() {
        return new InputStream() {
          @Override public int read() throws IOException { throw new IOException("disconnected"); }
        };
      }
    };
    var resource = new SharedFolderDownloadAuditResource(
        failing, 4, (outcome, bytes) -> events.add(outcome + ":" + bytes));

    try (InputStream stream = resource.getInputStream()) {
      assertThatThrownBy(stream::read).isInstanceOf(IOException.class);
    }

    assertThat(events).containsExactly("FAILED:0");
  }

  @Test
  void prematureEndOfStreamRecordsFailedWithActualBytes() throws Exception {
    List<String> events = new ArrayList<>();
    var resource = new SharedFolderDownloadAuditResource(
        new ByteArrayResource(new byte[] {1, 2}), 4,
        (outcome, bytes) -> events.add(outcome + ":" + bytes));

    try (InputStream stream = resource.getInputStream()) {
      assertThat(stream.readAllBytes()).hasSize(2);
    }

    assertThat(events).containsExactly("FAILED:2");
  }
}
