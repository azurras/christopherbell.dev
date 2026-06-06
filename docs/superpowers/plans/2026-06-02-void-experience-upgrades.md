# Void Experience Upgrades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the Void experience with composer previews, stronger thread navigation, richer image handling, notification preferences, trust/moderation controls, profile stats, performance improvements, and accessibility coverage.

**Architecture:** Implement this as phased, independently testable slices. Keep backend behavior in the narrow feature package that owns it (`post`, `notification`, `account`, `report`) and keep browser behavior in vanilla JavaScript modules under `website/src/main/resources/static/js`. Do not create a framework, npm workflow, or broad cross-feature service.

**Tech Stack:** Java 21, Spring Boot 3.4, MongoDB, Thymeleaf, vanilla JavaScript ES modules, Bootstrap, `node --test`, JUnit.

---

## Worktree Warning

This repository is already heavily modified. Before implementation, run:

```bash
git status --short
```

Preserve unrelated dirty files. Stage only files changed by the current task. Do not run destructive git commands.

## Phase Order

1. Composer preview.
2. Thread navigation improvements.
3. Image lightbox, fallback, and multi-image layout.
4. Notification settings.
5. Trust and moderation tools.
6. Public profile stats.
7. Performance pass.
8. Accessibility pass.

Each phase should update the relevant README in the same change and run the smallest useful tests first. Run `./gradlew :website:test` after backend or security changes.

## Planned File Map

- Modify `website/src/main/resources/templates/index.html`: add composer preview mount point.
- Modify `website/src/main/resources/templates/post.html`: add thread controls and accessible landmarks.
- Modify `website/src/main/resources/templates/profile.html`: render new public profile stats and trust controls.
- Modify `website/src/main/resources/templates/user.html`: render public profile stats and trust controls.
- Modify `website/src/main/resources/templates/notifications.html`: add notification preference panel.
- Modify `website/src/main/resources/static/js/lib/composer.js`: wire live preview lifecycle.
- Create `website/src/main/resources/static/js/lib/composer-preview.js`: convert draft text into sanitized preview model and markup.
- Modify `website/src/main/resources/static/js/lib/feed-render.js`: image click/error hooks, media lazy behavior, accessible action labels.
- Modify `website/src/main/resources/static/js/lib/thread-navigation.js`: newest-reply and collapsible-branch model helpers.
- Create `website/src/main/resources/static/js/lib/image-lightbox.js`: shared image preview dialog behavior.
- Create `website/src/main/resources/static/js/lib/lazy-media.js`: IntersectionObserver media loading helper.
- Modify `website/src/main/resources/static/js/post.js`: wire thread jump/collapse controls.
- Modify `website/src/main/resources/static/js/profile.js`: render profile stats and user trust actions.
- Modify `website/src/main/resources/static/js/user-feed.js`: render user trust actions.
- Modify `website/src/main/resources/static/js/notifications.js`: render and save notification preferences.
- Modify `website/src/main/resources/static/js/components/nav.js`: respect notification preferences when offering browser notifications.
- Modify `website/src/main/resources/static/js/lib/api.js`: add notification preference, account trust, and hidden-thread routes.
- Modify `website/src/main/resources/static/css/main.css`: style preview, thread controls, lightbox, trust controls, profile stats, focus states, and responsive layout.
- Create `website/src/main/java/dev/christopherbell/notification/preference/NotificationPreference.java`: Mongo document for per-user notification toggles.
- Create `website/src/main/java/dev/christopherbell/notification/preference/NotificationPreferenceRepository.java`: persistence boundary.
- Create `website/src/main/java/dev/christopherbell/notification/preference/NotificationPreferenceService.java`: preference read/update and delivery checks.
- Create `website/src/main/java/dev/christopherbell/notification/model/NotificationPreferenceDetail.java`: API response model.
- Create `website/src/main/java/dev/christopherbell/notification/model/NotificationPreferenceUpdateRequest.java`: API update request.
- Modify `website/src/main/java/dev/christopherbell/notification/NotificationController.java`: add preference endpoints.
- Modify `website/src/main/java/dev/christopherbell/notification/delivery/NotificationDeliveryService.java`: skip disabled notification types.
- Create `website/src/main/java/dev/christopherbell/account/trust/AccountTrustRelationship.java`: mute/block relationship document.
- Create `website/src/main/java/dev/christopherbell/account/trust/AccountTrustRepository.java`: relationship persistence boundary.
- Create `website/src/main/java/dev/christopherbell/account/trust/AccountTrustService.java`: mute/block/unmute/unblock behavior.
- Create `website/src/main/java/dev/christopherbell/account/trust/AccountTrustController.java`: account trust API.
- Create `website/src/main/java/dev/christopherbell/account/trust/model/AccountTrustActionRequest.java`: trust request DTO.
- Create `website/src/main/java/dev/christopherbell/account/trust/model/AccountTrustDetail.java`: trust response DTO.
- Create `website/src/main/java/dev/christopherbell/post/hide/HiddenPostThread.java`: per-user hidden thread document.
- Create `website/src/main/java/dev/christopherbell/post/hide/HiddenPostThreadRepository.java`: hidden-thread persistence boundary.
- Create `website/src/main/java/dev/christopherbell/post/hide/HiddenPostThreadService.java`: hide/unhide and feed exclusion helper.
- Create `website/src/main/java/dev/christopherbell/post/hide/HiddenPostThreadController.java`: hidden thread API.
- Modify `website/src/main/java/dev/christopherbell/post/feed/PostFeedService.java`: filter muted/blocked/hidden content for signed-in users.
- Modify `website/src/main/java/dev/christopherbell/message/delivery/MessageDeliveryService.java`: prevent sends to blocking or blocked accounts.
- Modify `website/src/main/java/dev/christopherbell/report/moderation/ReportModerationService.java`: expose repeat-report counts.
- Create `website/src/main/java/dev/christopherbell/report/model/ReportModerationDetail.java`: API response model with report data plus repeat-report aggregate counts.
- Modify `website/src/main/java/dev/christopherbell/account/model/dto/AccountProfile.java`: add profile stats fields.
- Modify `website/src/main/java/dev/christopherbell/account/profile/AccountProfileService.java`: calculate public stats.
- Modify package READMEs for `post`, `notification`, `account`, `report`, `static/js`, `static/js/lib`, and `static/css`.

