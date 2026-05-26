# Void Messages Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign `/messages` as the approved Void "Signal Bridge" two-pane messenger while preserving existing message APIs and behavior.

**Architecture:** Keep the current Thymeleaf template and vanilla `messages.js` page module, but replace the light hero/panel presentation with a scoped `void-messages-page` shell. JavaScript changes are limited to markup/class support for the visual states; `main.css` owns the dark Signal Bridge layout and responsive behavior.

**Tech Stack:** Spring Boot, Thymeleaf, vanilla JavaScript ES modules, CSS in `website/src/main/resources/static/css/main.css`.

---

## File Structure

- Modify `website/src/main/resources/templates/messages.html`
  - Owns static page shell, page header, alert placement, conversation rail, thread panel, and composer markup.
- Modify `website/src/main/resources/static/js/messages.js`
  - Owns conversation/message rendering markup and current behavior wiring.
- Modify `website/src/main/resources/static/css/main.css`
  - Owns `.void-messages-*`, conversation rail, message bubble, empty state, form, and responsive styles.
- Modify `website/src/main/java/dev/christopherbell/message/README.md`
  - Documents the backend/frontend boundary and Signal Bridge page rendering.
- Modify `website/src/main/resources/static/js/README.md`
  - Documents `messages.js` page responsibility.
- Modify `website/src/main/resources/static/css/README.md`
  - Documents Messages styling ownership in `main.css`.

---

### Task 1: Replace Messages Template Shell

**Files:**
- Modify: `website/src/main/resources/templates/messages.html`

- [ ] **Step 1: Update body and main classes**

Change:

```html
<body class="site-page messages-page">
```

to:

```html
<body class="site-page void-shell-page messages-page">
```

Change:

```html
<main class="site-main" role="main">
```

to:

```html
<main class="site-main void-messages-page" role="main">
```

- [ ] **Step 2: Replace the hero and site-content with the Signal Bridge shell**

Replace everything inside `<main ...>` with:

```html
    <section class="void-messages-shell" aria-labelledby="messagesTitle">
      <div class="void-messages-container">
        <header class="void-messages-header">
          <div>
            <p class="thread-label">Private signals</p>
            <h1 id="messagesTitle">Messages</h1>
            <p>Direct conversations inside the Void.</p>
          </div>
          <span class="thread-status-pill is-live">Encrypted by session</span>
        </header>

        <div id="messagesAlert" class="alert alert-danger d-none" role="alert"></div>

        <div class="messages-shell void-messages-bridge">
          <aside class="messages-sidebar void-messages-rail" aria-label="Conversations">
            <div class="messages-panel">
              <div class="messages-panel-header">
                <div>
                  <p class="profile-label">Signal rail</p>
                  <h2>Conversations</h2>
                </div>
              </div>
              <form id="newConversationForm" class="message-new-form">
                <label class="form-label" for="recipientUsername">Open a private signal</label>
                <div class="input-group">
                  <span class="input-group-text">@</span>
                  <input id="recipientUsername" class="form-control" autocomplete="off" placeholder="username" />
                  <button class="btn btn-dark" type="submit">Open</button>
                </div>
              </form>
              <div id="conversationList" class="conversation-list"></div>
            </div>
          </aside>

          <section class="messages-panel messages-thread-panel void-messages-thread" aria-labelledby="conversationTitle">
            <div class="messages-panel-header">
              <div>
                <p class="profile-label">Private signal</p>
                <h2 id="conversationTitle">Pick a conversation</h2>
              </div>
              <a id="conversationProfileLink" class="btn btn-outline-dark btn-sm d-none" href="/void">View profile</a>
            </div>
            <div id="messageList" class="message-list">
              <div class="feed-empty-state message-empty-state">
                <h2>No signal selected</h2>
                <p>Open a conversation or start one with a username.</p>
              </div>
            </div>
            <form id="messageForm" class="message-form d-none">
              <label class="visually-hidden" for="messageText">Write a private message</label>
              <textarea id="messageText" class="form-control" rows="3" maxlength="1000" placeholder="Write a private signal..."></textarea>
              <div class="message-form-actions">
                <span id="messageCount" class="composer-count">0 / 1000</span>
                <button id="sendMessageBtn" class="btn btn-dark" type="submit">Send</button>
              </div>
            </form>
          </section>
        </div>
      </div>
    </section>
```

