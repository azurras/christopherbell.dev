# Void Homepage Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign `/` as a Void-first homepage using the approved Option A Feed Gateway direction.

**Architecture:** Keep the existing Spring MVC route and Thymeleaf template. Change the homepage markup and CSS only, using existing `void-shell-page` conventions and no new browser dependencies.

**Tech Stack:** Java 21, Spring Boot MVC, Thymeleaf, vanilla browser assets, CSS in `main.css`, JUnit MockMvc tests.

---

### Task 1: Add Homepage Rendering Coverage

**Files:**
- Modify: `website/src/test/java/dev/christopherbell/view/ViewControllerTest.java`

- [ ] **Step 1: Write the failing test assertions**

Add assertions to `getHomePage_rendersSocialPreviewMetadata`:

```java
.andExpect(content().string(containsString("Drop into the Void.")))
.andExpect(content().string(containsString("Enter Void")))
.andExpect(content().string(containsString("home-void-gateway")))
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Expected: `getHomePage_rendersSocialPreviewMetadata` fails because the old homepage copy and `home-void-gateway` class are not rendered yet.

### Task 2: Replace Homepage Markup With Option A

**Files:**
- Modify: `website/src/main/resources/templates/index.html`

- [ ] **Step 1: Add Void shell classes**

Set the body to:

```html
<body class="site-page void-shell-page home-page">
```

- [ ] **Step 2: Replace light landing content**

Use a `main` class that includes:

```html
<main role="main" class="site-main home-void-gateway">
```

Render a Void-first hero with one button:

```html
<a href="/void" class="btn btn-primary home-void-primary">Enter Void</a>
```

Render supporting activity and secondary destination cards below or beside the hero.

### Task 3: Add Void Homepage CSS

**Files:**
- Modify: `website/src/main/resources/static/css/main.css`

- [ ] **Step 1: Update homepage classes**

Add or adjust `.home-void-*` styles for:

```css
.home-void-gateway
.home-void-shell
.home-void-hero
.home-void-copy
.home-void-signal-panel
.home-void-signal-card
.home-void-destinations
.home-void-card
```

- [ ] **Step 2: Preserve responsive behavior**

At the existing mobile breakpoint, stack hero and cards:

```css
.home-void-hero,
.home-void-destinations {
    grid-template-columns: 1fr;
}
```

### Task 4: Update Documentation

**Files:**
- Modify: `website/src/main/java/dev/christopherbell/view/README.md`
- Modify: `website/src/main/resources/static/css/README.md`

- [ ] **Step 1: Document the route purpose**

Mention that `/` is the Void-first gateway page.

- [ ] **Step 2: Document CSS ownership**

Mention `.home-void-*` classes in `main.css`.

### Task 5: Verify

**Files:**
- Verify touched files only.

- [ ] **Step 1: Run focused Java test**

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:test --tests dev.christopherbell.view.ViewControllerTest
```

Expected: build successful.

- [ ] **Step 2: Run whitespace check**

```bash
git diff --check -- website/src/main/resources/templates/index.html website/src/main/resources/static/css/main.css website/src/main/java/dev/christopherbell/view/README.md website/src/main/resources/static/css/README.md website/src/test/java/dev/christopherbell/view/ViewControllerTest.java
```

Expected: no output.

- [ ] **Step 3: Render check**

Start the app and fetch `/`:

```bash
./gradlew --no-daemon --project-cache-dir /tmp/codex-gradle-cache-cbell :website:bootRun
curl -sS http://localhost:8081/ -o /tmp/homepage.html
rg -n "Drop into the Void|Enter Void|home-void-gateway" /tmp/homepage.html
```

Expected: all three strings are present.
