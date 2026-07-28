export const SITE_MEDIA_PLAYER_TAG = 'site-media-player';
const SITE_MEDIA_RESUME_KEY = 'cbellSiteMediaResumeV1';
const PLAYBACK_RATES = Object.freeze([1, 1.25, 1.5, 2]);
const AUDIO_METADATA_TAGS = Object.freeze(['title', 'artist', 'album', 'picture']);
const AUDIO_METADATA_TEXT_LIMIT = 512;
const AUDIO_ARTWORK_BYTE_LIMIT = 5 * 1024 * 1024;
const AUDIO_METADATA_READ_BYTE_LIMIT = 6 * 1024 * 1024;
const AUDIO_ARTWORK_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);
const SITE_RADIO_POLL_MILLISECONDS = 15_000;
const SITE_RADIO_DRIFT_SECONDS = 3;
const SITE_RADIO_MIN_DURATION_SECONDS = 1;
const SITE_RADIO_MAX_DURATION_SECONDS = 86_400;

function validString(value) {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= 4096;
}

function finiteInRange(value, minimum, maximum) {
  return Number.isFinite(value) && value >= minimum && value <= maximum;
}

function validRadioPathSegment(segment) {
  return typeof segment === 'string' && segment.length > 0 && segment === segment.trim()
    && segment !== '.' && segment !== '..'
    && !/[\\/\u0000-\u001F\u007F-\u009F]/u.test(segment);
}

function validRadioEntry(entry) {
  const path = entry?.path;
  const pathParts = typeof path === 'string' ? path.split('/') : [];
  const name = pathParts.at(-1);
  return typeof path === 'string' && path.length > 0 && path.length <= 4096
    && pathParts.length >= 2 && pathParts[0].toLowerCase() === 'music'
    && pathParts.every(validRadioPathSegment)
    && typeof entry.name === 'string' && entry.name === name && entry.name === entry.name.trim()
    && entry.type === 'FILE' && entry.previewKind === 'AUDIO'
    && Number.isSafeInteger(entry.size) && entry.size >= 0
    && typeof entry.modifiedAt === 'string' && Number.isFinite(Date.parse(entry.modifiedAt))
    && (entry.observedToken === undefined || entry.observedToken === null
      || typeof entry.observedToken === 'string');
}

function validMusicRadioTrack(track) {
  const optionalText = value => value === null || value === undefined
    || (typeof value === 'string' && value.length <= 512);
  return validString(track?.id) && validString(track?.title)
    && optionalText(track.artist) && optionalText(track.albumArtist)
    && optionalText(track.album) && optionalText(track.genre)
    && typeof track.artworkAvailable === 'boolean';
}

function validatedMusicRadioPlayback(response) {
  if (!Number.isSafeInteger(response?.stationSequence) || response.stationSequence < 1
      || !validString(response.trackId) || response.trackId !== response.track?.id
      || !validString(response.observedToken)
      || typeof response.startedAt !== 'string' || !Number.isFinite(Date.parse(response.startedAt))
      || !finiteInRange(response.positionSeconds, 0, Number.MAX_SAFE_INTEGER)
      || !finiteInRange(response.durationSeconds,
        SITE_RADIO_MIN_DURATION_SECONDS, SITE_RADIO_MAX_DURATION_SECONDS)
      || !['RADIO', 'QUEUE'].includes(response.source)
      || !validMusicRadioTrack(response.track)) {
    throw new Error('Music returned an invalid radio response.');
  }
  return Object.freeze({
    stationSequence: response.stationSequence,
    startedAt: response.startedAt,
    positionSeconds: response.positionSeconds,
    durationSeconds: response.durationSeconds,
    source: response.source,
    entry: Object.freeze({
      name: response.track.title,
      path: response.trackId,
      type: 'FILE',
      size: 0,
      modifiedAt: response.startedAt,
      previewKind: 'AUDIO',
      observedToken: response.observedToken,
      track: Object.freeze({ ...response.track }),
    }),
  });
}

