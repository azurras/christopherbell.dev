export const SITE_MEDIA_PLAYER_TAG = 'site-media-player';
const SITE_MEDIA_RESUME_KEY = 'cbellSiteMediaResumeV1';
const PLAYBACK_RATES = Object.freeze([1, 1.25, 1.5, 2]);

function validString(value) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 4096;
}

function finiteInRange(value, minimum, maximum) {
  return Number.isFinite(value) && value >= minimum && value <= maximum;
}

function validatedSiteMediaResume(value) {
  const descriptor = value?.descriptor;
  if (value?.version !== 1 || !['AUDIO', 'VIDEO'].includes(descriptor?.kind)
      || !validString(descriptor?.title) || !validString(descriptor?.path)
      || !finiteInRange(value.positionSeconds, 0, Number.MAX_SAFE_INTEGER)
      || typeof value.wasPlaying !== 'boolean'
      || !finiteInRange(value.playbackRate, 0.25, 4)
      || typeof value.muted !== 'boolean'
      || !finiteInRange(value.volume, 0, 1)) {
    return null;
  }
  return Object.freeze({
    descriptor: Object.freeze({
      kind: descriptor.kind,
      title: descriptor.title,
      path: descriptor.path,
    }),
    positionSeconds: value.positionSeconds,
    wasPlaying: value.wasPlaying,
    playbackRate: value.playbackRate,
    muted: value.muted,
    volume: value.volume,
  });
}

/** Read and validate same-tab playback state from an untrusted storage boundary. */
export function readSiteMediaResume(storage) {
  try {
    const serialized = storage?.getItem?.(SITE_MEDIA_RESUME_KEY);
    if (!serialized) return null;
    const resume = validatedSiteMediaResume(JSON.parse(serialized));
    if (resume) return resume;
  } catch (_) {
    // Corrupt or unavailable session storage must not prevent page startup.
  }
  clearSiteMediaResume(storage);
  return null;
}

/** Save only the non-secret facts needed to restore playback in this tab. */
export function saveSiteMediaResume(storage, descriptor, media) {
  if (media?.ended) {
    clearSiteMediaResume(storage);
    return false;
  }
  const resume = validatedSiteMediaResume({
    version: 1,
    descriptor,
    positionSeconds: Number(media?.currentTime),
    wasPlaying: media?.paused === false,
    playbackRate: Number(media?.playbackRate),
    muted: media?.muted,
    volume: Number(media?.volume),
  });
  if (!resume) {
    clearSiteMediaResume(storage);
    return false;
  }
  try {
    storage?.setItem?.(SITE_MEDIA_RESUME_KEY, JSON.stringify({ version: 1, ...resume }));
    return true;
  } catch (_) {
    return false;
  }
}

/** Remove this tab's resumable playback without exposing storage failures. */
export function clearSiteMediaResume(storage) {
  try {
    storage?.removeItem?.(SITE_MEDIA_RESUME_KEY);
  } catch (_) {
    // Playback teardown remains safe when session storage is unavailable.
  }
}

/** Restore media preferences and position before optionally continuing playback. */
export function applySiteMediaResume(media, resume) {
  const validated = validatedSiteMediaResume({ version: 1, ...resume });
  if (!media || !validated) throw new TypeError('Site media resume state is invalid.');
  const duration = Number(media.duration);
  media.currentTime = Number.isFinite(duration)
    ? Math.min(validated.positionSeconds, Math.max(0, duration))
    : validated.positionSeconds;
  media.playbackRate = validated.playbackRate;
  media.muted = validated.muted;
  media.volume = validated.volume;
  return validated.wasPlaying ? media.play() : Promise.resolve();
}

/** Format a media time for the custom player without exposing NaN or Infinity. */
export function formatSiteMediaTime(seconds) {
  const wholeSeconds = Number.isFinite(seconds) && seconds > 0 ? Math.floor(seconds) : 0;
  const hours = Math.floor(wholeSeconds / 3600);
  const minutes = Math.floor((wholeSeconds % 3600) / 60);
  const remainingSeconds = wholeSeconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(remainingSeconds).padStart(2, '0')}`
    : `${minutes}:${String(remainingSeconds).padStart(2, '0')}`;
}

/** Seek by a relative number of seconds while remaining inside media bounds. */
export function seekSiteMediaBy(media, offsetSeconds) {
  const current = Number.isFinite(media?.currentTime) ? media.currentTime : 0;
  const duration = Number(media?.duration);
  const upperBound = Number.isFinite(duration) && duration >= 0 ? duration : Number.MAX_SAFE_INTEGER;
  const next = Math.min(upperBound, Math.max(0, current + Number(offsetSeconds || 0)));
  media.currentTime = next;
  return next;
}

/** Cycle through the bounded playback speeds exposed by the custom controls. */
export function nextSiteMediaPlaybackRate(currentRate) {
  const index = PLAYBACK_RATES.indexOf(Number(currentRate));
  return PLAYBACK_RATES[(index + 1) % PLAYBACK_RATES.length];
}

/** Toggle playback through the media element's native lifecycle. */
export function toggleSiteMediaPlayback(media) {
  if (!media?.play || !media?.pause) throw new TypeError('Site media is not controllable.');
  if (media.paused || media.ended) return media.play();
  media.pause();
  return Promise.resolve();
}

/** Toggle mute without relying on unsupported mobile volume assignment. */
export function toggleSiteMediaMute(media) {
  if (!media) throw new TypeError('Site media is unavailable.');
  media.muted = !media.muted;
  return media.muted;
}

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