---

## Task 1: Composer Preview

**Intent:** When a signed-in user types a post, show a live preview with mention links, clickable URLs, image embeds, YouTube embeds, and the same character count rules used at submit time.

**Files:**
- Create: `website/src/main/resources/static/js/lib/composer-preview.js`
- Modify: `website/src/main/resources/static/js/lib/composer.js`
- Modify: `website/src/main/resources/templates/index.html`
- Modify: `website/src/main/resources/static/css/main.css`
- Test: `website/src/test/js/composer-preview.test.js`
- Test: create `website/src/test/js/composer.test.js`

- [ ] **Step 1: Write composer preview unit tests**

Create `website/src/test/js/composer-preview.test.js`:

```js
import assert from 'node:assert/strict';
import test from 'node:test';

import {
  composerPreviewModel,
  composerPreviewMarkup
} from '../../main/resources/static/js/lib/composer-preview.js';

const sanitize = value => String(value ?? '')
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#39;');

test('composerPreviewModel trims empty text and reports remaining characters', () => {
  const model = composerPreviewModel('   ', 280);

  assert.equal(model.hasContent, false);
  assert.equal(model.remaining, 280);
  assert.equal(model.overLimit, false);
});

test('composerPreviewModel detects over-limit drafts', () => {
  const model = composerPreviewModel('x'.repeat(281), 280);

  assert.equal(model.hasContent, true);
  assert.equal(model.remaining, -1);
  assert.equal(model.overLimit, true);
});

test('composerPreviewMarkup links mentions and renders supported rich embeds', () => {
  const markup = composerPreviewMarkup(
    composerPreviewModel('hey @alice https://example.com/cat.jpg', 280),
    sanitize
  );

  assert.match(markup, /class="composer-preview"/);
  assert.match(markup, /href="\/u\/alice"/);
  assert.match(markup, /post-rich-image/);
});

test('composerPreviewMarkup renders a quiet empty state', () => {
  const markup = composerPreviewMarkup(composerPreviewModel('', 280), sanitize);

  assert.match(markup, /composer-preview-empty/);
  assert.doesNotMatch(markup, /post-rich-image/);
});
```

- [ ] **Step 2: Run the failing preview test**

Run:

```bash
node --test website/src/test/js/composer-preview.test.js
```

Expected: fail because `composer-preview.js` does not exist.

- [ ] **Step 3: Implement `composer-preview.js`**

Create `website/src/main/resources/static/js/lib/composer-preview.js`:

```js
import { linkMentions } from './util.js';
import { richEmbedMarkupForPost } from './feed-render.js';

/**
 * Build a deterministic preview model for draft post text.
 */
export function composerPreviewModel(text, maxLength = 280) {
  const rawText = String(text ?? '');
  const trimmedText = rawText.trim();
  return {
    text: rawText,
    trimmedText,
    hasContent: trimmedText.length > 0,
    length: rawText.length,
    maxLength,
    remaining: maxLength - rawText.length,
    overLimit: rawText.length > maxLength
  };
}

/**
 * Render sanitized preview markup using the same rich embed detector as feed cards.
 */
export function composerPreviewMarkup(model, sanitize) {
  if (typeof sanitize !== 'function') return '';
  if (!model?.hasContent) {
    return '<div class="composer-preview composer-preview-empty">Preview appears as you type.</div>';
  }

  const linkedText = linkMentions(model.text);
  const richEmbeds = richEmbedMarkupForPost({ text: model.text, linkPreviews: [] }, sanitize);
  const counterClass = model.overLimit ? ' composer-preview-count-over' : '';
  return `<section class="composer-preview" aria-label="Post preview">
    <div class="composer-preview-label">Preview</div>
    <p class="composer-preview-text">${linkedText}</p>
    ${richEmbeds}
    <div class="composer-preview-count${counterClass}">${model.remaining} characters remaining</div>
  </section>`;
}
```

- [ ] **Step 4: Wire preview into the composer helper**

Modify `website/src/main/resources/static/js/lib/composer.js`:

```js
import { composerPreviewMarkup, composerPreviewModel } from './composer-preview.js';
import { sanitize } from './util.js';
```

Extend selector docs with `preview`.

Inside `initComposer`, add:

```js
const preview = document.querySelector(selectors.preview);

function updatePreview() {
  if (!preview || !textarea) return;
  preview.innerHTML = composerPreviewMarkup(
    composerPreviewModel(textarea.value || '', maxLength),
    sanitize
  );
}
```

Update `updateCounter()` to call `updatePreview()` after updating the counter. Update `reset()` to call `updatePreview()` after clearing the textarea. Call `updatePreview()` once during initialization.

- [ ] **Step 5: Add the preview mount to the home composer**

Modify `website/src/main/resources/templates/index.html` near the existing post textarea:

```html
<div id="composerPreview" class="composer-preview-shell" aria-live="polite"></div>
```

Modify the composer initialization call in the page script that owns the home composer to pass:

```js
preview: '#composerPreview'
```

- [ ] **Step 6: Add CSS**

Add stable rules in `website/src/main/resources/static/css/main.css`:

```css
.composer-preview-shell {
    margin-top: 0.75rem;
}

.composer-preview {
    border: 1px solid rgba(125, 220, 205, 0.28);
    border-radius: 0.5rem;
    padding: 0.85rem;
    background: rgba(5, 12, 18, 0.72);
}

.composer-preview-empty {
    color: #9fb3c3;
}

.composer-preview-label {
    color: #7ddccd;
    font-size: 0.76rem;
    font-weight: 800;
    letter-spacing: 0.08em;
    text-transform: uppercase;
}

.composer-preview-text {
    margin: 0.35rem 0 0;
    white-space: pre-wrap;
}

.composer-preview-count {
    margin-top: 0.5rem;
    color: #9fb3c3;
    font-size: 0.82rem;
}

.composer-preview-count-over {
    color: #ff6b6b;
    font-weight: 800;
}
```

