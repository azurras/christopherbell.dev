export const SITE_MEDIA_PLAYER_TAG = 'site-media-player';

/** Return the same-origin page URL that should remain inside the persistent media shell. */
export function persistentNavigationTarget(anchor, event, currentHref) {
  if (!anchor?.href || event?.defaultPrevented || event?.button !== 0
      || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey
      || anchor.hasAttribute?.('download')
      || (anchor.target && anchor.target.toLowerCase() !== '_self')) {
    return null;
  }
  let current;
  let target;
  try {
    current = new URL(currentHref);
    target = new URL(anchor.href, current);
  } catch (_) {
    return null;
  }
  if (!['http:', 'https:'].includes(target.protocol) || target.origin !== current.origin
      || target.pathname.startsWith('/api/')) {
    return null;
  }
  if (target.pathname === current.pathname && target.search === current.search && target.hash) {
    return null;
  }
  return target.href;
}

/** Keep route history synchronized while one reusable content frame preserves the top document. */
export function createPersistentSiteNavigator({
  currentHref,
  createFrame,
  showFrame,
  pushHref,
  setTitle,
}) {
  let frame = null;
  let pendingHistory = null;

  const loaded = ({ href, title }) => {
    const historyMode = pendingHistory;
    pendingHistory = null;
    if (title) setTitle(title);
    if (historyMode !== 'restore' && href !== currentHref()) pushHref(href);
  };

  const ensureFrame = () => {
    if (frame) return frame;
    frame = createFrame(loaded);
    showFrame(frame);
    return frame;
  };

  const navigate = (href, historyMode) => {
    pendingHistory = historyMode;
    ensureFrame().navigate(href);
  };

  return Object.freeze({
    open: href => navigate(href, 'push'),
    restore: href => navigate(href, 'restore'),
    currentFrame: () => frame,
  });
}

/** Own exactly one media element, its cancellation lifetime, and complete teardown. */
export function createSiteMediaSession({ mount, unmount }) {
  let active = null;

  const release = (playback, removeUi) => {
    if (!playback) return;
    playback.controller.abort();
    playback.media.pause();
    playback.media.removeAttribute('src');
    playback.media.load?.();
    if (removeUi) unmount();
  };

  const stop = playback => {
    if (!active || active !== playback) return;
    active = null;
    release(playback, true);
  };

  return Object.freeze({
    start(descriptor, media) {
      if (!['AUDIO', 'VIDEO'].includes(descriptor?.kind) || !descriptor.title?.trim()) {
        throw new TypeError('Site media requires an AUDIO or VIDEO descriptor with a title.');
      }
      if (!media?.pause || !media?.removeAttribute) {
        throw new TypeError('Site media requires a controllable media element.');
      }
      release(active, false);
      const controller = new AbortController();
      const playback = Object.freeze({
        descriptor: Object.freeze({ ...descriptor }),
        media,
        controller,
        signal: controller.signal,
        stop: () => stop(playback),
      });
      active = playback;
      mount(playback);
      return playback;
    },
    stop: () => stop(active),
    snapshot: () => active,
  });
}

function sameOriginTopWindow(browserWindow) {
  try {
    if (browserWindow.top && browserWindow.top.location.origin === browserWindow.location.origin) {
      return browserWindow.top;
    }
  } catch (_) {
    // Cross-origin embedding cannot participate in the site's player shell.
  }
  return browserWindow;
}

/** Find the one player owned by the top same-origin document. */
export function siteMediaPlayerHost(browserWindow = window) {
  return sameOriginTopWindow(browserWindow).document.querySelector(SITE_MEDIA_PLAYER_TAG);
}

/** Start authenticated shared-folder playback in the site-wide player. */
export function playSharedFolderMedia(entry, browserWindow = window) {
  const host = siteMediaPlayerHost(browserWindow);
  if (typeof host?.playSharedFolder !== 'function') {
    throw new Error('The site-wide media player is unavailable.');
  }
  return host.playSharedFolder(entry);
}

/** Let every same-origin document delegate ordinary link clicks to the top player. */
export function handleSiteNavigationClick(event, browserWindow = window) {
  const anchor = event?.target?.closest?.('a[href]');
  if (!anchor) return false;
  return siteMediaPlayerHost(browserWindow)?.navigateFromClick?.(anchor, event) === true;
}

/** Stop and remove the site-wide player from any same-origin page context. */
export function stopSiteMediaPlayback(browserWindow = window) {
  siteMediaPlayerHost(browserWindow)?.stopPlayback?.();
}
