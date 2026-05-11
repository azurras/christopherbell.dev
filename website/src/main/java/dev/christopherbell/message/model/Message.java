package dev.christopherbell.message.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@Builder
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@Document("messages")
public class Message {
  private final String type = "message";

  @Id private String id;
  private String conversationKey;
  private Set<String> participantIds;
  private String senderAccountId;
  private String recipientAccountId;
  private String text;
  private Boolean read;

  @JsonFormat(
      shape = JsonFormat.Shape.STRING,
      pattern = "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'",
      timezone = "UTC")
  @CreatedDate
  private Instant createdOn;
}