function validatedSiteRadioPlayback(playback) {
  const validDuration = playback?.durationSeconds === null
    || finiteInRange(playback?.durationSeconds,
      SITE_RADIO_MIN_DURATION_SECONDS, SITE_RADIO_MAX_DURATION_SECONDS);
  const musicEntry = validMusicRadioTrack(playback?.entry?.track)
    && playback.entry.path === playback.entry.track.id
    && playback.entry.name === playback.entry.track.title
    && validString(playback.entry.observedToken);
  if (!Number.isSafeInteger(playback?.stationSequence) || playback.stationSequence < 1
      || typeof playback.startedAt !== 'string' || !Number.isFinite(Date.parse(playback.startedAt))
      || !finiteInRange(playback.positionSeconds, 0, Number.MAX_SAFE_INTEGER)
      || !validDuration || (!musicEntry && !validRadioEntry(playback.entry))) {
    throw new Error('The shared folder returned an invalid radio response.');
  }
  return Object.freeze({
    stationSequence: playback.stationSequence,
    startedAt: playback.startedAt,
    positionSeconds: playback.positionSeconds,
    durationSeconds: playback.durationSeconds,
    entry: Object.freeze({
      name: playback.entry.name,
      path: playback.entry.path,
      type: playback.entry.type,
      size: playback.entry.size,
      modifiedAt: playback.entry.modifiedAt,
      previewKind: playback.entry.previewKind,
      observedToken: playback.entry.observedToken ?? null,
      ...(musicEntry ? { track: Object.freeze({ ...playback.entry.track }) } : {}),
    }),
  });
}

/** Validate the complete untrusted radio response before media or URL effects use it. */
export function validateSiteRadioResponse(response) {
  if (response?.status === 'EMPTY' && (response.playback === null || response.trackId === null)) {
    return Object.freeze({ status: 'EMPTY', playback: null });
  }
  if (response?.status === 'PLAYING' && response.trackId) {
    return Object.freeze({ status: 'PLAYING', playback: validatedMusicRadioPlayback(response) });
  }
  if (response?.status !== 'PLAYING' || !response.playback) {
    throw new Error('The shared folder returned an invalid radio response.');
  }
  return Object.freeze({ status: 'PLAYING', playback: validatedSiteRadioPlayback(response.playback) });
}

/** Decide the only player mutation needed to match one validated station snapshot. */
export function siteRadioSyncDecision(current, response, mediaPositionSeconds) {
  const station = validateSiteRadioResponse(response);
  if (station.status === 'EMPTY') {
    return Object.freeze({ action: 'EMPTY', targetPositionSeconds: null });
  }
  const next = station.playback;
  const sameIdentity = current?.stationSequence === next.stationSequence
    && current?.path === next.entry.path;
  if (!sameIdentity) {
    return Object.freeze({ action: 'REPLACE', targetPositionSeconds: next.positionSeconds });
  }
  const currentPosition = Number.isFinite(mediaPositionSeconds) ? mediaPositionSeconds : 0;
  const action = Math.abs(currentPosition - next.positionSeconds) > SITE_RADIO_DRIFT_SECONDS
    ? 'SEEK' : 'KEEP';
  return Object.freeze({ action, targetPositionSeconds: next.positionSeconds });
}

/** Build radio startup state without restoring a stale per-item position or playback rate. */
export function siteRadioResumeState(resume, playback) {
  const validatedResume = validatedSiteMediaResume({ version: 1, ...resume });
  const station = validatedSiteRadioPlayback(playback);
  if (validatedResume?.descriptor.mode !== 'RADIO') {
    throw new TypeError('Site radio resume state is invalid.');
  }
  return Object.freeze({
    descriptor: Object.freeze({
      mode: 'RADIO',
      kind: 'AUDIO',
      title: station.entry.name,
      path: station.entry.path,
      stationSequence: station.stationSequence,
    }),
    positionSeconds: station.positionSeconds,
    wasPlaying: validatedResume.wasPlaying,
    playbackRate: 1,
    muted: validatedResume.muted,
    volume: validatedResume.volume,
  });
}

/** Build one bounded duration observation tied to the exact station identity. */
export function siteRadioDurationReport(playback, durationSeconds) {
  if (!finiteInRange(durationSeconds,
    SITE_RADIO_MIN_DURATION_SECONDS, SITE_RADIO_MAX_DURATION_SECONDS)) return null;
  const station = validatedSiteRadioPlayback(playback);
  return Object.freeze({
    stationSequence: station.stationSequence,
    path: station.entry.path,
    durationSeconds,
  });
}