- [ ] **Step 7: Verify composer preview**

Run:

```bash
node --test website/src/test/js/composer-preview.test.js
node --test website/src/test/js/*.test.js
node --check website/src/main/resources/static/js/lib/composer-preview.js
node --check website/src/main/resources/static/js/lib/composer.js
git diff --check -- website/src/main/resources/static/js/lib/composer-preview.js website/src/main/resources/static/js/lib/composer.js website/src/main/resources/templates/index.html website/src/main/resources/static/css/main.css website/src/test/js/composer-preview.test.js
```

Expected: all commands exit 0.

- [ ] **Step 8: Update docs**

Update:

- `website/src/main/resources/static/js/README.md`
- `website/src/main/resources/static/js/lib/README.md`
- `website/src/main/resources/static/css/README.md`
- `website/src/main/java/dev/christopherbell/post/README.md`

Document that the composer preview uses shared feed rich embed detection and does not store preview-only data.

---

## Task 2: Thread Navigation Improvements

**Intent:** Improve `/p/{id}` with "jump to newest reply", collapsible branches, and clearer reply chain indicators.

**Files:**
- Modify: `website/src/main/resources/static/js/lib/thread-navigation.js`
- Modify: `website/src/main/resources/static/js/post.js`
- Modify: `website/src/main/resources/static/css/main.css`
- Modify: `website/src/main/resources/templates/post.html`
- Test: `website/src/test/js/thread-navigation.test.js`

- [ ] **Step 1: Add thread model tests**

Extend `website/src/test/js/thread-navigation.test.js`:

```js
import {
  newestReplyInThread,
  visibleThreadAfterCollapsedBranches
} from '../../main/resources/static/js/lib/thread-navigation.js';

test('newestReplyInThread returns the newest non-root reply', () => {
  const thread = [
    { id: 'root', level: 0, createdOn: '2026-06-01T10:00:00Z' },
    { id: 'old', level: 1, createdOn: '2026-06-01T11:00:00Z' },
    { id: 'new', level: 2, createdOn: '2026-06-01T12:00:00Z' }
  ];

  assert.equal(newestReplyInThread(thread).id, 'new');
});

test('visibleThreadAfterCollapsedBranches hides descendants of collapsed nodes', () => {
  const thread = [
    { id: 'root', level: 0 },
    { id: 'a', parentId: 'root', level: 1 },
    { id: 'b', parentId: 'a', level: 2 },
    { id: 'c', parentId: 'root', level: 1 }
  ];

  assert.deepEqual(
    visibleThreadAfterCollapsedBranches(thread, new Set(['a'])).map(post => post.id),
    ['root', 'a', 'c']
  );
});
```

- [ ] **Step 2: Run the failing thread tests**

Run:

```bash
node --test website/src/test/js/thread-navigation.test.js
```

Expected: fail because the new exports do not exist.

- [ ] **Step 3: Implement thread helpers**

Modify `website/src/main/resources/static/js/lib/thread-navigation.js`:

```js
export function newestReplyInThread(thread = []) {
  return [...thread]
    .filter(post => Number(post.level || 0) > 0)
    .sort((a, b) => new Date(b.createdOn || b.lastUpdatedOn || 0) - new Date(a.createdOn || a.lastUpdatedOn || 0))[0] || null;
}

export function visibleThreadAfterCollapsedBranches(thread = [], collapsedIds = new Set()) {
  const hidden = new Set();
  const byParent = new Map();
  for (const post of thread) {
    if (!post.parentId) continue;
    const children = byParent.get(post.parentId) || [];
    children.push(post.id);
    byParent.set(post.parentId, children);
  }

  function markDescendants(parentId) {
    for (const childId of byParent.get(parentId) || []) {
      hidden.add(childId);
      markDescendants(childId);
    }
  }

  for (const id of collapsedIds) {
    markDescendants(id);
  }

  return thread.filter(post => !hidden.has(post.id));
}
```

- [ ] **Step 4: Add controls to the post page**

Modify `website/src/main/resources/templates/post.html` near the thread header:

```html
<div class="void-thread-controls" aria-label="Thread controls">
  <button id="jumpNewestReply" type="button" class="void-thread-control">Newest reply</button>
  <button id="expandAllReplies" type="button" class="void-thread-control">Expand all</button>
</div>
```

- [ ] **Step 5: Wire controls in `post.js`**

Modify `website/src/main/resources/static/js/post.js`:

```js
const collapsedBranches = new Set();

function scrollToPost(postId) {
  const target = document.querySelector(`[data-post-id="${CSS.escape(postId)}"]`);
  target?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  target?.focus?.();
}
```

When rendering the thread, filter through `visibleThreadAfterCollapsedBranches(thread, collapsedBranches)`. Add branch toggle buttons to reply rows that have descendants:

```html
<button type="button" class="void-thread-collapse" data-collapse-thread="${sanitize(post.id)}" aria-expanded="true">Collapse branch</button>
```

Wire:

```js
document.querySelector('#jumpNewestReply')?.addEventListener('click', () => {
  const newest = newestReplyInThread(currentThread);
  if (newest) scrollToPost(newest.id);
});

document.querySelector('#expandAllReplies')?.addEventListener('click', () => {
  collapsedBranches.clear();
  renderThread(currentThread);
});
```

- [ ] **Step 6: Add thread CSS**

Add:

```css
.void-thread-controls {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    margin: 0.75rem 0;
}

.void-thread-control,
.void-thread-collapse {
    border: 1px solid rgba(125, 220, 205, 0.34);
    border-radius: 0.5rem;
    background: rgba(5, 12, 18, 0.72);
    color: #e8f7f4;
    padding: 0.45rem 0.7rem;
}

.void-thread-depth-label {
    color: #9fb3c3;
    font-size: 0.78rem;
}
```

- [ ] **Step 7: Verify thread navigation**

Run:

