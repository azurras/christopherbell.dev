package dev.christopherbell.message;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.christopherbell.configuration.security.ControllerSliceSecurityTestConfig;
import dev.christopherbell.libs.api.APIVersion;
import dev.christopherbell.libs.api.controller.ControllerExceptionHandler;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.permission.PermissionService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MessageController.class)
@Import({ControllerExceptionHandler.class, ControllerSliceSecurityTestConfig.class})
public class MessageControllerTest {
  @Autowired private MockMvc mockMvc;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private MessageService messageService;

  @Test
  @DisplayName("Send message: USER -> 201 detail")
  @WithMockUser(authorities = {"USER"})
  public void sendMessage_whenUser_Returns201() throws Exception {
    var request = MessageCreateRequest.builder()
        .recipientUsername("alex")
        .text("hello")
        .build();
    var detail = MessageDetail.builder()
        .id("m1")
        .senderUsername("chris")
        .recipientUsername("alex")
        .text("hello")
        .mine(true)
        .createdOn(Instant.parse("2026-01-01T00:00:00Z"))
        .build();
    when(messageService.sendMessage(eq(request))).thenReturn(detail);

    mockMvc.perform(post("/api/messages" + APIVersion.V20250914)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientUsername\":\"alex\",\"text\":\"hello\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload.id").value("m1"))
        .andExpect(jsonPath("$.payload.text").value("hello"));

    verify(messageService).sendMessage(eq(request));
  }

  @Test
  @DisplayName("Send message: anonymous -> 401")
  public void sendMessage_whenAnonymous_Returns401() throws Exception {
    mockMvc.perform(post("/api/messages" + APIVersion.V20250914)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientUsername\":\"alex\",\"text\":\"hello\"}"))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(messageService);
  }

  @Test
  @DisplayName("Get conversation: USER -> 200 messages")
  @WithMockUser(authorities = {"USER"})
  public void getConversation_whenUser_ReturnsMessages() throws Exception {
    when(messageService.getConversation(eq("alex"), eq(50)))
        .thenReturn(List.of(MessageDetail.builder().id("m1").text("hello").build()));

    mockMvc.perform(get("/api/messages" + APIVersion.V20250914 + "/conversation/{username}", "alex")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].id").value("m1"));
  }

  @Test
  @DisplayName("Get conversations: USER -> 200 summaries")
  @WithMockUser(authorities = {"USER"})
  public void getConversations_whenUser_ReturnsSummaries() throws Exception {
    when(messageService.getConversations(eq(20)))
        .thenReturn(List.of(ConversationSummary.builder()
            .username("alex")
            .latestText("hello")
            .unreadCount(1)
            .build()));

    mockMvc.perform(get("/api/messages" + APIVersion.V20250914 + "/conversations")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.payload[0].username").value("alex"))
        .andExpect(jsonPath("$.payload[0].unreadCount").value(1));
  }
}
