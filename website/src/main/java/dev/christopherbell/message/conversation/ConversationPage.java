package dev.christopherbell.message.conversation;

import dev.christopherbell.message.model.MessageDetail;
import java.util.List;

/** Chronological conversation page with a cursor for the next older page. */
public record ConversationPage(List<MessageDetail> items, String nextCursor) {
  public ConversationPage {
    items = List.copyOf(items);
  }
}