- [ ] **Step 3: Run focused route test**

Run:

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 2: Tighten Message Rendering Markup

**Files:**
- Modify: `website/src/main/resources/static/js/messages.js`

- [ ] **Step 1: Update conversation empty state copy**

In `renderConversations`, replace:

```js
      <div class="conversation-empty">
        No messages yet.
      </div>`;
```

with:

```js
      <div class="conversation-empty">
        No signals yet. Start one with a username.
      </div>`;
```

- [ ] **Step 2: Add a direction label to message bubble rows**

In `renderMessages`, replace the `row.innerHTML` assignment with:

```js
    row.innerHTML = `
      <div class="message-bubble">
        <div class="message-bubble-meta">
          <span>${message.mine ? 'You' : `@${sanitize(message.senderUsername || ACTIVE_USERNAME || 'user')}`}</span>
          <time>${formatWhen(message.createdOn)}</time>
        </div>
        <p></p>
      </div>`;
```

This is intentionally the same structure as today. Do not add nested anchors or HTML-injected message text; `appendTextWithMentionLinks` remains responsible for safe mention/link rendering.

- [ ] **Step 3: Update empty conversation copy**

In `renderMessages`, replace:

```js
        <h2>No messages yet</h2>
        <p>Send the first private message in this conversation.</p>
```

with:

```js
        <h2>No signals yet</h2>
        <p>Send the first private message in this conversation.</p>
```

- [ ] **Step 4: Run JavaScript syntax check**

Run:

```bash
node --check website/src/main/resources/static/js/messages.js
```

Expected: no output and exit code `0`.

---

### Task 3: Add Signal Bridge CSS

**Files:**
- Modify: `website/src/main/resources/static/css/main.css`

- [ ] **Step 1: Add page shell styles near existing Messages styles**

Add this block immediately before the existing `.messages-shell` rule:

```css
.void-messages-page {
    background:
        radial-gradient(circle at 18% 8%, rgba(222, 177, 95, 0.12), transparent 24rem),
        radial-gradient(circle at 82% 16%, rgba(117, 202, 185, 0.14), transparent 28rem),
        linear-gradient(180deg, #081018 0%, #101820 42%, #070b10 100%);
    min-height: 100vh;
}

.void-messages-shell {
    padding: clamp(0.85rem, 2vw, 1.5rem) 0 clamp(2rem, 5vw, 4rem);
}

.void-messages-container {
    width: min(100% - 1.5rem, 1180px);
    margin: 0 auto;
}

.void-messages-header {
    display: flex;
    align-items: flex-end;
    justify-content: space-between;
    gap: 1rem;
    border: 1px solid rgba(128, 190, 185, 0.18);
    border-radius: 0.5rem;
    background: rgba(7, 12, 18, 0.76);
    box-shadow: 0 22px 58px rgba(0, 0, 0, 0.32);
    margin-bottom: 1rem;
    padding: clamp(1rem, 3vw, 1.35rem);
}

.void-messages-header h1 {
    color: #edf4f5;
    font-size: clamp(2rem, 5vw, 3.5rem);
    font-weight: 950;
    letter-spacing: 0;
    line-height: 0.95;
    margin: 0;
}

.void-messages-header p:last-child {
    color: rgba(201, 216, 221, 0.72);
    font-weight: 800;
    margin: 0.5rem 0 0;
}
```

- [ ] **Step 2: Add dark Signal Bridge overrides**

Add this block after the existing `.message-form-actions` rule:

