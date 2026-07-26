package dev.christopherbell.message.conversation;

import dev.christopherbell.message.model.Message;
import java.util.List;

/** Internal descending message slice plus its stable continuation cursor. */
public record ConversationMessageSlice(List<Message> items, String nextCursor) {
  public ConversationMessageSlice {
    items = List.copyOf(items);
  }
}