/** Own de-duplication of bounded duration effects for one radio joining lifetime. */
export function createSiteRadioDurationReporter({ report }) {
  if (typeof report !== 'function') {
    throw new TypeError('Site radio duration reporting requires a report function.');
  }
  const reported = new Set();
  return Object.freeze({
    async loaded(playback, source, durationSeconds) {
      if (!validString(source)) return false;
      const request = siteRadioDurationReport(playback, durationSeconds);
      if (!request) return false;
      const key = `${request.stationSequence}\0${source}`;
      if (reported.has(key)) return false;
      reported.add(key);
      await report(request);
      return true;
    },
  });
}

/** Return the item-only controls that must be disabled for live radio playback. */
export function siteMediaControlState(descriptor, durationSeconds) {
  const live = descriptor?.mode === 'RADIO';
  return Object.freeze({
    live,
    seekDisabled: live || !Number.isFinite(durationSeconds) || durationSeconds <= 0,
    rewindDisabled: live,
    forwardDisabled: live,
    rateDisabled: live,
  });
}

/** Own one exact-delay, non-overlapping radio polling lifecycle through injected timers. */
export function createSiteRadioScheduler({ poll, schedule, cancel, onError = () => {} }) {
  if (typeof poll !== 'function' || typeof schedule !== 'function'
      || typeof cancel !== 'function' || typeof onError !== 'function') {
    throw new TypeError('Site radio scheduling requires poll, schedule, cancel, and error functions.');
  }
  let active = false;
  let running = false;
  let timer = null;

  const queue = () => {
    if (!active || running || timer !== null) return;
    timer = schedule(run, SITE_RADIO_POLL_MILLISECONDS);
  };
  const run = () => {
    timer = null;
    if (!active || running) return;
    running = true;
    let result;
    try {
      result = poll();
    } catch (error) {
      result = Promise.reject(error);
    }
    void Promise.resolve(result)
      .catch(onError)
      .finally(() => {
        running = false;
        queue();
      });
  };

  return Object.freeze({
    start() {
      if (active) return;
      active = true;
      queue();
    },
    stop() {
      active = false;
      if (timer !== null) cancel(timer);
      timer = null;
    },
    active: () => active,
  });
}