```bash
node --test website/src/test/js/thread-navigation.test.js
node --test website/src/test/js/*.test.js
node --check website/src/main/resources/static/js/lib/thread-navigation.js
node --check website/src/main/resources/static/js/post.js
git diff --check -- website/src/main/resources/static/js/lib/thread-navigation.js website/src/main/resources/static/js/post.js website/src/main/resources/templates/post.html website/src/main/resources/static/css/main.css website/src/test/js/thread-navigation.test.js
```

Expected: all commands exit 0.

---

## Task 3: Image Lightbox, Fallback, And Multi-Image Layout

**Intent:** Image links should feel native: multiple images group cleanly, clicking opens a lightweight dialog, and broken external images show a fallback instead of empty space.

**Files:**
- Create: `website/src/main/resources/static/js/lib/image-lightbox.js`
- Modify: `website/src/main/resources/static/js/lib/feed-render.js`
- Modify: `website/src/main/resources/static/js/home-feed.js`
- Modify: `website/src/main/resources/static/js/profile.js`
- Modify: `website/src/main/resources/static/js/user-feed.js`
- Modify: `website/src/main/resources/static/js/post.js`
- Modify: `website/src/main/resources/static/css/main.css`
- Test: `website/src/test/js/feed-render-lifespan.test.js`
- Test: create `website/src/test/js/image-lightbox.test.js`

- [ ] **Step 1: Add feed-render tests for image grouping**

Extend `website/src/test/js/feed-render-lifespan.test.js`:

```js
test('richEmbedMarkupForPost groups multiple direct images', () => {
  const markup = richEmbedMarkupForPost({
    text: 'one https://example.com/1.jpg two https://example.com/2.png'
  }, text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;'));

  assert.match(markup, /post-rich-embeds-images/);
  assert.match(markup, /data-post-image-src="https:\/\/example.com\/1.jpg"/);
  assert.match(markup, /data-post-image-src="https:\/\/example.com\/2.png"/);
});
```

- [ ] **Step 2: Add image lightbox tests**

Create `website/src/test/js/image-lightbox.test.js`:

```js
import assert from 'node:assert/strict';
import test from 'node:test';

import {
  imageLightboxMarkup,
  imageFallbackMarkup
} from '../../main/resources/static/js/lib/image-lightbox.js';

test('imageLightboxMarkup renders an accessible dialog shell', () => {
  const markup = imageLightboxMarkup('https://example.com/a.jpg');

  assert.match(markup, /role="dialog"/);
  assert.match(markup, /aria-modal="true"/);
  assert.match(markup, /src="https:\/\/example.com\/a.jpg"/);
});

test('imageFallbackMarkup keeps the source link available', () => {
  const markup = imageFallbackMarkup('https://example.com/a.jpg');

  assert.match(markup, /Image unavailable/);
  assert.match(markup, /href="https:\/\/example.com\/a.jpg"/);
});
```

- [ ] **Step 3: Implement `image-lightbox.js`**

Create:

```js
import { sanitize } from './util.js';

export function imageLightboxMarkup(src) {
  return `<div class="post-image-lightbox" role="dialog" aria-modal="true" aria-label="Post image preview">
    <button type="button" class="post-image-lightbox-close" aria-label="Close image preview">&times;</button>
    <img src="${sanitize(src)}" alt="Expanded post image">
  </div>`;
}

export function imageFallbackMarkup(src) {
  return `<div class="post-image-fallback">
    <span>Image unavailable</span>
    <a href="${sanitize(src)}" target="_blank" rel="noopener noreferrer">Open source</a>
  </div>`;
}

export function initPostImageLightbox(root = document) {
  root.addEventListener('click', event => {
    const trigger = event.target.closest('[data-post-image-src]');
    if (!trigger) return;
    event.preventDefault();
    const src = trigger.getAttribute('data-post-image-src');
    if (!src) return;
    const wrapper = document.createElement('div');
    wrapper.innerHTML = imageLightboxMarkup(src);
    const dialog = wrapper.firstElementChild;
    document.body.appendChild(dialog);
    const close = () => dialog.remove();
    dialog.querySelector('.post-image-lightbox-close')?.addEventListener('click', close);
    dialog.addEventListener('click', clickEvent => {
      if (clickEvent.target === dialog) close();
    });
    document.addEventListener('keydown', function handleEscape(keyEvent) {
      if (keyEvent.key !== 'Escape') return;
      document.removeEventListener('keydown', handleEscape);
      close();
    });
  });
}
```

- [ ] **Step 4: Modify image embed markup**

In `richEmbedMarkupForPost`, split image embeds from non-image embeds and render images inside:

```html
<div class="post-rich-embeds-images" data-image-count="2">
```

Each image should be a button:

```html
<button type="button" class="post-rich-image-trigger" data-post-image-src="${sanitize(embed.src)}">
  <img class="post-rich-image" src="${sanitize(embed.src)}" alt="Post image" loading="lazy">
</button>
```

Attach error fallback inside `createFeedItem` after `item.innerHTML`:

```js
for (const image of item.querySelectorAll('.post-rich-image')) {
  image.addEventListener('error', () => {
    const src = image.getAttribute('src') || '';
    const wrapper = document.createElement('div');
    wrapper.innerHTML = imageFallbackMarkup(src);
    image.closest('.post-rich-image-trigger')?.replaceWith(wrapper.firstElementChild);
  }, { once: true });
}
```

- [ ] **Step 5: Initialize lightbox from feed pages**

Import and call once in each feed page module:

```js
import { initPostImageLightbox } from './lib/image-lightbox.js';

initPostImageLightbox();
```

Use the correct relative import in `post.js`, `home-feed.js`, `profile.js`, and `user-feed.js`.

- [ ] **Step 6: Add CSS**

Add:

```css
.post-rich-embeds-images {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
    gap: 0.5rem;
}

.post-rich-image-trigger {
    display: block;
    width: 100%;
    border: 0;
    padding: 0;
    background: #0b1117;
    cursor: zoom-in;
}

.post-image-lightbox {
    position: fixed;
    inset: 0;
    z-index: 2000;
    display: grid;
    place-items: center;
    padding: 1rem;
    background: rgba(0, 0, 0, 0.86);
}

.post-image-lightbox img {
    max-width: min(100%, 72rem);
    max-height: 88vh;
    object-fit: contain;
}

.post-image-lightbox-close {
    position: fixed;
    top: 1rem;
    right: 1rem;
    border: 1px solid rgba(255, 255, 255, 0.35);
    border-radius: 0.5rem;
    background: #050c12;
    color: #fff;
    min-width: 2.5rem;
    min-height: 2.5rem;
}

.post-image-fallback {
    padding: 0.9rem;
    background: #101820;
    color: #d7e6ef;
}
```

