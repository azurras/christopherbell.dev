package dev.christopherbell.sitemonitor.api;

import dev.christopherbell.libs.api.model.Message;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.sitemonitor.model.MonitorWorkspace;
import dev.christopherbell.sitemonitor.model.CreateMonitorSite;
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
  private final SiteMonitorService service;
  public SiteMonitorController(SiteMonitorService service) { this.service = service; }

  @GetMapping
  public ResponseEntity<Response<MonitorWorkspace>> workspace() { return result(service.currentWorkspace()); }

  @PostMapping("/sites")
  public ResponseEntity<Response<MonitorWorkspace>> create(@Valid @RequestBody CreateMonitorSite input) {
    return result(service.addSite(input.label(), input.origin(), input.paths(), input.demo()));
  }

  @PostMapping("/sites/{id}/baseline")
  public ResponseEntity<Response<MonitorWorkspace>> baseline(@PathVariable String id) {
    return result(service.run(id, true));
  }

  @PostMapping("/sites/{id}/check")
  public ResponseEntity<Response<MonitorWorkspace>> check(@PathVariable String id) {
    return result(service.run(id, false));
  }

  @DeleteMapping("/sites/{id}")
  public ResponseEntity<Response<MonitorWorkspace>> delete(@PathVariable String id) {
    return result(service.removeSite(id));
  }

  @GetMapping("/sites/{id}/reports/{reportId}")
  public ResponseEntity<byte[]> report(@PathVariable String id, @PathVariable String reportId) {
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .header("Content-Type", "text/plain; charset=UTF-8")
        .header("Content-Disposition", "attachment; filename=website-monitor-report.txt")
        .header("X-Content-Type-Options", "nosniff")
        .body(service.report(id, reportId).getBytes(StandardCharsets.UTF_8));
  }

  @ExceptionHandler(MonitorProblem.class)
  public ResponseEntity<Response<?>> problem(MonitorProblem problem) {
    var response = ResponseEntity.status(problem.status()).header("Cache-Control", "no-store");
    if (problem.status() == 429) response.header("Retry-After", "900");
    else if (problem.status() == 409) response.header("Retry-After", "60");
    return response.body(Response.builder().success(false).messages(List.of(Message.builder()
        .code("SITE_MONITOR_REJECTED").description(problem.getMessage()).build())).build());
  }

  private static ResponseEntity<Response<MonitorWorkspace>> result(MonitorWorkspace workspace) {
    return ResponseEntity.ok().header("Cache-Control", "no-store")
        .body(Response.<MonitorWorkspace>builder().success(true).payload(workspace).build());
  }

}
