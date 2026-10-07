package dev.christopherbell.message.model;

import java.time.Instant;
import lombok.Builder;

/**
 * One direct message as a participant sees it.
 *
 * @param mine whether the viewing account sent the message
 */
@Builder
public record MessageDetail(
    String id,
    String senderAccountId,
    String senderUsername,
    String recipientAccountId,
    String recipientUsername,
    String text,
    Boolean read,
    Boolean mine,
    Instant createdOn
) {

  /**
   * Describes a stored message for one of its participants.
   *
   * @param viewerAccountId the participant viewing the message
   * @param senderUsername the sender's username, or {@code null} if the account is gone
   * @param recipientUsername the recipient's username, or {@code null} if the account is gone
   */
  public static MessageDetail from(
      Message message, String viewerAccountId, String senderUsername, String recipientUsername) {
    return MessageDetail.builder()
        .id(message.getId())
        .senderAccountId(message.getSenderAccountId())
        .senderUsername(senderUsername)
        .recipientAccountId(message.getRecipientAccountId())
        .recipientUsername(recipientUsername)
        .text(message.getText())
        .read(Boolean.TRUE.equals(message.getRead()))
        .mine(viewerAccountId.equals(message.getSenderAccountId()))
        .createdOn(message.getCreatedOn())
        .build();
  }
}