function validatedSiteMediaResume(value) {
  const descriptor = value?.descriptor;
  const mode = descriptor?.mode ?? 'ITEM';
  const musicItem = mode === 'MUSIC_ITEM';
  const musicArtist = descriptor?.artist ?? '';
  const musicAlbum = descriptor?.album ?? '';
  if (value?.version !== 1 || !['ITEM', 'RADIO', 'MUSIC_ITEM'].includes(mode)
      || !['AUDIO', 'VIDEO'].includes(descriptor?.kind)
      || mode === 'RADIO' && descriptor.kind !== 'AUDIO'
      || musicItem && (descriptor.kind !== 'AUDIO'
        || !/^[A-Za-z0-9_-]{1,128}$/u.test(descriptor.path)
        || typeof musicArtist !== 'string' || musicArtist.length > 512
        || typeof musicAlbum !== 'string' || musicAlbum.length > 512
        || typeof descriptor.artworkAvailable !== 'boolean')
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
      mode,
      kind: descriptor.kind,
      title: descriptor.title,
      path: descriptor.path,
      ...(musicItem ? {
        artist: musicArtist,
        album: musicAlbum,
        artworkAvailable: descriptor.artworkAvailable,
      } : {}),
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
export function saveSiteMediaResume(storage, descriptor, media, playbackIntent = undefined) {
  if (media?.ended) {
    clearSiteMediaResume(storage);
    return false;
  }
  const resume = validatedSiteMediaResume({
    version: 1,
    descriptor,
    positionSeconds: Number(media?.currentTime),
    wasPlaying: playbackIntent?.wasPlaying ?? media?.paused === false,
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

/** Resume a browser-blocked playback request inside the next real user gesture. */
export function armSiteMediaGestureResume(
    media, eventTarget, onStarted = () => {}, shouldResume = () => true) {
  if (typeof media?.play !== 'function'
      || typeof eventTarget?.addEventListener !== 'function'
      || typeof eventTarget?.removeEventListener !== 'function'
      || typeof onStarted !== 'function'
      || typeof shouldResume !== 'function') {
    throw new TypeError('Gesture playback resume requires media, an event target, and a callback.');
  }
  let active = true;
  let attemptInFlight = false;
  const cancel = () => {
    if (!active) return;
    active = false;
    eventTarget.removeEventListener('pointerdown', retry, true);
    eventTarget.removeEventListener('keydown', retry, true);
  };
  const retry = event => {
    if (!active || attemptInFlight || !shouldResume(event)) return;
    attemptInFlight = true;
    let attempt;
    try {
      attempt = media.play();
    } catch (_) {
      attemptInFlight = false;
      return;
    }
    void Promise.resolve(attempt).then(() => {
      cancel();
      onStarted();
    }, () => {
      attemptInFlight = false;
    });
  };
  eventTarget.addEventListener('pointerdown', retry, true);
  eventTarget.addEventListener('keydown', retry, true);
  return cancel;
}

function normalizedAudioMetadataText(value) {
  if (typeof value !== 'string') return null;
  const normalized = value.trim();
  return normalized.length > 0 && normalized.length <= AUDIO_METADATA_TEXT_LIMIT
    ? normalized : null;
}

function normalizedAudioArtwork(picture) {
  const suppliedType = picture?.format ?? picture?.type;
  const type = typeof suppliedType === 'string' ? suppliedType.toLowerCase() : '';
  const data = picture?.data ?? picture?.bytes;
  if (!AUDIO_ARTWORK_TYPES.has(type)
      || !(Array.isArray(data) || data instanceof Uint8Array)
      || data.length === 0 || data.length > AUDIO_ARTWORK_BYTE_LIMIT) {
    return null;
  }
  for (const byte of data) {
    if (!Number.isInteger(byte) || byte < 0 || byte > 255) return null;
  }
  return Object.freeze({ type, bytes: Uint8Array.from(data) });
}

/** Validate untrusted third-party audio tags before the player renders them. */
export function normalizeSiteAudioMetadata(tags) {
  return Object.freeze({
    title: normalizedAudioMetadataText(tags?.title),
    artist: normalizedAudioMetadataText(tags?.artist),
    album: normalizedAudioMetadataText(tags?.album),
    picture: normalizedAudioArtwork(tags?.picture),
  });
}

/** Produce the complete text/artwork model rendered by the audio player. */
export function siteAudioPresentation(metadata, fallbackTitle) {
  const safeFallback = normalizedAudioMetadataText(fallbackTitle) || 'Unknown audio';
  const normalized = normalizeSiteAudioMetadata(metadata);
  return Object.freeze({
    title: normalized.title || safeFallback,
    artist: normalized.artist || '',
    album: normalized.album || '',
    subtitle: [normalized.artist, normalized.album].filter(Boolean).join(' · '),
    picture: normalized.picture,
  });
}

function siteMediaAbortError() {
  if (typeof DOMException === 'function') {
    return new DOMException('Audio metadata reading was aborted.', 'AbortError');
  }
  const error = new Error('Audio metadata reading was aborted.');
  error.name = 'AbortError';
  return error;
}

function createBoundedAudioMetadataReader(Reader, url, signal) {
  const reader = new Reader(url);
  const DefaultFileReader = reader._findFileReader?.();
  if (typeof DefaultFileReader !== 'function' || typeof reader.setFileReader !== 'function') {
    throw new TypeError('Audio metadata reader does not expose its pinned range interface.');
  }
  const activeRequests = new Set();

  class BoundedFileReader extends DefaultFileReader {
    constructor(location) {
      super(location);
      this.remainingReadBytes = AUDIO_METADATA_READ_BYTE_LIMIT;
    }

    loadRange(range, callbacks) {
      const start = Number(range?.[0]);
      const end = Number(range?.[1]);
      if (signal?.aborted) {
        callbacks.onError?.({ type: 'abort', info: 'Audio metadata reading was aborted.' });
        return;
      }
      if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start) {
        callbacks.onError?.({ type: 'range', info: 'Audio metadata requested an invalid range.' });
        return;
      }
      const fileEnd = Number.isSafeInteger(this._size) ? Math.min(this._size, end) : end;
      const isCached = this._fileData?.hasDataRange?.(start, fileEnd) === true;
      if (!isCached) {
        const rounded = typeof this._roundRangeToChunkMultiple === 'function'
          ? this._roundRangeToChunkMultiple([start, end]) : [start, end];
        const requestEnd = Number.isSafeInteger(this._size)
          ? Math.min(this._size, rounded[1]) : rounded[1];
        const requestBytes = requestEnd - rounded[0] + 1;
        if (!Number.isSafeInteger(requestBytes)
            || requestBytes <= 0 || requestBytes > this.remainingReadBytes) {
          callbacks.onError?.({
            type: 'budget',
            info: 'Audio metadata exceeded the 6 MiB byte budget.',
          });
          return;
        }
        this.remainingReadBytes -= requestBytes;
      }
      super.loadRange(range, callbacks);
    }

    _fetchEntireFile(callbacks) {
      callbacks.onError?.({
        type: 'budget',
        info: 'Audio metadata whole-file reads are disabled.',
      });
    }

    _createXHRObject() {
      const xhr = super._createXHRObject();
      activeRequests.add(xhr);
      xhr.addEventListener?.('loadend', () => activeRequests.delete(xhr), { once: true });
      return xhr;
    }
  }

  return Object.freeze({
    reader: reader.setFileReader(BoundedFileReader),
    abort() {
      for (const xhr of activeRequests) xhr.abort?.();
      activeRequests.clear();
    },
  });
}

function audioMetadataReadError(cause) {
  const detail = typeof cause?.info === 'string' ? ` ${cause.info}` : '';
  return new Error(`Audio metadata could not be read.${detail}`, { cause });
}

/** Read four display tags through an abortable reader with a hard range-I/O budget. */
export async function readSiteAudioMetadata(url, loadReader, signal = undefined) {
  if (!validString(url) || typeof loadReader !== 'function') {
    throw new TypeError('Audio metadata reading requires a URL and reader loader.');
  }
  const Reader = await loadReader();
  if (typeof Reader !== 'function') throw new TypeError('Audio metadata reader is unavailable.');
  if (signal?.aborted) throw siteMediaAbortError();
  const bounded = createBoundedAudioMetadataReader(Reader, url, signal);
  return new Promise((resolve, reject) => {
    let settled = false;
    const settle = (complete, value) => {
      if (settled) return;
      settled = true;
      signal?.removeEventListener?.('abort', onAbort);
      complete(value);
    };
    const onAbort = () => {
      bounded.abort();
      settle(reject, siteMediaAbortError());
    };
    signal?.addEventListener?.('abort', onAbort, { once: true });
    try {
      bounded.reader.setTagsToRead([...AUDIO_METADATA_TAGS]).read({
        onSuccess: result => settle(resolve, normalizeSiteAudioMetadata(result?.tags)),
        onError: cause => {
          bounded.abort();
          settle(reject, audioMetadataReadError(cause));
        },
      });
    } catch (cause) {
      bounded.abort();
      settle(reject, audioMetadataReadError(cause));
    }
  });
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

/** Join the authenticated shared-folder radio through the top same-origin player. */
export function playSharedFolderRadio(browserWindow = window) {
  const host = siteMediaPlayerHost(browserWindow);
  if (typeof host?.playSharedFolderRadio !== 'function') {
    throw new Error('The site-wide media player is unavailable.');
  }
  return host.playSharedFolderRadio();
}

/** Expand the persistent player only for the first-class Music route. */
export function siteMediaPresentation(href, currentHref) {
  try {
    return new URL(href, currentHref).pathname === '/music' ? 'expanded' : 'compact';
  } catch (_) {
    return 'compact';
  }
}

/** Join the global Music station through the top same-origin player. */
export function playMusicRadio(browserWindow = window) {
  const host = siteMediaPlayerHost(browserWindow);
  if (typeof host?.playMusicRadio !== 'function') {
    throw new Error('The site-wide media player is unavailable.');
  }
  return host.playMusicRadio();
}

/** Play one catalog track through the top same-origin player. */
export function playMusicTrack(track, browserWindow = window) {
  const host = siteMediaPlayerHost(browserWindow);
  if (typeof host?.playMusicTrack !== 'function') {
    throw new Error('The site-wide media player is unavailable.');
  }
  return host.playMusicTrack(track);
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
