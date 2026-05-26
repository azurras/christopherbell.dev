# Void Reply Page Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `/p/{id}` as a single-column Void-styled post detail page that reads like an X/Twitter thread while keeping spectral context and lifespan cues.

**Architecture:** Keep the existing post API and feed renderer behavior. Replace the template's hero/sidebar structure with a centered thread shell, then let `post.js` render context echoes, selected post detail, compact reply composer, and reply timeline. Add page-scoped CSS under the existing post/thread section of `main.css` without introducing new frontend dependencies.

**Tech Stack:** Spring Boot, Thymeleaf, vanilla JavaScript ES modules, shared `fetchJson`/feed helpers, CSS in `website/src/main/resources/static/css/main.css`.

---

## File Structure

- Modify `website/src/main/resources/templates/post.html`
  - Owns static markup for the new single-column thread shell.
  - Removes the large hero, stats sidebar, and panel-heavy layout.
- Modify `website/src/main/resources/static/js/post.js`
  - Owns page-specific rendering for context echoes, selected post detail classes, compact composer wiring, summary metadata, and reply timeline filtering.
  - Continues to use shared API helpers and feed renderer.
- Modify `website/src/main/resources/static/css/main.css`
  - Owns visual styling for the new `.void-thread-*` page shell.
  - Keeps styles page-scoped under `.post-page` and `.void-shell-page` where possible.
- Modify `website/src/main/resources/static/js/README.md`
  - Documents the new post page script responsibilities.
- Modify `website/src/main/resources/static/css/README.md`
  - Documents the new post/reply page styling ownership.
- Modify `website/src/main/java/dev/christopherbell/post/README.md`
  - Documents that the post page now renders as a spectral thread detail view.

---

### Task 1: Replace The Post Page Shell

**Files:**
- Modify: `website/src/main/resources/templates/post.html`

- [ ] **Step 1: Replace the current `<main>` content with the new single-column shell**

Use this complete `<main>` block:

```html
<main class="site-main void-thread-page" role="main">
  <section class="void-thread-shell" aria-label="Post thread">
    <div class="void-thread-container">
      <header class="void-thread-topbar">
        <a class="void-thread-back" href="/void" aria-label="Back to the Void feed">←</a>
        <div class="void-thread-heading">
          <h1 id="postHeroTitle">Post</h1>
          <p id="postHeroMeta">Loading thread signal...</p>
        </div>
        <span class="thread-status-pill is-live" id="threadStatus">Live</span>
      </header>

      <div id="postAlert" class="alert alert-danger d-none" role="alert"></div>

      <section id="rootPost" class="void-thread-focus" aria-label="Selected post">
        <div class="thread-root-body"></div>
      </section>

      <section id="replyComposer" class="void-thread-composer d-none" aria-label="Reply composer">
        <div class="void-thread-composer-avatar" aria-hidden="true">Y</div>
        <div class="void-thread-composer-main">
          <label class="visually-hidden" for="replyText">Write a reply</label>
          <textarea id="replyText" class="form-control" rows="2" maxlength="280" placeholder="Post your reply"></textarea>
          <div class="void-thread-composer-actions">
            <span id="replyComposerMeta">Replies extend the signal.</span>
            <button id="replyBtn" class="btn btn-dark">Reply</button>
          </div>
        </div>
      </section>

      <section class="void-thread-replies" aria-labelledby="threadRepliesTitle">
        <div class="void-thread-section-header">
          <div>
            <p class="thread-label">Conversation</p>
            <h2 id="threadRepliesTitle">Replies</h2>
          </div>
          <span class="thread-count-pill" id="threadReplyPill">0 replies</span>
        </div>
        <div id="threadList" class="feed-list thread-feed-list void-thread-list"></div>
      </section>
    </div>
  </section>
</main>
```

- [ ] **Step 2: Keep existing assets and scripts**

Confirm the template still includes:

```html
<link rel="stylesheet" type="text/css" href="/css/main.css"/>
<script type="module" src="/js/app.js"></script>
<script type="module" src="/js/post.js"></script>
```

- [ ] **Step 3: Run the page route test**

Run:

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 2: Render Spectral Context And Selected Post Detail

**Files:**
- Modify: `website/src/main/resources/static/js/post.js`

- [ ] **Step 1: Update `renderThreadSummary` so it targets the new top bar**

Use this function body:

```js
function renderThreadSummary(post, directReplies) {
  const author = post.username ? `@${post.username}` : '@user';
  setText('threadReplyPill', `${directReplies.length} ${directReplies.length === 1 ? 'reply' : 'replies'}`);

  const status = statusFor(post);
  const statusEl = document.getElementById('threadStatus');
  if (statusEl) {
    statusEl.textContent = status.label;
    statusEl.className = `thread-status-pill is-${status.tone}`;
  }

  const heroTitle = document.getElementById('postHeroTitle');
  if (heroTitle) heroTitle.textContent = 'Post';

  const heroMeta = document.getElementById('postHeroMeta');
  if (heroMeta) {
    heroMeta.textContent = `${author} · ${directReplies.length} ${directReplies.length === 1 ? 'reply' : 'replies'}`;
  }
}
```

- [ ] **Step 2: Replace `contextCard` with a context echo**

Use this function:

```js
function contextCard(kind, postId) {
  return `
    <a class="void-context-echo" href="/p/${encodeURIComponent(postId)}" data-context-kind="${kind}">
      <span class="void-context-line" aria-hidden="true"></span>
      <span class="void-context-copy">
        <span class="void-context-label">${kind === 'root' ? 'Thread root' : 'In reply to'}</span>
        <span class="void-context-handle" data-context-handle>@user</span>
        <span class="void-context-text" data-context-text>Loading context...</span>
      </span>
    </a>`;
}
```

- [ ] **Step 3: Update `fillContext` selectors for the echo markup**

In `fillContext`, keep the fetch logic and change only the selectors:

```js
const handleEl = card?.querySelector('[data-context-handle]');
const textEl = card?.querySelector('[data-context-text]');
```

When setting the handle, do not set `href` on `handleEl`; the whole echo is already the link:

```js
if (handleEl) {
  const username = context.username || '';
  handleEl.textContent = username ? `@${username}` : '@user';
}
```

- [ ] **Step 4: Add detail mode classes in `renderRoot`**

After `root.appendChild(createFeedItem(post, ctx));`, add:

```js
const focusedPost = root.querySelector('.post-card');
if (focusedPost) {
  focusedPost.classList.add('void-thread-selected-post');
}
```

- [ ] **Step 5: Run JavaScript syntax check**

Run:

```bash
node --check website/src/main/resources/static/js/post.js
```

Expected: no output and exit code `0`.

---

### Task 3: Wire The Compact Reply Composer

**Files:**
- Modify: `website/src/main/resources/static/js/post.js`

- [ ] **Step 1: Add composer helper functions after `renderRoot`**

Add:

```js
function showReplyComposer(currentUser) {
  const composer = document.getElementById('replyComposer');
  const meta = document.getElementById('replyComposerMeta');
  if (!composer) return;
  composer.classList.remove('d-none');
  if (meta) {
    meta.textContent = currentUser ? 'Replies extend the signal.' : 'Log in to reply.';
  }
  const replyButton = document.getElementById('replyBtn');
  const replyText = document.getElementById('replyText');
  if (!currentUser) {
    replyText?.setAttribute('disabled', 'disabled');
    replyButton?.setAttribute('disabled', 'disabled');
  } else {
    replyText?.removeAttribute('disabled');
    replyButton?.removeAttribute('disabled');
  }
}

async function submitReply(parentId) {
  const replyText = document.getElementById('replyText');
  const replyButton = document.getElementById('replyBtn');
  const text = replyText?.value?.trim() || '';
  if (!text) return;
  replyButton?.setAttribute('disabled', 'disabled');
  try {
    await fetchJson(API.posts.create, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ text, parentId })
    });
    window.location.reload();
  } finally {
    replyButton?.removeAttribute('disabled');
  }
}
```

- [ ] **Step 2: Call `showReplyComposer` after the selected post renders**

In the `DOMContentLoaded` success path, after `renderRoot(post, me);`, add:

```js
showReplyComposer(me);
```

- [ ] **Step 3: Wire the reply button**

In the `DOMContentLoaded` success path, after `showReplyComposer(me);`, add:

```js
document.getElementById('replyBtn')?.addEventListener('click', () => submitReply(id));
```

- [ ] **Step 4: Run JavaScript syntax check**

Run:

```bash
node --check website/src/main/resources/static/js/post.js
```

Expected: no output and exit code `0`.

---

### Task 4: Restyle The Page As A Spectral Timeline

**Files:**
- Modify: `website/src/main/resources/static/css/main.css`

- [ ] **Step 1: Add page shell styles near the existing thread styles**

Add:

```css
.void-thread-page {
    background:
        radial-gradient(circle at 74% 0%, rgba(117, 202, 185, 0.14), transparent 28rem),
        linear-gradient(180deg, #081018 0%, #101820 42%, #070b10 100%);
    min-height: 100vh;
}

.void-thread-shell {
    padding: clamp(0.75rem, 2vw, 1.25rem) 0 clamp(2rem, 5vw, 4rem);
}

.void-thread-container {
    width: min(100%, 720px);
    margin: 0 auto;
    border-inline: 1px solid rgba(128, 190, 185, 0.16);
    background: rgba(7, 12, 18, 0.74);
    min-height: calc(100vh - 4rem);
}

.void-thread-topbar {
    position: sticky;
    top: 0;
    z-index: 5;
    display: grid;
    grid-template-columns: 2.5rem minmax(0, 1fr) auto;
    gap: 0.75rem;
    align-items: center;
    border-bottom: 1px solid rgba(128, 190, 185, 0.16);
    background: rgba(7, 12, 18, 0.9);
    backdrop-filter: blur(14px);
    padding: 0.75rem 1rem;
}

.void-thread-back {
    display: grid;
    place-items: center;
    width: 2.25rem;
    height: 2.25rem;
    border: 1px solid rgba(158, 225, 210, 0.34);
    border-radius: 999px;
    color: #9ee1d2;
    font-size: 1.15rem;
    font-weight: 900;
    text-decoration: none;
}

.void-thread-back:hover,
.void-thread-back:focus-visible {
    background: rgba(117, 202, 185, 0.14);
    color: #edf4f5;
}

.void-thread-heading {
    min-width: 0;
}

.void-thread-heading h1 {
    color: #edf4f5;
    font-size: 1.05rem;
    font-weight: 900;
    margin: 0;
}

.void-thread-heading p {
    color: rgba(201, 216, 221, 0.68);
    font-size: 0.82rem;
    font-weight: 800;
    margin: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
```

- [ ] **Step 2: Add selected post and context echo styles**

Add:

```css
.void-thread-focus {
    border-bottom: 1px solid rgba(128, 190, 185, 0.18);
}

.void-thread-focus .thread-root-body {
    gap: 0;
}

.void-context-stack {
    gap: 0;
}

.void-context-stack::before {
    display: none;
}

.void-context-echo {
    display: grid;
    grid-template-columns: 2.5rem minmax(0, 1fr);
    gap: 0.75rem;
    border-bottom: 1px solid rgba(128, 190, 185, 0.12);
    color: inherit;
    padding: 0.8rem 1rem;
    text-decoration: none;
}

.void-context-line {
    justify-self: center;
    width: 2px;
    min-height: 3rem;
    background: linear-gradient(180deg, rgba(222, 177, 95, 0.1), rgba(158, 225, 210, 0.55));
}

.void-context-copy {
    display: grid;
    gap: 0.18rem;
    min-width: 0;
}

.void-context-label,
.void-context-handle {
    color: rgba(222, 177, 95, 0.76);
    font-size: 0.75rem;
    font-weight: 900;
    text-transform: uppercase;
}

.void-context-handle {
    color: rgba(201, 216, 221, 0.68);
    text-transform: none;
}

.void-context-text {
    color: rgba(237, 244, 245, 0.78);
    font-weight: 800;
    overflow-wrap: anywhere;
}

.void-thread-selected-post {
    border: 0;
    border-radius: 0;
    background: transparent;
    box-shadow: none;
    padding: clamp(1rem, 3vw, 1.25rem);
}

.void-thread-selected-post .post-body {
    font-size: clamp(1.18rem, 2.6vw, 1.55rem);
    line-height: 1.38;
}
```

- [ ] **Step 3: Add composer and reply timeline styles**

Add:

```css
.void-thread-composer {
    display: grid;
    grid-template-columns: 2.25rem minmax(0, 1fr);
    gap: 0.75rem;
    border-bottom: 1px solid rgba(128, 190, 185, 0.16);
    padding: 0.9rem 1rem;
}

.void-thread-composer-avatar {
    display: grid;
    place-items: center;
    width: 2.25rem;
    height: 2.25rem;
    border-radius: 999px;
    background: linear-gradient(135deg, #75cab9, #deb15f);
    color: #061016;
    font-weight: 950;
}

.void-thread-composer-main {
    min-width: 0;
}

.void-thread-composer .form-control {
    border: 1px solid rgba(128, 190, 185, 0.22);
    border-radius: 0.75rem;
    background: rgba(4, 9, 14, 0.64);
    color: #edf4f5;
}

.void-thread-composer-actions {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-top: 0.65rem;
}

.void-thread-composer-actions span {
    color: rgba(201, 216, 221, 0.66);
    font-size: 0.82rem;
    font-weight: 800;
}

.void-thread-replies {
    min-width: 0;
}

.void-thread-section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 1rem;
    border-bottom: 1px solid rgba(128, 190, 185, 0.16);
    padding: 0.9rem 1rem;
}

.void-thread-section-header h2 {
    color: #edf4f5;
    font-size: 1rem;
    font-weight: 900;
    margin: 0;
}

.void-thread-list {
    gap: 0;
}

.void-thread-list .post-card {
    border: 0;
    border-bottom: 1px solid rgba(128, 190, 185, 0.14);
    border-radius: 0;
    background: transparent;
    box-shadow: none;
}
```

