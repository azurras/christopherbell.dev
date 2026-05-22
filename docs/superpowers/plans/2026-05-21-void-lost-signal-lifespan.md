# Void Lost Signal Lifespan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn on Void post expiration, show the server-backed lifespan on every shared post card, and apply the approved Lost Signal theme to every Void-related page surface.

**Architecture:** Keep expiration authority in `PostService` and the existing `expiresOn` feed contract. Keep card-level countdown rendering, timer retargeting after likes, expiry animation, and default feed removal in the shared feed renderer; let the thread page opt into a focused-post expiry callback while templates only opt into the shared Void shell class. Put the Lost Signal shell, dark chrome overrides, timer styling, and expiry motion in `main.css` so page scripts stay behavioral.

**Tech Stack:** Java 21, Spring Boot 3.4, JUnit 5, Mongo-backed Post feature services, Thymeleaf templates, vanilla ES modules, Node parser/test runner, CSS.

---

## File Map

### Backend And Docs

- Modify `website/src/main/resources/application.yml` to enable the default expiration path.
- Create `website/src/test/java/dev/christopherbell/post/PostExpirationConfigurationTest.java` to verify the application default loads as enabled.
- Modify `website/src/test/java/dev/christopherbell/post/PostServiceTest.java` to document the 24-hour create behavior already owned by `PostService`.
- Modify `website/src/main/java/dev/christopherbell/post/README.md` to document the enabled lifespan policy.

### Shared Feed Behavior

- Modify `website/src/main/resources/static/js/lib/feed-render.js` to render the countdown, manage timer lifecycle, retarget lifespan after likes, animate expiry, and expose pure countdown formatting helpers for unit tests.
- Modify `website/src/main/resources/static/js/lib/feed-context.js` to forward the thread page's focused-post expiry callback through the standard renderer context.
- Create `website/src/test/js/feed-render-lifespan.test.js` to verify countdown formatting and expiry threshold math without a DOM harness.
- Modify `website/src/main/resources/static/js/lib/README.md` to record the shared renderer's lifespan responsibility.

### Thread Expiry State

- Modify `website/src/main/resources/static/js/post.js` to replace an expired focused post with a stable thread state and update thread status text.

### Lost Signal Shell

- Modify `website/src/main/resources/templates/void/index.html` to opt the Void feed into the shared shell hook.
- Modify `website/src/main/resources/templates/post.html` to opt thread pages into the shared shell hook.
- Modify `website/src/main/resources/templates/profile.html` to opt signed-in profile feeds into the shared shell hook.
- Modify `website/src/main/resources/templates/user.html` to opt public user feeds into the shared shell hook.
- Modify `website/src/main/resources/static/css/main.css` to style the dark Void shell, shared chrome, post cards, timer, and expiry animation.
- Modify `website/src/main/resources/static/css/README.md` to record the Void shell ownership.

## Task 1: Enable Default Expiration And Lock The Policy

**Files:**
- Create: `website/src/test/java/dev/christopherbell/post/PostExpirationConfigurationTest.java`
- Modify: `website/src/test/java/dev/christopherbell/post/PostServiceTest.java`
- Modify: `website/src/main/resources/application.yml`
- Modify: `website/src/main/java/dev/christopherbell/post/README.md`

- [ ] **Step 1: Write a failing configuration test for the deployed default**

Create `website/src/test/java/dev/christopherbell/post/PostExpirationConfigurationTest.java`:

```java
package dev.christopherbell.post;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class PostExpirationConfigurationTest {
  @Test
  void applicationDefaultsEnablePostExpiration() {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));

    var properties = yaml.getObject();

    assertEquals("true", properties.getProperty("posts.expiration.enabled"));
  }
}
```

- [ ] **Step 2: Run the new configuration test and confirm it fails against the current default**

Run:

```powershell
.\gradlew.bat :website:test --tests dev.christopherbell.post.PostExpirationConfigurationTest
```

Expected: `PostExpirationConfigurationTest.applicationDefaultsEnablePostExpiration` fails because `posts.expiration.enabled` is currently `false`.

