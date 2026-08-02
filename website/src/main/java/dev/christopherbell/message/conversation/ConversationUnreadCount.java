package dev.christopherbell.message.conversation;

/** Mongo aggregation row for unread messages grouped by sender. */
record ConversationUnreadCount(String id, long count) {}
