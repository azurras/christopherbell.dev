package dev.christopherbell.notification;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.notification.inbox.NotificationInboxService;
import dev.christopherbell.notification.model.NotificationDetail;
import dev.christopherbell.notification.model.NotificationType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@Import(ControllerExceptionHandler.class)
class NotificationControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private NotificationInboxService notificationInboxService;

  @Test
  @DisplayName("Get notifications: user -> 200 with requested limit")
  @WithMockUser(authorities = {"USER"})
  void getMyNotifications_whenUser_returnsNotifications() throws Exception {
    when(notificationInboxService.getMyNotifications(eq(10))).thenReturn(List.of(detail("notification-1")));

    mockMvc.perform(get("/api/notifications" + APIVersion.V20250914)
            .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value("notification-1"))
        .andExpect(jsonPath("$.payload[0].notificationType").value("MENTION"));

    verify(notificationInboxService).getMyNotifications(eq(10));
  }

  @Test
  @DisplayName("Get notifications: anonymous -> 401")
  void getMyNotifications_whenAnonymous_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/notifications" + APIVersion.V20250914))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(notificationInboxService);
  }

  @Test
  @DisplayName("Unread count: user -> 200 with count")
  @WithMockUser(authorities = {"USER"})
  void countMyUnreadNotifications_whenUser_returnsCount() throws Exception {
    when(notificationInboxService.countMyUnreadNotifications()).thenReturn(3L);

    mockMvc.perform(get("/api/notifications" + APIVersion.V20250914 + "/unread-count"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload").value(3));

    verify(notificationInboxService).countMyUnreadNotifications();
  }

  @Test
  @DisplayName("Mark read: user -> 200 with updated notification")
  @WithMockUser(authorities = {"USER"})
  void markRead_whenUser_returnsUpdatedNotification() throws Exception {
    var updated = NotificationDetail.builder()
        .id("notification-1")
        .notificationType(NotificationType.MENTION)
        .read(true)
        .createdOn(Instant.parse("2026-05-18T15:00:00Z"))
        .build();
    when(notificationInboxService.markRead(eq("notification-1"))).thenReturn(updated);

    mockMvc.perform(post("/api/notifications" + APIVersion.V20250914 + "/{notificationId}/read", "notification-1")
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("notification-1"))
        .andExpect(jsonPath("$.payload.read").value(true));

    verify(notificationInboxService).markRead(eq("notification-1"));
  }

  @Test
  @DisplayName("Mark read: anonymous -> 401")
  void markRead_whenAnonymous_returnsUnauthorized() throws Exception {
    mockMvc.perform(post("/api/notifications" + APIVersion.V20250914 + "/{notificationId}/read", "notification-1")
            .with(csrf()))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(notificationInboxService);
  }

  private NotificationDetail detail(String id) {
    return NotificationDetail.builder()
        .id(id)
        .accountId("account-1")
        .actorAccountId("actor-1")
        .actorUsername("writer")
        .postId("post-1")
        .postText("hello @reader")
        .notificationType(NotificationType.MENTION)
        .read(false)
        .createdOn(Instant.parse("2026-05-18T15:00:00Z"))
        .build();
  }
}
