import { API } from '../lib/api.js';
import {
  mediaOutputProfile,
  mediaStatusMessage,
  isSharedFolderAccessDenied,
  waitForPlayableMediaJob,
  waitForTerminalMediaJob,
} from '../lib/shared-folder.js';
import {
  clearSharedFolderStreamingAuth,
  prepareSharedFolderMediaAuth,
  sharedFolderStreamingDenial,
} from '../lib/shared-folder-streaming.js';
import {
  createPersistentSiteNavigator,
  createSiteMediaSession,
  persistentNavigationTarget,
  SITE_MEDIA_PLAYER_TAG,
} from '../lib/site-media-player.js';
import {
  authHeaders,
  clearAuthState,
  currentRedirectTarget,
  fetchJson,
  getAuthToken,
  loginRedirectUrl,
} from '../lib/util.js';

const PLAYER_HEIGHT_PROPERTY = '--site-media-player-height';

/** One top-document media owner that survives navigation through a same-origin content shell. */
export class SiteMediaPlayer extends HTMLElement {
  connectedCallback() {
    if (this.initialized) return;
    this.initialized = true;
    this.className = 'site-media-player-host';
    this.hidden = true;
    this.renderStructure();
    this.navigator = this.createNavigator();
    this.session = createSiteMediaSession({
      mount: playback => this.mountPlayback(playback),
      unmount: () => this.unmountPlayback(),
    });
    this.querySelector('[data-site-player-close]').addEventListener('click', () => this.close());
    if ('ResizeObserver' in window) {
      this.resizeObserver = new ResizeObserver(() => this.updatePlayerHeight());
      this.resizeObserver.observe(this.querySelector('.site-media-player-bar'));
    } else {
      window.addEventListener('resize', () => this.updatePlayerHeight());
    }
    window.addEventListener('popstate', () => {
      if (this.session.snapshot() && this.navigator.currentFrame()) {
        this.navigator.restore(window.location.href);
      }
    });
    navigator.serviceWorker?.addEventListener('message', event => {
      if (event.data?.type === 'shared-folder-auth-denied') {
        this.handleAccessLoss(event.data.status);
      }
    });
  }

  renderStructure() {
    const bar = document.createElement('section');
    bar.className = 'site-media-player-bar';
    bar.setAttribute('aria-label', 'Now playing');
    const media = document.createElement('div');
    media.className = 'site-media-player-media';
    media.dataset.sitePlayerMedia = '';
    const details = document.createElement('div');
    details.className = 'site-media-player-details';
    const eyebrow = document.createElement('span');
    eyebrow.className = 'site-media-player-eyebrow';
    eyebrow.textContent = 'Now playing';
    const title = document.createElement('strong');
    title.className = 'site-media-player-title';
    title.dataset.sitePlayerTitle = '';
    const state = document.createElement('span');
    state.className = 'site-media-player-status';
    state.dataset.sitePlayerStatus = '';
    state.setAttribute('aria-live', 'polite');
    details.append(eyebrow, title, state);
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'site-media-player-close';
    close.dataset.sitePlayerClose = '';
    close.setAttribute('aria-label', 'Close media player');
    close.textContent = '×';
    bar.append(media, details, close);
    this.replaceChildren(bar);
  }

  /** Begin one shared-folder item, replacing any previous item without replacing the player. */
  async playSharedFolder(entry) {
    const kind = String(entry?.previewKind || '').toUpperCase();
    if (!['AUDIO', 'VIDEO'].includes(kind)) {
      throw new TypeError('The site player only accepts audio or video files.');
    }
    const media = document.createElement(kind.toLowerCase());
    media.controls = true;
    media.preload = 'metadata';
    media.playsInline = true;
    media.title = entry.name;
    const playback = this.session.start({
      kind,
      title: entry.name,
      path: entry.path,
    }, media);
    this.setStatus('Opening secure media…');
    try {
      await fetchJson(API.sharedFolder.media.playback, {
        method: 'POST',
        headers: authHeaders(),
        redirectOnUnauthorized: false,
        cache: 'no-store',
        body: JSON.stringify({ path: entry.path }),
        signal: playback.signal,
      });
      await this.loadDirectSource(entry, playback);
    } catch (error) {
      this.handlePlaybackError(error, entry, playback);
    }
    return playback;
  }

  async loadDirectSource(entry, playback) {
    const url = API.sharedFolder.preview(entry.path);
    await prepareSharedFolderMediaAuth(getAuthToken(), url);
    playback.signal.throwIfAborted();
    playback.media.addEventListener('error', () => {
      if (!playback.signal.aborted) void this.loadFallbackSource(entry, playback);
    }, { once: true });
    playback.media.src = url;
    playback.media.load();
    this.setStatus('Ready');
    void playback.media.play().catch(() => this.setStatus('Press play to start'));
  }