```css
.void-messages-bridge {
    grid-template-columns: minmax(280px, 360px) minmax(0, 1fr);
}

.void-messages-page .messages-panel {
    border-color: rgba(128, 190, 185, 0.2);
    background: rgba(7, 12, 18, 0.78);
    box-shadow: 0 22px 58px rgba(0, 0, 0, 0.34);
    color: #e6edf2;
}

.void-messages-rail .messages-panel {
    border-radius: 1rem 0.35rem 0.35rem 1rem;
}

.void-messages-thread {
    border-radius: 0.35rem 1rem 1rem 0.35rem;
}

.void-messages-page .messages-panel-header {
    border-color: rgba(128, 190, 185, 0.16);
}

.void-messages-page .messages-panel-header h2 {
    color: #edf4f5;
}

.void-messages-page .profile-label,
.void-messages-page .form-label,
.void-messages-page .conversation-main small,
.void-messages-page .conversation-meta small,
.void-messages-page .conversation-empty {
    color: rgba(201, 216, 221, 0.68);
}

.void-messages-page .message-new-form {
    border-color: rgba(128, 190, 185, 0.16);
}

.void-messages-page .input-group-text,
.void-messages-page .message-new-form .form-control,
.void-messages-page .message-form .form-control {
    border-color: rgba(128, 190, 185, 0.22);
    background: rgba(4, 9, 14, 0.68);
    color: #edf4f5;
}

.void-messages-page .message-new-form .form-control::placeholder,
.void-messages-page .message-form .form-control::placeholder {
    color: rgba(201, 216, 221, 0.52);
}

.void-messages-page .message-new-form .form-control:focus,
.void-messages-page .message-form .form-control:focus {
    border-color: #75cab9;
    box-shadow: 0 0 0 0.2rem rgba(117, 202, 185, 0.15);
}

.void-messages-page .conversation-row {
    border-color: rgba(128, 190, 185, 0.16);
    background: rgba(4, 9, 14, 0.6);
    color: #e6edf2;
}

.void-messages-page .conversation-row:hover,
.void-messages-page .conversation-row:focus-visible,
.void-messages-page .conversation-row.active {
    border-left-color: #75cab9;
    background: linear-gradient(90deg, rgba(117, 202, 185, 0.15), rgba(4, 9, 14, 0.72));
}

.void-messages-page .conversation-main strong {
    color: #edf4f5;
}

.void-messages-page .conversation-avatar {
    border-radius: 999px;
    background: linear-gradient(135deg, #75cab9, #6c4e36);
    color: #061016;
    box-shadow: 0 0 0 4px rgba(8, 16, 22, 0.72);
}

.void-messages-page .conversation-unread {
    background: #75cab9;
    color: #061016;
}

.void-messages-page .message-list {
    scrollbar-color: rgba(117, 202, 185, 0.44) rgba(4, 9, 14, 0.62);
}

.void-messages-page .message-bubble {
    border-color: rgba(128, 190, 185, 0.22);
    border-radius: 1rem 1rem 1rem 0.25rem;
    background: rgba(12, 21, 27, 0.9);
    color: #edf4f5;
}

.void-messages-page .message-bubble-row.is-mine .message-bubble {
    border-color: rgba(222, 177, 95, 0.28);
    border-radius: 1rem 1rem 0.25rem 1rem;
    background: rgba(222, 177, 95, 0.14);
}

.void-messages-page .message-bubble-meta {
    color: rgba(201, 216, 221, 0.64);
}

.void-messages-page .message-bubble p {
    color: #edf4f5;
}

.void-messages-page .message-form {
    border-color: rgba(128, 190, 185, 0.16);
}

.void-messages-page .feed-empty-state {
    border-color: rgba(128, 190, 185, 0.18);
    background: rgba(4, 9, 14, 0.62);
    box-shadow: none;
    color: #edf4f5;
}
```

- [ ] **Step 3: Add responsive safeguards**

Inside the existing `@media (max-width: 768px)` block, add these rules before `.messages-shell { grid-template-columns: 1fr; }`:

```css
    .void-messages-header {
        align-items: flex-start;
        flex-direction: column;
    }

    .void-messages-bridge {
        grid-template-columns: 1fr;
    }

    .void-messages-rail .messages-panel,
    .void-messages-thread {
        border-radius: 0.5rem;
    }
```