- [ ] **Step 3: Add service coverage for the base 24-hour lifespan on creation**

Extend `website/src/test/java/dev/christopherbell/post/PostServiceTest.java` imports:

```java
import org.mockito.ArgumentCaptor;
```

Add this test after the valid create-path test:

```java
  @Test
  @DisplayName("Create assigns the 24 hour base expiration when expiration is enabled")
  public void testCreatePost_whenExpirationEnabled_AssignsBaseLifespan() throws Exception {
    var existing = AccountServiceStub.getAccountWhenExistsStub();
    var service = spy(postService);
    doReturn(existing.getId()).when(service).getSelfId();
    when(accountRepository.findById(eq(existing.getId()))).thenReturn(Optional.of(existing));
    when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(postMapper.toDetail(any(Post.class))).thenReturn(PostDetail.builder().id("p1").build());

    service.createPost(PostCreateRequest.builder().text("still here").build());

    var savedPost = ArgumentCaptor.forClass(Post.class);
    verify(postRepository).save(savedPost.capture());
    assertEquals(
        savedPost.getValue().getCreatedOn().plus(Duration.ofHours(24)),
        savedPost.getValue().getExpiresOn());
  }
```

- [ ] **Step 4: Enable the application default and document the behavior**

Change `website/src/main/resources/application.yml`:

```yaml
posts:
  expiration:
    enabled: true
    cleanup-interval: 600000
```

Replace the expiration design note in `website/src/main/java/dev/christopherbell/post/README.md` with:

```markdown
- Expiration is enabled by default. New posts start with a 24-hour lifespan,
  each active like adds 24 hours from the post creation timestamp, and removing
  a like removes that extension. Missing expiration timestamps are repaired on
  read and during cleanup so older data remains usable.
```

- [ ] **Step 5: Verify the backend policy**

Run:

```powershell
.\gradlew.bat :website:test --tests dev.christopherbell.post.PostExpirationConfigurationTest --tests dev.christopherbell.post.PostServiceTest
```

Expected: the configuration test and Post service tests pass, including create, like/unlike expiration, and cleanup backfill coverage.

- [ ] **Step 6: Commit the backend slice**

```powershell
git add website/src/test/java/dev/christopherbell/post/PostExpirationConfigurationTest.java website/src/test/java/dev/christopherbell/post/PostServiceTest.java website/src/main/resources/application.yml website/src/main/java/dev/christopherbell/post/README.md
git commit -m "feat: enable void post expiration"
```

## Task 2: Add Shared Lifespan Formatting Coverage

**Files:**
- Create: `website/src/test/js/feed-render-lifespan.test.js`
- Modify: `website/src/main/resources/static/js/lib/feed-render.js`

- [ ] **Step 1: Write failing Node tests for the countdown helpers**

Create `website/src/test/js/feed-render-lifespan.test.js`:

```javascript
import assert from 'node:assert/strict';
import test from 'node:test';

import {
  formatLifespanCountdown,
  remainingLifespanMs
} from '../../main/resources/static/js/lib/feed-render.js';

test('formatLifespanCountdown shows days and a tabular clock', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now + 90061000).toISOString();

  assert.equal(formatLifespanCountdown(expiresOn, now), '1d 01:01:01');
});

test('formatLifespanCountdown shows the final second before expiry', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now + 1000).toISOString();

  assert.equal(formatLifespanCountdown(expiresOn, now), '00:00:01');
});

test('remainingLifespanMs returns zero once a post has expired', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now - 1).toISOString();

  assert.equal(remainingLifespanMs(expiresOn, now), 0);
});
```

- [ ] **Step 2: Run the Node tests and confirm the helper exports are missing**

Run:

```powershell
node --experimental-default-type=module --test website/src/test/js/feed-render-lifespan.test.js
```

Expected: the test runner fails because `feed-render.js` does not yet export `formatLifespanCountdown` or `remainingLifespanMs`.

- [ ] **Step 3: Add the pure countdown helpers to the shared renderer**

Add these constants and exports near the top of `website/src/main/resources/static/js/lib/feed-render.js`:

