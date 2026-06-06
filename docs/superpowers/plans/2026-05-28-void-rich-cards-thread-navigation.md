# Void Rich Cards and Thread Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add allowlisted rich post embeds/cards and better nested thread navigation to Void post detail pages.

**Architecture:** Keep post card rendering centralized in `feed-render.js`. Put thread navigation modeling and markup in a new pure helper module so `post.js` stays focused on page wiring and the behavior is unit-testable.

**Tech Stack:** Vanilla JavaScript ES modules, Node test runner, Thymeleaf, CSS.

---

### Task 1: Rich Embed Tests and Renderer

**Files:**
- Modify: `website/src/test/js/feed-render-lifespan.test.js`
- Modify: `website/src/main/resources/static/js/lib/feed-render.js`
- Modify: `website/src/main/resources/static/css/main.css`

- [ ] **Step 1: Write failing tests for provider parsing and dedupe**

Add tests that import `richEmbedsForPost`, `spotifyEmbedUrl`, `soundCloudEmbedUrl`, `directImageUrl`, and `githubCardDetail`. Cover direct image URLs, Spotify supported types, SoundCloud links, GitHub repo/issue/PR links, and duplicate URLs across post text and `linkPreviews`.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
node --test website/src/test/js/feed-render-lifespan.test.js
```

Expected: FAIL because the new exports do not exist yet.

- [ ] **Step 3: Implement rich provider helpers and rendering**

Add allowlisted URL helpers to `feed-render.js`, create a `richEmbedsForPost(post)` collector, render rich media after post text, and filter generic preview cards for URLs already handled by rich embeds.

- [ ] **Step 4: Add responsive rich embed styles**

Add styles for `.post-rich-embeds`, `.post-rich-embed`, `.post-rich-iframe`, `.post-rich-image`, and `.post-rich-card-github` to `main.css`.

- [ ] **Step 5: Run targeted tests**

Run:

```bash
node --test website/src/test/js/feed-render-lifespan.test.js
node --check website/src/main/resources/static/js/lib/feed-render.js
```

Expected: PASS.

### Task 2: Thread Navigation Helper and Page Wiring

**Files:**
- Create: `website/src/main/resources/static/js/lib/thread-navigation.js`
- Create: `website/src/test/js/thread-navigation.test.js`
- Modify: `website/src/main/resources/static/js/post.js`
- Modify: `website/src/main/resources/templates/post.html`
- Modify: `website/src/main/resources/static/css/main.css`

- [ ] **Step 1: Write failing tests for thread navigation model**

Create tests that import `threadNavigationModel` and `renderThreadNavigation`. Cover previous/next selection, nested indentation, selected item state, and empty/single-post threads.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
node --test website/src/test/js/thread-navigation.test.js
```

Expected: FAIL because `thread-navigation.js` does not exist.

- [ ] **Step 3: Implement the pure helper**

Create `thread-navigation.js` with `threadNavigationModel(items, currentId)` and `renderThreadNavigation(items, currentId)`. Sanitize user-authored snippets and usernames before generating markup.

- [ ] **Step 4: Wire the post page**

Add `<nav id="threadNavigation" class="void-thread-navigation" aria-label="Thread navigation"></nav>` to `post.html`. Import and call `renderThreadNavigation` from `post.js` after the full thread payload loads.

- [ ] **Step 5: Add Void-styled thread nav CSS**

Add styles for `.void-thread-navigation`, `.void-thread-jump-links`, `.void-thread-map`, `.void-thread-map-item`, and selected states.

- [ ] **Step 6: Run targeted tests**

Run:

```bash
node --test website/src/test/js/thread-navigation.test.js
node --check website/src/main/resources/static/js/post.js
node --check website/src/main/resources/static/js/lib/thread-navigation.js
```

Expected: PASS.

### Task 3: Documentation and Verification

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/post/README.md`
- Modify: `website/src/main/resources/static/js/lib/README.md`

- [ ] **Step 1: Update feature docs**

Document the richer allowlisted embeds and the nested Signal Rail behavior on post detail pages.

- [ ] **Step 2: Run JavaScript suite**

Run:

```bash
node --test website/src/test/js/*.test.js
```

Expected: PASS.

- [ ] **Step 3: Run scoped diff check**

Run:

```bash
git diff --check -- website/src/main/resources/static/js/lib/feed-render.js website/src/main/resources/static/js/lib/thread-navigation.js website/src/main/resources/static/js/post.js website/src/main/resources/templates/post.html website/src/main/resources/static/css/main.css website/src/test/js/feed-render-lifespan.test.js website/src/test/js/thread-navigation.test.js website/src/main/java/dev/christopherbell/post/README.md website/src/main/resources/static/js/lib/README.md docs/superpowers/specs/2026-05-28-void-rich-cards-thread-navigation-design.md docs/superpowers/plans/2026-05-28-void-rich-cards-thread-navigation.md
```

Expected: no output.
