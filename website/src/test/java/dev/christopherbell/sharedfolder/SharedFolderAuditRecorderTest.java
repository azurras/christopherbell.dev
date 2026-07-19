package dev.christopherbell.sharedfolder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.permission.PermissionService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class SharedFolderAuditRecorderTest {
  @AfterEach
  void clearRequest() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void logicalContentRangesProduceOneBoundedEventAndSinkFailureDoesNotLeakOrBreakReads() {
    SharedFolderAuditSink sink = mock(SharedFolderAuditSink.class);
    ClientIpResolver clientIps = mock(ClientIpResolver.class);
    PermissionService permissions = mock(PermissionService.class);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(clientIps.resolveClientIp(request)).thenReturn("203.0.113.8");
    when(permissions.getSelfId()).thenReturn("account-1");
    var recorder = new SharedFolderAuditRecorder(
        sink, permissions, clientIps,
        Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC));

    recorder.recordLogicalAccess("DOWNLOAD_STARTED", "music/song.flac", 42L);
    recorder.recordLogicalAccess("DOWNLOAD_STARTED", "music/song.flac", 42L);

    verify(sink, times(1)).record(any());

    org.mockito.Mockito.doThrow(new IllegalStateException("database secret A:\\Shared"))
        .when(sink).record(any());
    assertThatCode(() -> recorder.recordCurrent(
        "PREVIEW_STARTED", "docs/readme.txt", 12L, "accepted", null))
        .doesNotThrowAnyException();
  }

  @Test
  void invalidPathsAreRecordedAsANonSensitiveCategoryInsteadOfRawInput() {
    SharedFolderAuditSink sink = mock(SharedFolderAuditSink.class);
    ClientIpResolver clientIps = mock(ClientIpResolver.class);
    PermissionService permissions = mock(PermissionService.class);
    when(permissions.getSelfId()).thenReturn("account-1");
    var recorder = new SharedFolderAuditRecorder(
        sink, permissions, clientIps,
        Clock.fixed(Instant.parse("2026-07-18T12:00:00Z"), ZoneOffset.UTC));

    recorder.recordRejected("PREVIEW_STARTED", "A:/Shared/secret.txt", "invalid_path");

    var captor = org.mockito.ArgumentCaptor.forClass(
        dev.christopherbell.sharedfolder.audit.SharedFolderAuditCommand.class);
    verify(sink).record(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().relativePathOrResourceId())
        .isEqualTo("invalid-path");
  }
}