- [ ] **Step 7: Verify image behavior**

Run:

```bash
node --test website/src/test/js/feed-render-lifespan.test.js
node --test website/src/test/js/image-lightbox.test.js
node --test website/src/test/js/*.test.js
node --check website/src/main/resources/static/js/lib/image-lightbox.js
node --check website/src/main/resources/static/js/lib/feed-render.js
git diff --check -- website/src/main/resources/static/js/lib/image-lightbox.js website/src/main/resources/static/js/lib/feed-render.js website/src/main/resources/static/css/main.css website/src/test/js/feed-render-lifespan.test.js website/src/test/js/image-lightbox.test.js
```

Expected: all commands exit 0.

---

## Task 4: Notification Settings

**Intent:** Signed-in users can choose which notification categories they receive. Delivery code respects those settings before storing in-app notifications or selecting browser notifications.

**Files:**
- Create: `website/src/main/java/dev/christopherbell/notification/preference/NotificationPreference.java`
- Create: `website/src/main/java/dev/christopherbell/notification/preference/NotificationPreferenceRepository.java`
- Create: `website/src/main/java/dev/christopherbell/notification/preference/NotificationPreferenceService.java`
- Create: `website/src/main/java/dev/christopherbell/notification/preference/README.md`
- Create: `website/src/main/java/dev/christopherbell/notification/model/NotificationPreferenceDetail.java`
- Create: `website/src/main/java/dev/christopherbell/notification/model/NotificationPreferenceUpdateRequest.java`
- Modify: `website/src/main/java/dev/christopherbell/notification/NotificationController.java`
- Modify: `website/src/main/java/dev/christopherbell/notification/delivery/NotificationDeliveryService.java`
- Modify: `website/src/main/resources/static/js/lib/api.js`
- Modify: `website/src/main/resources/static/js/notifications.js`
- Modify: `website/src/main/resources/templates/notifications.html`
- Modify: `website/src/main/resources/static/css/main.css`
- Test: `website/src/test/java/dev/christopherbell/notification/NotificationPreferenceServiceTest.java`
- Test: `website/src/test/java/dev/christopherbell/notification/NotificationControllerTest.java`
- Test: `website/src/test/java/dev/christopherbell/notification/NotificationServiceTest.java`
- Test: `website/src/test/js/notification-ui.test.js`

- [ ] **Step 1: Add backend tests**

Create `NotificationPreferenceServiceTest` with these cases:

```java
@Test
void getPreferences_whenMissing_returnsAllEnabledDefaults() {
  when(permissionService.getSelfId()).thenReturn("acct-1");
  when(repository.findByAccountId("acct-1")).thenReturn(Optional.empty());

  var detail = service.getMyPreferences();

  assertTrue(detail.mentions());
  assertTrue(detail.likes());
  assertTrue(detail.comments());
  assertTrue(detail.messages());
  assertTrue(detail.wflSessions());
}

@Test
void updatePreferences_savesRequestedSettingsForCurrentUser() {
  when(permissionService.getSelfId()).thenReturn("acct-1");
  when(repository.findByAccountId("acct-1")).thenReturn(Optional.empty());
  when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

  var detail = service.updateMyPreferences(new NotificationPreferenceUpdateRequest(false, true, false, true, false));

  assertFalse(detail.mentions());
  assertTrue(detail.likes());
  assertFalse(detail.comments());
  assertTrue(detail.messages());
  assertFalse(detail.wflSessions());
}

@Test
void shouldDeliver_returnsFalseWhenTypeDisabled() {
  when(repository.findByAccountId("acct-1")).thenReturn(Optional.of(NotificationPreference.builder()
      .accountId("acct-1")
      .mentions(true)
      .likes(false)
      .comments(true)
      .messages(true)
      .wflSessions(true)
      .build()));

  assertFalse(service.shouldDeliver("acct-1", NotificationType.LIKE));
}
```

- [ ] **Step 2: Implement model and repository**

Create `NotificationPreference`:

```java
@Document("notification_preferences")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {
  @Id private String id;
  @Indexed(unique = true) private String accountId;
  private boolean mentions;
  private boolean likes;
  private boolean comments;
  private boolean messages;
  private boolean wflSessions;
  @CreatedDate private Instant createdOn;
  @LastModifiedDate private Instant lastUpdatedOn;
}
```

Create repository:

```java
public interface NotificationPreferenceRepository extends MongoRepository<NotificationPreference, String> {
  Optional<NotificationPreference> findByAccountId(String accountId);
}
```

- [ ] **Step 3: Implement service**

Create service with these public methods:

```java
public NotificationPreferenceDetail getMyPreferences()
public NotificationPreferenceDetail updateMyPreferences(NotificationPreferenceUpdateRequest request)
public boolean shouldDeliver(String accountId, NotificationType type)
```

Map missing preference records to all enabled. In `shouldDeliver`, return true for missing records to preserve existing behavior.

- [ ] **Step 4: Add controller endpoints**

In `NotificationController`, add:

```java
@GetMapping(value = V20250914 + "/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("@permissionService.hasAuthority('USER')")
public ResponseEntity<Response<NotificationPreferenceDetail>> getNotificationPreferences()

@PutMapping(value = V20250914 + "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("@permissionService.hasAuthority('USER')")
public ResponseEntity<Response<NotificationPreferenceDetail>> updateNotificationPreferences(@RequestBody NotificationPreferenceUpdateRequest request)
```

- [ ] **Step 5: Gate delivery**

In `NotificationDeliveryService`, call:

```java
if (!notificationPreferenceService.shouldDeliver(recipient.getId(), NotificationType.LIKE)) {
  return;
}
```