```javascript
const LIFESPAN_TICK_MS = 1000;
const EXPIRY_ANIMATION_MS = 560;

export function remainingLifespanMs(expiresOn, now = Date.now()) {
  if (!expiresOn) return null;
  const delta = new Date(expiresOn).getTime() - now;
  if (!Number.isFinite(delta)) return null;
  return Math.max(0, delta);
}

export function formatLifespanCountdown(expiresOn, now = Date.now()) {
  const remaining = remainingLifespanMs(expiresOn, now);
  if (remaining === null) return '';

  const totalSeconds = Math.max(0, Math.ceil(remaining / 1000));
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const clock = [hours, minutes, seconds].map(value => String(value).padStart(2, '0')).join(':');
  return days > 0 ? `${days}d ${clock}` : clock;
}
```

- [ ] **Step 4: Verify the countdown helper tests pass**

Run:

```powershell
node --experimental-default-type=module --test website/src/test/js/feed-render-lifespan.test.js
```

Expected: all three helper tests pass.

- [ ] **Step 5: Commit the pure helper slice**

```powershell
git add website/src/test/js/feed-render-lifespan.test.js website/src/main/resources/static/js/lib/feed-render.js
git commit -m "test: cover void lifespan countdown formatting"
```

## Task 3: Render Countdown Timers And Expire Shared Cards

**Files:**
- Modify: `website/src/main/resources/static/js/lib/feed-context.js`
- Modify: `website/src/main/resources/static/js/lib/feed-render.js`
- Modify: `website/src/main/resources/static/js/lib/README.md`

- [ ] **Step 1: Keep the standard renderer context aligned with the server contract**

Update the like-action return docs in `website/src/main/resources/static/js/lib/feed-context.js`:

```javascript
 * @returns {(postId:string)=>Promise<{likesCount:number, liked:boolean, expiresOn?:string}>}
```

Update `makeRendererContext` so focused pages can forward the shared renderer expiry callback:

```javascript
export function makeRendererContext({
  fetchJson,
  authHeaders,
  sanitize,
  formatWhen,
  isLoggedIn,
  canDelete,
  currentUserName,
  suppressParentContext = false,
  onExpire = null
}) {
  const fetchPost = createRootFetcher(fetchJson);
  return {
    sanitize,
    formatWhen,
    isLoggedIn,
    canDelete,
    fetchRoot: fetchPost,
    fetchParent: fetchPost,
    fetchThread: createThreadFetcher(fetchJson, authHeaders),
    onLike: onLikeAction(fetchJson, authHeaders),
    onDelete: onDeleteAction(fetchJson, authHeaders),
    onReply: onReplyAction(fetchJson, authHeaders),
    currentUserName: currentUserName || null,
    suppressParentContext,
    onExpire
  };
}
```

- [ ] **Step 2: Add the shared lifespan markup to post cards**

Change the shared renderer docblock callback contract to include `expiresOn` and the optional expiry hook:

```javascript
 * @param {object} post feed item ({ id, username, accountId, text, createdOn, lastUpdatedOn, level, rootId, likesCount, liked, expiresOn })
 * @param {object} ctx  context with small helpers:
 *  - onLike(postId): Promise<{likesCount:number, liked:boolean, expiresOn?:string}>
 *  - onExpire(post, item): void
```

Inside the `.post-author` markup in `createFeedItem`, render the countdown slot after the timestamp:

```javascript
          <div class="post-author">
            <a href="/u/${encodeURIComponent(post.username || '')}" class="post-handle">${handle}</a>
            <small>${when}</small>
            ${post.expiresOn ? '<span class="post-lifespan" data-post-lifespan aria-label="Post lifespan remaining"></span>' : ''}
          </div>
```

- [ ] **Step 3: Add the card timer controller and Lost Signal expiry lifecycle**

Add these helpers below `postPermalink` in `website/src/main/resources/static/js/lib/feed-render.js`:

