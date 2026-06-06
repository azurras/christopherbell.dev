# Message Username Autocomplete Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add live recipient username suggestions to the Messages page.

**Architecture:** Add a protected account search endpoint that returns public-safe suggestion DTOs. Keep browser behavior in `messages.js`, with pure exported markup helpers for tests and minimal DOM wiring around the existing handle field.

**Tech Stack:** Java 21, Spring Boot, MongoDB repositories, Thymeleaf, vanilla JavaScript ES modules, Node test runner.

---

### Task 1: Backend Account Search

**Files:**
- Create: `website/src/main/java/dev/christopherbell/account/model/dto/AccountUsernameSuggestion.java`
- Modify: `website/src/main/java/dev/christopherbell/account/AccountRepository.java`
- Modify: `website/src/main/java/dev/christopherbell/account/AccountService.java`
- Modify: `website/src/main/java/dev/christopherbell/account/AccountController.java`
- Modify: `website/src/test/java/dev/christopherbell/account/AccountServiceTest.java`
- Modify: `website/src/test/java/dev/christopherbell/account/AccountControllerTest.java`

- [ ] Write failing service and controller tests for username suggestions.
- [ ] Implement the DTO, repository query, service method, and controller endpoint.
- [ ] Run targeted account tests.

### Task 2: Frontend Autocomplete

**Files:**
- Modify: `website/src/main/resources/static/js/lib/api.js`
- Modify: `website/src/main/resources/templates/messages.html`
- Modify: `website/src/main/resources/static/js/messages.js`
- Modify: `website/src/main/resources/static/css/main.css`
- Modify: `website/src/test/js/messages-rendering.test.js`

- [ ] Write failing JS tests for suggestion markup and template listbox.
- [ ] Add API route and render helpers.
- [ ] Add debounced input fetching, click selection, and clear behavior.
- [ ] Add Void-styled suggestion list CSS.
- [ ] Run JS syntax and Node tests.

### Task 3: Docs and Verification

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/account/README.md`
- Modify: `website/src/main/java/dev/christopherbell/message/README.md`
- Modify: `website/src/main/resources/static/js/README.md`

- [ ] Document the account search endpoint and Messages autocomplete behavior.
- [ ] Run targeted Java tests, JS tests, syntax checks, and scoped diff check.
