package dev.christopherbell.account.model.dto;

/**
 * Public-safe account suggestion used by recipient autocomplete controls.
 *
 * @param username username that can be displayed and selected by the caller
 */
public record AccountUsernameSuggestion(String username) {
}
