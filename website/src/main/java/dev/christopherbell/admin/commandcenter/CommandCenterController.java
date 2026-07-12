package dev.christopherbell.admin.commandcenter;

import static dev.christopherbell.libs.api.APIVersion.V20260712;

import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService.ActionChallenge;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService.ActionConfirmation;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionService.ActionResult;
import dev.christopherbell.admin.commandcenter.action.CommandCenterActionType;
import dev.christopherbell.admin.commandcenter.logs.CommandCenterLogService;
import dev.christopherbell.admin.commandcenter.logs.CommandCenterLogService.LogPage;
import dev.christopherbell.admin.commandcenter.metrics.CommandCenterMetricsService;
import dev.christopherbell.admin.commandcenter.model.CommandCenterSnapshot;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.model.Response;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Method-secured HTTP facade for command-center metrics, logs, and fixed host actions. */
@RequiredArgsConstructor
@RequestMapping("/api/admin/command-center")
@RestController
public class CommandCenterController {
  private final CommandCenterMetricsService metricsService;
  private final CommandCenterLogService logService;
  private final CommandCenterActionService actionService;

  /** Returns the latest immutable cached host snapshot. */
  @GetMapping(value = V20260712 + "/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')"
      + " and @commandCenterAccessService.hasFreshAdminAccess()")
  public ResponseEntity<Response<CommandCenterSnapshot>> snapshot() {
    return ok(metricsService.snapshot());
  }

  /** Returns one bounded, redacted page from the configured server-side log. */
  @GetMapping(value = V20260712 + "/logs", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')"
      + " and @commandCenterAccessService.hasFreshAdminAccess()")
  public ResponseEntity<Response<LogPage>> logs(
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "ALL") String level,
      @RequestParam(defaultValue = "") @Size(max = 100) String query) {
    return ok(logService.read(cursor, level, query));
  }

  /** Creates a short-lived challenge for one closed-set host action. */
  @PostMapping(
      value = V20260712 + "/action-challenges",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')"
      + " and @commandCenterAccessService.hasFreshAdminAccess()")
  public ResponseEntity<Response<ActionChallenge>> challenge(
      @Valid @RequestBody ChallengeRequest request) throws InvalidRequestException {
    return ok(actionService.createChallenge(request.action()));
  }

  /** Confirms and accepts a previously challenged fixed host action. */
  @PostMapping(
      value = V20260712 + "/actions",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')"
      + " and @commandCenterAccessService.hasFreshAdminAccess()")
  public ResponseEntity<Response<ActionResult>> execute(
      @Valid @RequestBody ActionConfirmation request,
      HttpServletRequest servletRequest) throws InvalidRequestException {
    return ResponseEntity.accepted().body(success(actionService.execute(request, servletRequest)));
  }

  /** Cancels the currently pending cancellable machine action. */
  @PostMapping(value = V20260712 + "/actions/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('ADMIN')"
      + " and @commandCenterAccessService.hasFreshAdminAccess()")
  public ResponseEntity<Response<ActionResult>> cancel(HttpServletRequest servletRequest)
      throws InvalidRequestException {
    return ok(actionService.cancel(servletRequest));
  }

  private static <T> ResponseEntity<Response<T>> ok(T payload) {
    return ResponseEntity.ok(success(payload));
  }

  private static <T> Response<T> success(T payload) {
    return Response.<T>builder().payload(payload).success(true).build();
  }

  /** Challenge creation input restricted to the closed action enum. */
  public record ChallengeRequest(@NotNull CommandCenterActionType action) {}
}