Apply this pattern to `MENTION`, `LIKE`, `COMMENT`, `MESSAGE`, and `WFL_SESSION`.

- [ ] **Step 6: Add frontend API routes**

In `static/js/lib/api.js`:

```js
notifications: {
  preferences: '/api/notifications/2025-09-14/preferences'
}
```

Use the existing notification API version already present in `NotificationController`.

- [ ] **Step 7: Render settings on notifications page**

Add a settings panel to `notifications.html`:

```html
<section class="notification-settings" aria-labelledby="notificationSettingsTitle">
  <h2 id="notificationSettingsTitle">Notification settings</h2>
  <div id="notificationSettingsForm"></div>
  <p id="notificationSettingsStatus" aria-live="polite"></p>
</section>
```

In `notifications.js`, render five toggles:

```js
const notificationPreferenceLabels = [
  ['mentions', 'Mentions'],
  ['likes', 'Likes'],
  ['comments', 'Comments'],
  ['messages', 'Messages'],
  ['wflSessions', 'WFL sessions']
];
```

Save with `fetchJson(api.notifications.preferences, { method: 'PUT', headers: authHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(payload) })`.

- [ ] **Step 8: Verify notification settings**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.notification.NotificationPreferenceServiceTest --tests dev.christopherbell.notification.NotificationControllerTest --tests dev.christopherbell.notification.NotificationServiceTest
node --test website/src/test/js/notification-ui.test.js
node --check website/src/main/resources/static/js/notifications.js
node --check website/src/main/resources/static/js/lib/api.js
```

Expected: all commands exit 0.

---

## Task 5: Trust And Moderation Tools

**Intent:** Users can mute users, block users, and hide threads. Admins can see repeat-report context. Suspended users already cannot post or message; this task adds user-level trust controls around what users see and who can contact them.

**Files:**
- Create: `website/src/main/java/dev/christopherbell/account/trust/*`
- Create: `website/src/main/java/dev/christopherbell/account/trust/model/*`
- Create: `website/src/main/java/dev/christopherbell/post/hide/*`
- Modify: `website/src/main/java/dev/christopherbell/post/feed/PostFeedService.java`
- Modify: `website/src/main/java/dev/christopherbell/message/delivery/MessageDeliveryService.java`
- Modify: `website/src/main/java/dev/christopherbell/report/moderation/ReportModerationService.java`
- Modify: `website/src/main/resources/static/js/lib/api.js`
- Modify: `website/src/main/resources/static/js/lib/feed-render.js`
- Modify: `website/src/main/resources/static/js/profile.js`
- Modify: `website/src/main/resources/static/js/user-feed.js`
- Modify: `website/src/main/resources/static/js/back-office.js`
- Test: `website/src/test/java/dev/christopherbell/account/AccountTrustServiceTest.java`
- Test: `website/src/test/java/dev/christopherbell/post/HiddenPostThreadServiceTest.java`
- Test: `website/src/test/java/dev/christopherbell/message/MessageServiceTest.java`
- Test: `website/src/test/java/dev/christopherbell/post/PostServiceTest.java`
- Test: `website/src/test/js/back-office-users.test.js`

- [ ] **Step 1: Define trust model**

Create enum:

```java
public enum AccountTrustType {
  MUTE,
  BLOCK
}
```

Create document:

```java
@Document("account_trust_relationships")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountTrustRelationship {
  @Id private String id;
  @Indexed private String ownerAccountId;
  @Indexed private String targetAccountId;
  private AccountTrustType type;
  @CreatedDate private Instant createdOn;
}
```

Create unique compound index on `ownerAccountId`, `targetAccountId`, and `type` using `@CompoundIndex`.

- [ ] **Step 2: Implement trust service tests**

Test cases:

```java
@Test
void blockUser_storesBlockRelationshipForCurrentUser()

@Test
void muteUser_storesMuteRelationshipForCurrentUser()

@Test
void cannotTrustSelf_throwsInvalidRequestException()

@Test
void isBlockedEitherDirection_returnsTrueWhenEitherUserBlockedTheOther()
```

- [ ] **Step 3: Implement trust service**

Public methods:

```java
public AccountTrustDetail setTrust(String username, AccountTrustType type)
public void clearTrust(String username, AccountTrustType type)
public Set<String> hiddenAccountIdsForSelf()
public boolean isBlockedEitherDirection(String firstAccountId, String secondAccountId)
```

Rules:

- Mute hides target posts from the current user's feeds.
- Block hides target posts and prevents direct messages in either direction.
- Self mute/block throws `InvalidRequestException`.
- Missing target username throws `ResourceNotFoundException`.

- [ ] **Step 4: Add trust controller endpoints**

Create `AccountTrustController`:

```java
@PutMapping(value = "/api/accounts/2026-06-02/trust/{username}", consumes = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("@permissionService.hasAuthority('USER')")
public ResponseEntity<Response<AccountTrustDetail>> setTrust(@PathVariable String username, @RequestBody AccountTrustActionRequest request)

@DeleteMapping(value = "/api/accounts/2026-06-02/trust/{username}/{type}")
@PreAuthorize("@permissionService.hasAuthority('USER')")
public ResponseEntity<Response<Void>> clearTrust(@PathVariable String username, @PathVariable AccountTrustType type)
```

- [ ] **Step 5: Add hidden thread model and API**

Create `HiddenPostThread`:

```java
@Document("hidden_post_threads")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HiddenPostThread {
  @Id private String id;
  @Indexed private String accountId;
  @Indexed private String rootPostId;
  @CreatedDate private Instant createdOn;
}
```

Service methods:

```java
public void hideThread(String postId)
public void unhideThread(String rootPostId)
public Set<String> hiddenRootIdsForSelf()
```

Controller endpoints:

```java
PUT /api/posts/2026-06-02/{postId}/hide-thread
DELETE /api/posts/2026-06-02/{rootPostId}/hide-thread
```

- [ ] **Step 6: Apply feed filtering**

In `PostFeedService`, for signed-in requests:

```java
Set<String> hiddenAccountIds = accountTrustService.hiddenAccountIdsForSelf();
Set<String> hiddenRootIds = hiddenPostThreadService.hiddenRootIdsForSelf();
```

Filter feed items where:

```java
hiddenAccountIds.contains(post.getAccountId())
```

or:

```java
hiddenRootIds.contains(post.getRootId() != null ? post.getRootId() : post.getId())
```

- [ ] **Step 7: Block direct messages**

In `MessageDeliveryService`, before creating a message:

```java
if (accountTrustService.isBlockedEitherDirection(sender.getId(), recipient.getId())) {
  throw new InvalidRequestException("Messages are not available between these accounts.");
}
```

- [ ] **Step 8: Add admin repeat-report visibility**

In `ReportModerationService`, add counts for reported account:

```java
long openReportsForAccount = reportRepository.countByReportedAccountIdAndStatus(reportedAccountId, ReportStatus.OPEN);
long resolvedReportsForAccount = reportRepository.countByReportedAccountIdAndStatus(reportedAccountId, ReportStatus.RESOLVED);
```

Expose those fields in the admin report response consumed by `back-office.js`.

- [ ] **Step 9: Add frontend controls**

In feed card menu, add signed-in controls:

```html
<button class="post-hide-thread-btn" type="button" data-post="${post.id}">Hide thread</button>
```

On user profile pages, add:

```html
<button type="button" class="profile-mute-btn">Mute</button>
<button type="button" class="profile-block-btn">Block</button>
```

Do not show mute/block on your own profile.

- [ ] **Step 10: Verify trust tools**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.account.AccountTrustServiceTest --tests dev.christopherbell.post.HiddenPostThreadServiceTest --tests dev.christopherbell.message.MessageServiceTest --tests dev.christopherbell.post.PostServiceTest
node --test website/src/test/js/back-office-users.test.js
node --check website/src/main/resources/static/js/lib/feed-render.js
node --check website/src/main/resources/static/js/profile.js
node --check website/src/main/resources/static/js/user-feed.js
node --check website/src/main/resources/static/js/back-office.js
```

Expected: all commands exit 0.

---

## Task 6: Public Profile Stats

**Intent:** Public profiles remain username-only but become more useful with safe activity stats.

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/account/model/dto/AccountProfile.java`
- Modify: `website/src/main/java/dev/christopherbell/account/profile/AccountProfileService.java`
- Modify: `website/src/main/java/dev/christopherbell/post/PostRepository.java`
- Modify: `website/src/main/resources/static/js/profile.js`
- Modify: `website/src/main/resources/static/js/user-feed.js`
- Modify: `website/src/main/resources/templates/profile.html`
- Modify: `website/src/main/resources/templates/user.html`
- Test: `website/src/test/java/dev/christopherbell/account/AccountServiceTest.java`
- Test: `website/src/test/java/dev/christopherbell/account/AccountControllerTest.java`
- Test: create `website/src/test/js/profile-stats.test.js`

- [ ] **Step 1: Extend profile DTO**

Add fields:

```java
long postCount,
long replyCount,
long totalLikesReceived
```

Keep existing fields unchanged.

- [ ] **Step 2: Add repository count methods**

In `PostRepository`:

```java
long countByAccountIdAndParentIdIsNull(String accountId);
long countByAccountIdAndParentIdIsNotNull(String accountId);
List<Post> findByAccountId(String accountId);
```

Use a service helper to sum `likesCount` across returned posts for `totalLikesReceived`.

- [ ] **Step 3: Add service tests**

Add:

```java
@Test
void getPublicProfile_includesSafeActivityStats()
```

Assert:

```java
assertEquals(3, profile.postCount());
assertEquals(5, profile.replyCount());
assertEquals(12, profile.totalLikesReceived());
```

- [ ] **Step 4: Render stats**

Add a stat grid to profile templates:

```html
<section class="profile-stat-grid" aria-label="Profile activity stats">
  <div><strong id="profilePostCount">0</strong><span>Posts</span></div>
  <div><strong id="profileReplyCount">0</strong><span>Replies</span></div>
  <div><strong id="profileLikeCount">0</strong><span>Likes received</span></div>
</section>
```

In `profile.js` and `user-feed.js`, fill these values from the profile API.

- [ ] **Step 5: Verify profile stats**

Run:

```bash
./gradlew :website:test --tests dev.christopherbell.account.AccountServiceTest --tests dev.christopherbell.account.AccountControllerTest
node --test website/src/test/js/profile-stats.test.js
node --check website/src/main/resources/static/js/profile.js
node --check website/src/main/resources/static/js/user-feed.js
```

Expected: all commands exit 0.

---

## Task 7: Performance Pass

**Intent:** Heavy rich media should not cost page performance until it is near the viewport. Feed rendering should avoid unnecessary work on initial load.

**Files:**
- Create: `website/src/main/resources/static/js/lib/lazy-media.js`
- Modify: `website/src/main/resources/static/js/lib/feed-render.js`
- Modify: `website/src/main/resources/static/js/home-feed.js`
- Modify: `website/src/main/resources/static/js/profile.js`
- Modify: `website/src/main/resources/static/js/user-feed.js`
- Modify: `website/src/main/resources/static/js/post.js`
- Test: create `website/src/test/js/lazy-media.test.js`
- Test: update `website/src/test/js/feed-render-lifespan.test.js`

- [ ] **Step 1: Add lazy media tests**

Create:

```js
import assert from 'node:assert/strict';
import test from 'node:test';

import { lazyIframeMarkup } from '../../main/resources/static/js/lib/lazy-media.js';

test('lazyIframeMarkup stores iframe source in data-src before activation', () => {
  const markup = lazyIframeMarkup({
    className: 'post-rich-iframe',
    src: 'https://example.com/embed',
    title: 'Example embed',
    allow: 'fullscreen'
  }, value => String(value));

  assert.match(markup, /data-src="https:\/\/example.com\/embed"/);
  assert.doesNotMatch(markup, /\ssrc="https:\/\/example.com\/embed"/);
});
```

- [ ] **Step 2: Implement lazy media helper**

Create:

```js
export function lazyIframeMarkup({ className, src, title, allow }, sanitize) {
  return `<iframe
    class="${sanitize(className)}"
    data-src="${sanitize(src)}"
    title="${sanitize(title)}"
    loading="lazy"
    referrerpolicy="strict-origin-when-cross-origin"
    allow="${sanitize(allow)}"
    allowfullscreen></iframe>`;
}

export function initLazyMedia(root = document) {
  const frames = [...root.querySelectorAll('iframe[data-src]')];
  if (!frames.length) return;

  if (!('IntersectionObserver' in window)) {
    frames.forEach(frame => {
      frame.setAttribute('src', frame.dataset.src);
      frame.removeAttribute('data-src');
    });
    return;
  }

  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const frame = entry.target;
      frame.setAttribute('src', frame.dataset.src);
      frame.removeAttribute('data-src');
      observer.unobserve(frame);
    }
  }, { rootMargin: '500px' });

  frames.forEach(frame => observer.observe(frame));
}
```

- [ ] **Step 3: Use lazy iframes in rich embeds**

In `feed-render.js`, replace direct iframe `src` markup with `lazyIframeMarkup`.

- [ ] **Step 4: Initialize lazy media on feed pages**

In `home-feed.js`, `profile.js`, `user-feed.js`, and `post.js`:

```js
import { initLazyMedia } from './lib/lazy-media.js';

initLazyMedia();
```

Call again after appending new feed items.

- [ ] **Step 5: Verify performance pass**

Run:

```bash
node --test website/src/test/js/lazy-media.test.js
node --test website/src/test/js/feed-render-lifespan.test.js
node --test website/src/test/js/*.test.js
node --check website/src/main/resources/static/js/lib/lazy-media.js
node --check website/src/main/resources/static/js/lib/feed-render.js
```

Expected: all commands exit 0.

---

## Task 8: Accessibility Pass

**Intent:** Feed cards, menus, composer previews, image lightbox, thread controls, notification settings, and trust controls should be keyboard reachable and have coherent focus states.

**Files:**
- Modify: `website/src/main/resources/static/js/lib/feed-render.js`
- Modify: `website/src/main/resources/static/js/lib/image-lightbox.js`
- Modify: `website/src/main/resources/static/js/components/nav.js`
- Modify: `website/src/main/resources/static/js/notifications.js`
- Modify: `website/src/main/resources/static/js/post.js`
- Modify: `website/src/main/resources/static/css/main.css`
- Test: `website/src/test/js/nav-messages-link.test.js`
- Test: `website/src/test/js/notification-ui.test.js`
- Test: `website/src/test/js/thread-navigation.test.js`
- Test: create `website/src/test/js/a11y-markup.test.js`

- [ ] **Step 1: Add markup tests**

Create:

```js
import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

test('main stylesheet includes visible focus states for Void controls', () => {
  const css = fs.readFileSync('website/src/main/resources/static/css/main.css', 'utf8');

  assert.match(css, /:focus-visible/);
  assert.match(css, /\.post-action:focus-visible/);
  assert.match(css, /\.void-thread-control:focus-visible/);
});

test('post template exposes thread controls as a labelled group', () => {
  const html = fs.readFileSync('website/src/main/resources/templates/post.html', 'utf8');

  assert.match(html, /aria-label="Thread controls"/);
});
```

- [ ] **Step 2: Add keyboard behavior**

Rules:

- Escape closes open post action menus.
- Escape closes image lightbox.
- Tab stays inside image lightbox while it is open.
- Enter and Space activate custom image triggers.
- Notification setting toggles use native checkboxes.
- Thread controls use native buttons.

- [ ] **Step 3: Add focus CSS**

Add:

```css
.post-action:focus-visible,
.post-menu-btn:focus-visible,
.post-copy-btn:focus-visible,
.post-report-btn:focus-visible,
.post-delete-btn:focus-visible,
.void-thread-control:focus-visible,
.void-thread-collapse:focus-visible,
.post-rich-image-trigger:focus-visible,
.notification-btn:focus-visible,
.avatar-btn:focus-visible {
    outline: 3px solid #7ddccd;
    outline-offset: 3px;
}
```

- [ ] **Step 4: Verify accessibility pass**

Run:

```bash
node --test website/src/test/js/a11y-markup.test.js
node --test website/src/test/js/nav-messages-link.test.js
node --test website/src/test/js/notification-ui.test.js
node --test website/src/test/js/thread-navigation.test.js
node --test website/src/test/js/*.test.js
node --check website/src/main/resources/static/js/lib/feed-render.js
node --check website/src/main/resources/static/js/lib/image-lightbox.js
node --check website/src/main/resources/static/js/components/nav.js
node --check website/src/main/resources/static/js/notifications.js
node --check website/src/main/resources/static/js/post.js
```

Expected: all commands exit 0.

---

## Final Full Verification

After all phases are complete, run:

```bash
node --test website/src/test/js/*.test.js
./gradlew :website:test
git diff --check
```

Expected:

- JavaScript tests: all pass.
- Website test suite: `BUILD SUCCESSFUL`.
- `git diff --check`: no whitespace errors.

## Documentation Updates Required

Update these docs as the relevant phase lands:

- `website/src/main/java/dev/christopherbell/post/README.md`
- `website/src/main/java/dev/christopherbell/account/README.md`
- `website/src/main/java/dev/christopherbell/account/profile/README.md`
- `website/src/main/java/dev/christopherbell/notification/README.md`
- `website/src/main/java/dev/christopherbell/report/README.md`
- `website/src/main/resources/static/js/README.md`
- `website/src/main/resources/static/js/lib/README.md`
- `website/src/main/resources/static/css/README.md`

## Self-Review Notes

- User-requested items covered:
  - Composer upgrades: Task 1.
  - Better thread UX: Task 2.
  - Image handling polish: Task 3.
  - Notification settings: Task 4.
  - User trust/moderation tools: Task 5.
  - Profile quality: Task 6.
  - Performance pass: Task 7.
  - Accessibility pass: Task 8.
- Scope is intentionally phased because the combined request touches frontend rendering, backend notification delivery, account trust state, post feed filtering, direct messages, report moderation, and profile APIs.
- Every phase has a focused verification command. Backend phases also include targeted Gradle tests.
