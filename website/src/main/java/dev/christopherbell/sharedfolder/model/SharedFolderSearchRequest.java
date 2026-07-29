package dev.christopherbell.sharedfolder.model;

/** Raw search page boundary supplied by the authenticated HTTP controller. */
public record SharedFolderSearchRequest(String query, String cursor, Integer size) {}
