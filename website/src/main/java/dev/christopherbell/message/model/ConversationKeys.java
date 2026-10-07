package dev.christopherbell.message.model;

/** Builds the stored key that identifies the one conversation between two accounts. */
public final class ConversationKeys {

  private ConversationKeys() {
  }

  /**
   * Returns the conversation key for two accounts, the same whichever account is named first.
   *
   * @return {@code "<smaller id>:<larger id>"}
   */
  public static String between(String firstAccountId, String secondAccountId) {
    return firstAccountId.compareTo(secondAccountId) < 0
        ? firstAccountId + ":" + secondAccountId
        : secondAccountId + ":" + firstAccountId;
  }
}