```javascript
function expireFeedItem(item, post, ctx) {
  if (item.dataset.expiring === 'true') return;
  item.dataset.expiring = 'true';
  item.classList.add('post-item-expiring');

  window.setTimeout(() => {
    if (typeof ctx.onExpire === 'function') {
      ctx.onExpire(post, item);
      return;
    }
    item.remove();
  }, EXPIRY_ANIMATION_MS);
}

function startLifespanTimer(item, post, ctx) {
  const label = item.querySelector('[data-post-lifespan]');
  if (!label || !post.expiresOn) {
    return { update() {}, stop() {} };
  }

  let expiresOn = post.expiresOn;
  let intervalId = null;

  const stop = () => {
    if (intervalId !== null) {
      window.clearInterval(intervalId);
      intervalId = null;
    }
  };

  const tick = () => {
    if (!item.isConnected) {
      stop();
      return;
    }

    const remaining = remainingLifespanMs(expiresOn);
    if (remaining === null) {
      label.textContent = '';
      label.classList.add('d-none');
      stop();
      return;
    }

    label.classList.remove('d-none');
    label.textContent = formatLifespanCountdown(expiresOn);
    label.classList.toggle('is-ending', remaining <= 60 * 60 * 1000);

    if (remaining === 0) {
      stop();
      expireFeedItem(item, post, ctx);
    }
  };

  tick();
  intervalId = window.setInterval(tick, LIFESPAN_TICK_MS);

  return {
    update(nextExpiresOn) {
      if (!nextExpiresOn) return;
      expiresOn = nextExpiresOn;
      post.expiresOn = nextExpiresOn;
      tick();
    },
    stop
  };
}
```

- [ ] **Step 4: Start the timer for every rendered card and retarget it from like responses**

After mention-safe body rendering in `createFeedItem`, start the timer:

```javascript
  const lifespanTimer = startLifespanTimer(item, post, ctx);
```

Inside the existing successful like block, after the heart icon updates, retarget the timer from the server response:

```javascript
        if (updated.expiresOn) {
          lifespanTimer.update(updated.expiresOn);
        }
```

Keep the existing `catch` branch unchanged so a failed like request does not fake a lifespan change.

- [ ] **Step 5: Document the new shared renderer responsibility**

Update the `feed-render.js` bullet in `website/src/main/resources/static/js/lib/README.md`:

```markdown
- `feed-render.js` renders post cards, mention links, likes, replies, menus,
  inline reply composers, shared lifespan countdowns, and expiry removal motion.
```

Add this design note in the same README:

```markdown
- Shared lifespan timers use the feed item's server-returned `expiresOn`
  timestamp. Like handlers must retarget the shared card timer from the updated
  feed item instead of calculating browser-side extensions.
```

- [ ] **Step 6: Verify shared feed JavaScript**

Run:

```powershell
node --experimental-default-type=module --test website/src/test/js/feed-render-lifespan.test.js
node --check website/src/main/resources/static/js/lib/feed-context.js
node --check website/src/main/resources/static/js/lib/feed-render.js
```

Expected: the Node tests pass and the syntax check exits cleanly.

- [ ] **Step 7: Commit the shared renderer slice**

```powershell
git add website/src/main/resources/static/js/lib/feed-context.js website/src/main/resources/static/js/lib/feed-render.js website/src/main/resources/static/js/lib/README.md
git commit -m "feat: show void post lifespan countdowns"
```

## Task 4: Give Focused Thread Expiry A Stable State

**Files:**
- Modify: `website/src/main/resources/static/js/post.js`

- [ ] **Step 1: Add the focused-post expiry renderer**

Add this helper after `renderThreadSummary` in `website/src/main/resources/static/js/post.js`:

```javascript
function renderExpiredRootState(root) {
  root.innerHTML = `
    <div class="thread-expired-state" role="status">
      <p class="thread-label">Signal lost</p>
      <h2>This post expired in the Void.</h2>
      <p>The thread context may still show active replies until their own lifespan ends.</p>
      <a class="btn btn-outline-light btn-sm" href="/void">Back to feed</a>
    </div>`;

  const heroMeta = document.getElementById('postHeroMeta');
  if (heroMeta) heroMeta.textContent = 'The selected post reached the end of its lifespan.';

  const statusEl = document.getElementById('threadStatus');
  if (statusEl) {
    statusEl.textContent = 'Expired';
    statusEl.className = 'thread-status-pill is-expired';
  }
}
```