- [ ] **Step 4: Add mobile safeguards**

Add:

```css
@media (max-width: 768px) {
    .void-thread-container {
        border-inline: 0;
        min-height: 100vh;
    }

    .void-thread-topbar {
        grid-template-columns: 2.25rem minmax(0, 1fr);
    }

    .void-thread-topbar .thread-status-pill {
        grid-column: 2;
        justify-self: start;
    }

    .void-thread-section-header,
    .void-thread-composer-actions {
        align-items: flex-start;
        flex-direction: column;
    }
}
```

- [ ] **Step 5: Run CSS diff check**

Run:

```bash
git diff --check -- website/src/main/resources/static/css/main.css
```

Expected: no output and exit code `0`.

---

### Task 5: Update Documentation

**Files:**
- Modify: `website/src/main/resources/static/js/README.md`
- Modify: `website/src/main/resources/static/css/README.md`
- Modify: `website/src/main/java/dev/christopherbell/post/README.md`

- [ ] **Step 1: Update JavaScript README**

Add this bullet under the page-script section:

```markdown
- `post.js` renders the `/p/{id}` spectral thread page: selected post detail,
  root/parent context echoes, compact reply composer, and direct replies.
```

- [ ] **Step 2: Update CSS README**

Add this bullet under page or feature styling ownership:

```markdown
- Post/reply page styles use the `.void-thread-*` classes in `main.css` to keep
  the `/p/{id}` detail view as a single-column Void timeline.
```

- [ ] **Step 3: Update post package README**

Add this design note:

```markdown
- The `/p/{id}` page renders as a spectral thread detail view: the selected post
  is the anchor, parent/root context appears as muted echoes, and direct replies
  render in a continuous single-column timeline.
```

- [ ] **Step 4: Run diff check on docs**

Run:

```bash
git diff --check -- website/src/main/resources/static/js/README.md website/src/main/resources/static/css/README.md website/src/main/java/dev/christopherbell/post/README.md
```

Expected: no output and exit code `0`.

---

### Task 6: Browser Verification

**Files:**
- Verify: `website/src/main/resources/templates/post.html`
- Verify: `website/src/main/resources/static/js/post.js`
- Verify: `website/src/main/resources/static/css/main.css`

- [ ] **Step 1: Start the app if it is not running**

Run:

```bash
./gradlew :website:bootRun
```

Expected: app starts on the configured local port. If another app process is already running, use the running instance and do not start a second one.

- [ ] **Step 2: Open a post page at desktop width**

Use an existing post URL such as:

```text
http://localhost:8081/p/{existing-post-id}
```

Verify:

- The large old hero is gone.
- The top bar is compact.
- The selected post is the visual anchor.
- Context echoes appear above reply posts.
- The stats sidebar is gone.
- No text overlaps or overflows.

- [ ] **Step 3: Open the same page at mobile width**

Use a viewport near `430 x 932`.

Verify:

- The page is single-column.
- The top bar wraps without overlap.
- Composer textarea and Reply button are usable.
- Reply action rows wrap cleanly.
- Empty and expired states remain readable.

---

### Task 7: Final Verification

**Files:**
- Verify all touched files.

- [ ] **Step 1: Run JavaScript syntax check**

Run:

```bash
node --check website/src/main/resources/static/js/post.js
```

Expected: no output and exit code `0`.

- [ ] **Step 2: Run focused Java route tests**

Run:

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run full website tests**

Run:

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run final diff check**

Run:

```bash
git diff --check -- website/src/main/resources/templates/post.html website/src/main/resources/static/js/post.js website/src/main/resources/static/css/main.css website/src/main/resources/static/js/README.md website/src/main/resources/static/css/README.md website/src/main/java/dev/christopherbell/post/README.md
```

Expected: no output and exit code `0`.

---

## Self-Review

- Spec coverage: The plan covers the single-column layout, selected post detail,
  context echoes, compact composer, continuous reply timeline, Void visual style,
  mobile behavior, accessibility safeguards, docs, and verification.
- Placeholder scan: No disallowed placeholder tokens or open implementation
  placeholders remain.
- Type consistency: The plan uses existing IDs and APIs: `postHeroTitle`,
  `postHeroMeta`, `threadStatus`, `threadReplyPill`, `replyComposer`,
  `replyText`, `replyBtn`, `API.posts.create`, `API.posts.byId`, and
  `API.posts.thread`.
