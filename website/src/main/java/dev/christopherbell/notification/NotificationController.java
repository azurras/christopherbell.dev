package dev.christopherbell.notification;

import static dev.christopherbell.libs.api.APIVersion.V20250914;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.notification.inbox.NotificationInboxService;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.notification.model.NotificationPreferenceDetail;
import dev.christopherbell.notification.model.NotificationPreferenceUpdateRequest;
import dev.christopherbell.notification.preference.NotificationPreferenceService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RequestMapping("/api/notifications")
@RestController
public class NotificationController {
  private final NotificationInboxService notificationInboxService;
  private final NotificationPreferenceService notificationPreferenceService;

  @GetMapping(value = V20250914, produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<NotificationDetail>>> getMyNotifications(
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) {
    return new ResponseEntity<>(
        Response.<List<NotificationDetail>>builder()
            .payload(notificationInboxService.getMyNotifications(limit))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @GetMapping(value = V20250914 + "/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<Long>> countMyUnreadNotifications() {
    return new ResponseEntity<>(
        Response.<Long>builder()
            .payload(notificationInboxService.countMyUnreadNotifications())
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @GetMapping(value = V20250914 + "/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<NotificationPreferenceDetail>> getNotificationPreferences() {
    return new ResponseEntity<>(
        Response.<NotificationPreferenceDetail>builder()
            .payload(notificationPreferenceService.getMyPreferences())
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @PutMapping(
      value = V20250914 + "/preferences",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE
  )
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<NotificationPreferenceDetail>> updateNotificationPreferences(
      @RequestBody NotificationPreferenceUpdateRequest request
  ) throws InvalidRequestException {
    return new ResponseEntity<>(
        Response.<NotificationPreferenceDetail>builder()
            .payload(notificationPreferenceService.updateMyPreferences(request))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @PostMapping(value = V20250914 + "/{notificationId}/read", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<NotificationDetail>> markRead(@PathVariable String notificationId)
      throws Exception {
    return new ResponseEntity<>(
        Response.<NotificationDetail>builder()
            .payload(notificationInboxService.markRead(notificationId))
            .success(true)
            .build(),
        HttpStatus.OK);
  }
}