- [ ] **Step 2: Pass the focused expiry callback through the root renderer context**

Replace the `ctx` construction in `renderRoot` with:

```javascript
  const ctx = makeRendererContext({
    fetchJson,
    authHeaders,
    sanitize,
    formatWhen,
    isLoggedIn,
    canDelete: canDeleteFor(currentUser),
    currentUserName: currentUser?.username || null,
    suppressParentContext: true,
    onExpire: () => renderExpiredRootState(root)
  });
```

The replies list continues to use the default shared renderer removal behavior.

- [ ] **Step 3: Verify the thread script parses**

Run:

```powershell
node --check website/src/main/resources/static/js/post.js
```

Expected: the syntax check exits cleanly.

- [ ] **Step 4: Commit the thread expiry slice**

```powershell
git add website/src/main/resources/static/js/post.js
git commit -m "feat: show expired state for void threads"
```

## Task 5: Add The Lost Signal Shell And Void-Aware Chrome

**Files:**
- Modify: `website/src/main/resources/templates/void/index.html`
- Modify: `website/src/main/resources/templates/post.html`
- Modify: `website/src/main/resources/templates/profile.html`
- Modify: `website/src/main/resources/templates/user.html`
- Modify: `website/src/main/resources/static/css/main.css`
- Modify: `website/src/main/resources/static/css/README.md`

- [ ] **Step 1: Add one shared shell hook to every Void-related template**

Update the four body tags:

```html
<body class="site-page void-shell-page void-page">
```

```html
<body class="site-page void-shell-page post-page">
```

```html
<body class="site-page void-shell-page profile-page">
```

```html
<body class="site-page void-shell-page user-page">
```

- [ ] **Step 2: Add the dark Lost Signal shell and shared chrome styling**

Add these rules near the existing site hero and feed styles in `website/src/main/resources/static/css/main.css`:

```css
.void-shell-page {
    min-height: 100vh;
    color: #e8f6ff;
    background:
        linear-gradient(rgba(18, 52, 67, 0.11) 1px, transparent 1px),
        linear-gradient(90deg, rgba(18, 52, 67, 0.08) 1px, transparent 1px),
        linear-gradient(180deg, rgba(18, 137, 168, 0.18), transparent 26rem),
        #04070c;
    background-size: 44px 44px, 44px 44px, auto, auto;
}

.void-shell-page .site-main {
    min-height: calc(100vh - 9rem);
    background: transparent;
}

.void-shell-page #nav .navbar {
    border-bottom: 1px solid rgba(128, 222, 255, 0.16);
    background: rgba(3, 8, 14, 0.96) !important;
}

.void-shell-page #footer footer {
    margin-top: 0 !important;
    border-top: 1px solid rgba(128, 222, 255, 0.12);
    color: rgba(221, 241, 250, 0.72);
    background: rgba(3, 8, 14, 0.96);
}

.void-shell-page .site-hero,
.void-shell-page .site-content {
    background: transparent;
}
```

- [ ] **Step 3: Restyle Void surfaces and countdown states without changing non-Void pages**

Add these scoped rules with the feed/card styles in `website/src/main/resources/static/css/main.css`:

