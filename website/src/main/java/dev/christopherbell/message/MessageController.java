package dev.christopherbell.message;

import static dev.christopherbell.libs.api.APIVersion.V20250914;

import dev.christopherbell.libs.api.model.Response;
import dev.christopherbell.message.model.ConversationSummary;
import dev.christopherbell.message.model.MessageCreateRequest;
import dev.christopherbell.message.model.MessageDetail;
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
      @RequestBody MessageCreateRequest request
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<MessageDetail>builder()
            .payload(messageService.sendMessage(request))
            .success(true)
            .build(),
        HttpStatus.CREATED);
  }

  @GetMapping(value = V20250914 + "/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<ConversationSummary>>> getConversations(
      @RequestParam(value = "limit", required = false, defaultValue = "20") int limit
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<ConversationSummary>>builder()
            .payload(messageService.getConversations(limit))
            .success(true)
            .build(),
        HttpStatus.OK);
  }

  @GetMapping(value = V20250914 + "/conversation/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("@permissionService.hasAuthority('USER')")
  public ResponseEntity<Response<List<MessageDetail>>> getConversation(
      @PathVariable String username,
      @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
  ) throws Exception {
    return new ResponseEntity<>(
        Response.<List<MessageDetail>>builder()
            .payload(messageService.getConversation(username, limit))
            .success(true)
            .build(),
        HttpStatus.OK);
  }
}
