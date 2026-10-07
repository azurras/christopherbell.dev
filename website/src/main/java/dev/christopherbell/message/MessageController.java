package dev.christopherbell.message;

import static dev.christopherbell.libs.api.APIVersion.V20250914;
import static dev.christopherbell.libs.api.APIVersion.V20260726;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.message.model.MessageDetail;
import dev.christopherbell.message.conversation.ConversationArchiveResult;
import dev.christopherbell.message.conversation.ConversationPage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RequestMapping("/api/messages")
@RestController
public class MessageController {
  private final MessageService messageService;

  @PostMapping(
      value = V20250914,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<MessageDetail>> sendMessage(
      @RequestBody MessageCreateRequest createRequest
  ) throws Exception {
    MessageDetail sentMessage = messageService.sendMessage(createRequest);
    return ResponseEntity.status(HttpStatus.CREATED).body(
        Response.<MessageDetail>builder()
            .payload(sentMessage)
            .success(true)
            .build());
  }

  @GetMapping(value = V20250914 + "/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<ConversationSummary>>> listConversations(
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) throws Exception {
    List<ConversationSummary> conversations = messageService.listConversations(limit);
    return ResponseEntity.ok(
        Response.<List<ConversationSummary>>builder()
            .payload(conversations)
            .success(true)
            .build());
  }

  @GetMapping(value = V20250914 + "/conversation/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<MessageDetail>>> openConversation(
      @PathVariable("username") String otherUsername,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
  ) throws Exception {
    List<MessageDetail> messages = messageService.openConversation(otherUsername, limit);
    return ResponseEntity.ok(
        Response.<List<MessageDetail>>builder()
            .payload(messages)
            .success(true)
            .build());
  }

  @GetMapping(value = V20260726 + "/conversation/{username}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<ConversationPage>> openConversationPage(
      @PathVariable("username") String otherUsername,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "size", required = false, defaultValue = "50") int size
  ) throws Exception {
    ConversationPage conversationPage =
        messageService.openConversationPage(otherUsername, cursor, size);
    return ResponseEntity.ok(Response.<ConversationPage>builder()
        .payload(conversationPage)
        .success(true)
        .build());
  }

  @PostMapping(value = V20260726 + "/conversation/{username}/archive",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<ConversationArchiveResult>> archiveConversation(
      @PathVariable("username") String otherUsername
  ) throws Exception {
    ConversationArchiveResult archiveResult = messageService.archiveConversationWith(otherUsername);
    return ResponseEntity.ok(Response.<ConversationArchiveResult>builder()
        .payload(archiveResult)
        .success(true)
        .build());
  }
}