Inside the existing `@media (max-width: 576px)` block, add:

```css
    .void-messages-container {
        width: min(100% - 0.75rem, 1180px);
    }

    .void-messages-page .message-bubble {
        width: min(90%, 620px);
    }
```

- [ ] **Step 4: Run CSS diff check**

Run:

```bash
git diff --check -- website/src/main/resources/static/css/main.css
```

Expected: no output and exit code `0`.

---

### Task 4: Update Documentation

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/message/README.md`
- Modify: `website/src/main/resources/static/js/README.md`
- Modify: `website/src/main/resources/static/css/README.md`

- [ ] **Step 1: Update message package README**

Add under "What Lives Here":

```markdown
- The `/messages` page, which renders direct messages as a Void Signal Bridge:
  conversation rail on the left, private signal thread on the right, and the
  existing API-backed send/open behavior in browser JavaScript.
```

- [ ] **Step 2: Update JavaScript README**

Add under "How It Works":

```markdown
- `messages.js` renders the `/messages` Signal Bridge interactions: conversation
  list state, selected private thread, safe message body rendering, character
  counter, send action, and login redirect.
```

- [ ] **Step 3: Update CSS README**

Add under "How It Works":

```markdown
- Messages uses `.void-messages-*` classes in `main.css` for the Signal Bridge
  layout: dark conversation rail, private thread panel, directional message
  bubbles, and responsive one-column behavior.
```

- [ ] **Step 4: Run documentation diff check**

Run:

```bash
git diff --check -- website/src/main/java/dev/christopherbell/message/README.md website/src/main/resources/static/js/README.md website/src/main/resources/static/css/README.md
```

Expected: no output and exit code `0`.

---

### Task 5: Verify Messages Redesign

**Files:**
- Verify: `website/src/main/resources/templates/messages.html`
- Verify: `website/src/main/resources/static/js/messages.js`
- Verify: `website/src/main/resources/static/css/main.css`

- [ ] **Step 1: Run JavaScript syntax check**

Run:

```bash
node --check website/src/main/resources/static/js/messages.js
```

Expected: no output and exit code `0`.

- [ ] **Step 2: Run focused view tests**

Run:

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Start app for route verification**

Run:

```bash
./gradlew :website:bootRun
```

Expected: app starts on `http://localhost:8081`.

Then verify the route markup:

```bash
curl -sS -D /tmp/messages-headers.txt http://localhost:8081/messages -o /tmp/messages-page.html
rg -n "void-messages-page|void-messages-header|void-messages-bridge|void-messages-rail|void-messages-thread" /tmp/messages-page.html
```

Expected: HTTP 200 and all listed markers are present.

- [ ] **Step 4: Browser verification if tooling is available**

If Chrome/Chromium or Playwright is available, inspect `/messages` at desktop
and mobile widths:

- Large old hero is gone.
- Header, rail, and thread form one cohesive dark surface.
- Active/unread conversation states are visible.
- Message bubbles have distinct incoming/outgoing direction.
- Composer wraps cleanly.
- Mobile has no overlaps.

- [ ] **Step 5: Run final diff check**

Run:

```bash
git diff --check -- website/src/main/resources/templates/messages.html website/src/main/resources/static/js/messages.js website/src/main/resources/static/css/main.css website/src/main/java/dev/christopherbell/message/README.md website/src/main/resources/static/js/README.md website/src/main/resources/static/css/README.md
```

Expected: no output and exit code `0`.

---

## Self-Review

- Spec coverage: The plan covers the Signal Bridge shell, conversation rail,
  private thread, existing JavaScript behavior, responsive behavior,
  accessibility safeguards, documentation, and verification.
- Placeholder scan: No TBD/TODO placeholders remain.
- Type consistency: The plan uses existing IDs/classes where behavior depends on
  them: `messagesAlert`, `newConversationForm`, `recipientUsername`,
  `conversationList`, `conversationTitle`, `conversationProfileLink`,
  `messageList`, `messageForm`, `messageText`, `messageCount`, and
  `sendMessageBtn`.