  async loadFallbackSource(entry, playback) {
    if (playback.signal.aborted) return;
    this.setStatus('Preparing a browser-compatible version…');
    try {
      const initial = await fetchJson(API.sharedFolder.media.fallback, {
        method: 'POST',
        headers: authHeaders(),
        redirectOnUnauthorized: false,
        cache: 'no-store',
        body: JSON.stringify({ path: entry.path, profile: mediaOutputProfile(entry) }),
        signal: playback.signal,
      });
      const load = (id, signal) => fetchJson(API.sharedFolder.media.job(id), {
        headers: authHeaders(),
        redirectOnUnauthorized: false,
        cache: 'no-store',
        signal,
      });
      const observe = {
        signal: playback.signal,
        load,
        onStatus: job => this.setStatus(mediaStatusMessage(job.status)),
      };
      const playable = await waitForPlayableMediaJob(initial, observe);
      playback.signal.throwIfAborted();
      const url = API.sharedFolder.media.stream(playable.jobId || playable.id);
      await prepareSharedFolderMediaAuth(getAuthToken(), url);
      playback.signal.throwIfAborted();
      playback.media.addEventListener('error', () => {
        if (!playback.signal.aborted) this.showRetry(entry, playback);
      }, { once: true });
      playback.media.src = url;
      playback.media.load();
      this.setStatus(mediaStatusMessage(playable.status));
      void playback.media.play().catch(() => this.setStatus('Press play to start'));
      if (playable.status !== 'READY') await waitForTerminalMediaJob(playable, observe);
    } catch (error) {
      this.handlePlaybackError(error, entry, playback);
    }
  }

  handlePlaybackError(error, entry, playback) {
    if (error?.name === 'AbortError' || playback.signal.aborted) return;
    if (isSharedFolderAccessDenied(error)) {
      this.handleAccessLoss(error.status);
      return;
    }
    this.setStatus(error?.message || 'The media could not be prepared.');
    this.showRetry(entry, playback);
  }

  showRetry(entry, playback) {
    this.querySelector('[data-site-player-retry]')?.remove();
    this.setStatus('Playback stopped. Retry the browser-compatible stream.');
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'site-media-player-retry';
    retry.dataset.sitePlayerRetry = '';
    retry.textContent = 'Retry';
    retry.addEventListener('click', () => {
      retry.remove();
      void this.loadFallbackSource(entry, playback);
    }, { once: true });
    this.querySelector('.site-media-player-details').append(retry);
  }

  handleAccessLoss(status) {
    const denial = sharedFolderStreamingDenial(status);
    this.stopPlayback();
    if (denial.redirectToLogin) {
      clearSharedFolderStreamingAuth();
      clearAuthState();
      window.location.replace(loginRedirectUrl(currentRedirectTarget()));
    }
  }

  mountPlayback(playback) {
    this.querySelector('[data-site-player-retry]')?.remove();
    const mediaHost = this.querySelector('[data-site-player-media]');
    mediaHost.replaceChildren(playback.media);
    this.querySelector('[data-site-player-title]').textContent = playback.descriptor.title;
    this.dataset.kind = playback.descriptor.kind.toLowerCase();
    this.hidden = false;
    document.body.classList.add('site-media-player-active');
    this.updatePlayerHeight();
  }

  unmountPlayback() {
    this.hidden = true;
    this.removeAttribute('data-kind');
    this.querySelector('[data-site-player-media]').replaceChildren();
    this.querySelector('[data-site-player-retry]')?.remove();
    document.body.classList.remove('site-media-player-active', 'site-player-shell-active');
    document.documentElement.style.removeProperty(PLAYER_HEIGHT_PROPERTY);
    this.navigator.currentFrame()?.element.remove();
    this.navigator = this.createNavigator();
  }

  stopPlayback() {
    this.session?.stop();
  }

  close() {
    const frameHref = this.navigator.currentFrame()?.href();
    this.stopPlayback();
    if (frameHref) window.location.assign(frameHref);
  }

  setStatus(message) {
    const status = this.querySelector('[data-site-player-status]');
    if (status) status.textContent = message;
  }

  updatePlayerHeight() {
    requestAnimationFrame(() => {
      if (!this.hidden) {
        document.documentElement.style.setProperty(
          PLAYER_HEIGHT_PROPERTY,
          `${Math.ceil(this.getBoundingClientRect().height)}px`,
        );
      }
    });
  }

  navigateFromClick(anchor, event) {
    if (!this.session?.snapshot()) return false;
    const href = persistentNavigationTarget(anchor, event, window.location.href);
    if (!href) return false;
    event.preventDefault();
    this.navigator.open(href);
    return true;
  }

  createNavigator() {
    return createPersistentSiteNavigator({
      currentHref: () => window.location.href,
      createFrame: onLoad => this.createContentFrame(onLoad),
      showFrame: frame => {
        document.body.append(frame.element);
        document.body.classList.add('site-player-shell-active');
      },
      pushHref: href => window.history.pushState({ siteMediaPlayer: true }, '', href),
      setTitle: title => { document.title = title; },
    });
  }

  createContentFrame(onLoad) {
    const shell = document.createElement('div');
    shell.className = 'site-player-content-shell';
    const iframe = document.createElement('iframe');
    iframe.className = 'site-player-content-frame';
    iframe.title = 'Site content';
    iframe.addEventListener('load', () => {
      try {
        const href = iframe.contentWindow.location.href;
        if (href !== 'about:blank') onLoad({ href, title: iframe.contentDocument.title });
      } catch (_) {
        // Navigation interception only admits same-origin pages; ignore an unexpected escape.
      }
    });
    shell.append(iframe);
    return Object.freeze({
      element: shell,
      navigate: href => { iframe.src = href; },
      href: () => {
        try {
          return iframe.contentWindow.location.href === 'about:blank'
            ? null : iframe.contentWindow.location.href;
        } catch (_) {
          return null;
        }
      },
    });
  }
}

if (!customElements.get(SITE_MEDIA_PLAYER_TAG)) {
  customElements.define(SITE_MEDIA_PLAYER_TAG, SiteMediaPlayer);
}