```css
.void-shell-page .feed-composer-card,
.void-shell-page .feed-empty-state,
.void-shell-page .profile-card,
.void-shell-page .profile-posts-panel,
.void-shell-page .profile-stat,
.void-shell-page .thread-root-panel,
.void-shell-page .thread-replies-panel,
.void-shell-page .thread-composer-panel,
.void-shell-page .thread-summary-card,
.void-shell-page .post-shell {
    border: 1px solid rgba(128, 222, 255, 0.14);
    color: #e8f6ff;
    background: rgba(5, 13, 21, 0.88);
    box-shadow: 0 18px 44px rgba(0, 0, 0, 0.32);
}

.void-shell-page .post-item {
    transform-origin: center center;
}

.void-shell-page .post-author,
.void-shell-page .post-author small,
.void-shell-page .composer-hint,
.void-shell-page .thread-summary-card dt,
.void-shell-page .profile-detail-grid dt {
    color: rgba(218, 239, 248, 0.66);
}

.void-shell-page .post-handle,
.void-shell-page .home-kicker,
.void-shell-page .profile-label,
.void-shell-page .thread-label {
    color: #7fdfff;
}

.post-lifespan {
    display: inline-flex;
    align-items: center;
    min-height: 1.5rem;
    padding: 0.15rem 0.45rem;
    border: 1px solid rgba(128, 222, 255, 0.16);
    border-radius: 999px;
    color: rgba(218, 239, 248, 0.78);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
    background: rgba(20, 79, 98, 0.24);
}

.post-lifespan.is-ending {
    border-color: rgba(255, 154, 154, 0.32);
    color: #ffd0d0;
}

.post-item-expiring {
    animation: void-signal-collapse 560ms ease-in forwards;
    overflow: hidden;
}

.thread-expired-state {
    display: grid;
    gap: 0.75rem;
    padding: 1.25rem;
    border: 1px dashed rgba(128, 222, 255, 0.22);
    border-radius: 8px;
    color: rgba(232, 246, 255, 0.82);
    background: rgba(2, 7, 12, 0.7);
}

@keyframes void-signal-collapse {
    0% {
        max-height: 40rem;
        opacity: 1;
        filter: blur(0);
        transform: scale(1);
    }
    70% {
        opacity: 0.38;
        filter: blur(2px);
        transform: scale(0.82, 0.16);
    }
    100% {
        max-height: 0;
        margin-block: 0;
        opacity: 0;
        filter: blur(5px);
        transform: scale(0.08, 0);
    }
}
```

- [ ] **Step 4: Document the Void shell ownership**

Add this bullet to `website/src/main/resources/static/css/README.md` under `How It Works`:

```markdown
- Void-related templates opt into `void-shell-page`; `main.css` owns the Lost
  Signal shell, Void-aware nav/footer treatment, shared lifespan countdown
  styling, and expiry motion for those pages.
```

- [ ] **Step 5: Verify the template and CSS slice in the browser**

Run the app:

```powershell
.\gradlew.bat :website:bootRun
```

Open these routes with seeded or local post data:

```text
http://localhost:8081/void
http://localhost:8081/profile
http://localhost:8081/u/{username}
http://localhost:8081/p/{postId}
```

Expected:

- The viewport stays dark from nav through footer on each Void-related page.
- Nav/footer structure and links remain familiar.
- Post cards show a readable per-second lifespan timer without crowding narrow headers.
- Like and unlike responses retarget the visible timer from server data.
- A timer reaching zero collapses the card from feed views.
- The focused thread post leaves the stable expired state after its collapse.

- [ ] **Step 6: Commit the Lost Signal slice**

```powershell
git add website/src/main/resources/templates/void/index.html website/src/main/resources/templates/post.html website/src/main/resources/templates/profile.html website/src/main/resources/templates/user.html website/src/main/resources/static/css/main.css website/src/main/resources/static/css/README.md
git commit -m "feat: theme void pages as lost signal"
```

## Task 6: Run Final Verification

**Files:**
- Verify all files touched in Tasks 1-5.

- [ ] **Step 1: Run focused frontend verification**

```powershell
node --experimental-default-type=module --test website/src/test/js/feed-render-lifespan.test.js
node --check website/src/main/resources/static/js/lib/feed-context.js
node --check website/src/main/resources/static/js/lib/feed-render.js
node --check website/src/main/resources/static/js/post.js
```

Expected: the Node tests pass and both parser checks exit cleanly.

- [ ] **Step 2: Run backend verification for the larger shared/config change**

```powershell
.\gradlew.bat :website:test
```

Expected: the website test suite passes.

- [ ] **Step 3: Review the final diff for scope**

```powershell
git diff --stat
git diff -- website/src/main/resources/application.yml website/src/main/resources/static/js/lib/feed-render.js website/src/main/resources/static/js/post.js website/src/main/resources/static/css/main.css
```

Expected: the diff is limited to Void expiration defaults, shared lifespan behavior, Void shell hooks/styling, tests, and docs.
