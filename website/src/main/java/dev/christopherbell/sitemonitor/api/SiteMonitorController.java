package dev.christopherbell.sitemonitor.api;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.sitemonitor.model.CreateMonitorSite;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.monitor.SiteMonitorService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped monitoring APIs. Authentication, current account state and CSRF all apply. */
@RestController
@RequestMapping("/api/site-monitor/v1")
@PreAuthorize("isAuthenticated()")
public class SiteMonitorController {
  private static final String NO_STORE = "no-store";
  private static final String COOLDOWN_RETRY_AFTER_SECONDS = "900";
  private static final String CONFLICT_RETRY_AFTER_SECONDS = "60";

  private final SiteMonitorService siteMonitorService;

  public SiteMonitorController(SiteMonitorService siteMonitorService) {
    this.siteMonitorService = siteMonitorService;
  }

  @GetMapping
  public ResponseEntity<Response<MonitorWorkspace>> currentWorkspace() {
    return workspaceResponse(siteMonitorService.currentWorkspace());
  }

  @PostMapping("/sites")
  public ResponseEntity<Response<MonitorWorkspace>> addSite(
      @Valid @RequestBody CreateMonitorSite siteRequest) {
    return workspaceResponse(siteMonitorService.addSite(siteRequest));
  }

  @PostMapping("/sites/{id}/baseline")
  public ResponseEntity<Response<MonitorWorkspace>> captureBaseline(@PathVariable("id") String siteId) {
    return workspaceResponse(siteMonitorService.captureBaseline(siteId));
  }

  @PostMapping("/sites/{id}/check")
  public ResponseEntity<Response<MonitorWorkspace>> checkAgainstBaseline(
      @PathVariable("id") String siteId) {
    return workspaceResponse(siteMonitorService.checkAgainstBaseline(siteId));
  }

  @DeleteMapping("/sites/{id}")
  public ResponseEntity<Response<MonitorWorkspace>> removeSite(@PathVariable("id") String siteId) {
    return workspaceResponse(siteMonitorService.removeSite(siteId));
  }

  /** Downloads one report as a plain-text attachment that browsers will not render or sniff. */
  @GetMapping("/sites/{id}/reports/{reportId}")
  public ResponseEntity<byte[]> downloadReport(
      @PathVariable("id") String siteId, @PathVariable String reportId) {
    String reportText = siteMonitorService.renderReport(siteId, reportId);
    return ResponseEntity.ok()
        .header("Cache-Control", NO_STORE)
        .header("Content-Type", "text/plain; charset=UTF-8")
        .header("Content-Disposition", "attachment; filename=website-monitor-report.txt")
        .header("X-Content-Type-Options", "nosniff")
        .body(reportText.getBytes(StandardCharsets.UTF_8));
  }

  /** Maps a pilot rejection to its status, with retry guidance for cooldowns and conflicts. */
  @ExceptionHandler(MonitorProblem.class)
  public ResponseEntity<Response<?>> rejectedRequest(MonitorProblem problem) {
    ResponseEntity.BodyBuilder rejection =
        ResponseEntity.status(problem.status()).header("Cache-Control", NO_STORE);
    if (problem.status() == 429) {
      rejection.header("Retry-After", COOLDOWN_RETRY_AFTER_SECONDS);
    } else if (problem.status() == 409) {
      rejection.header("Retry-After", CONFLICT_RETRY_AFTER_SECONDS);
    }
    Message rejectionMessage = Message.builder()
        .code("SITE_MONITOR_REJECTED")
        .description(problem.getMessage())
        .build();
    return rejection.body(Response.builder()
        .success(false)
        .messages(List.of(rejectionMessage))
        .build());
  }

  private static ResponseEntity<Response<MonitorWorkspace>> workspaceResponse(MonitorWorkspace workspace) {
    return ResponseEntity.ok()
        .header("Cache-Control", NO_STORE)
        .body(Response.<MonitorWorkspace>builder().success(true).payload(workspace).build());
  }
}
