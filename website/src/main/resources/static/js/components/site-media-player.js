import { API } from '../lib/api.js';
import {
  mediaOutputProfile,
  mediaStatusMessage,
  isSharedFolderAccessDenied,
  waitForPlayableMediaJob,
  waitForTerminalMediaJob,
} from '../lib/shared-folder.js';
import {
  prepareSharedFolderMediaAuth,
  sharedFolderStreamingDenial,
} from '../lib/shared-folder-streaming.js';
import {
  applySiteMediaResume,
  armSiteMediaGestureResume,
  clearSiteMediaResume,
  createPersistentSiteNavigator,
  createSiteRadioDurationReporter,
  createSiteRadioScheduler,
  createSiteMediaSession,
  formatSiteMediaTime,
  nextSiteMediaPlaybackRate,
  persistentNavigationTarget,
  readSiteAudioMetadata,
  readSiteMediaResume,
  saveSiteMediaResume,
  seekSiteMediaBy,
  siteAudioPresentation,
  siteMediaControlState,
  siteRadioResumeState,
  siteRadioSyncDecision,
  SITE_MEDIA_PLAYER_TAG,
  toggleSiteMediaMute,
  toggleSiteMediaPlayback,
  validateSiteRadioResponse,
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
const AUDIO_METADATA_LIBRARY_URL = '/vendor/jsmediatags-3.9.7.min.js';
let audioMetadataReaderPromise = null;

function loadAudioMetadataReader() {
  if (typeof globalThis.jsmediatags?.Reader === 'function') {
    return Promise.resolve(globalThis.jsmediatags.Reader);
  }
  if (audioMetadataReaderPromise) return audioMetadataReaderPromise;
  audioMetadataReaderPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = AUDIO_METADATA_LIBRARY_URL;
    script.async = true;
    script.addEventListener('load', () => {
      if (typeof globalThis.jsmediatags?.Reader === 'function') {
        resolve(globalThis.jsmediatags.Reader);
      } else {
        reject(new Error('The audio metadata reader did not initialize.'));
      }
    }, { once: true });
    script.addEventListener('error', () => {
      reject(new Error('The audio metadata reader could not be loaded.'));
    }, { once: true });
    document.head.append(script);
  }).catch(error => {
    audioMetadataReaderPromise = null;
    throw error;
  });
  return audioMetadataReaderPromise;
}

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
    this.resumeByMedia = new WeakMap();
    this.sourceVersionByMedia = new WeakMap();
    this.playbackIntentByMedia = new WeakMap();
    this.pendingPlaybackStart = new WeakSet();
    this.radioGeneration = 0;
    this.radioScheduler = createSiteRadioScheduler({
      poll: () => this.syncRadio(),
      schedule: (callback, delayMilliseconds) => window.setTimeout(callback, delayMilliseconds),
      cancel: timer => window.clearTimeout(timer),
      onError: error => this.handleRadioSyncError(error),
    });
    this.bindControls();
    this.pageHideHandler = () => this.persistPlayback();
    window.addEventListener('pagehide', this.pageHideHandler);
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
    void this.restorePlayback();
  }

  disconnectedCallback() {
    if (this.pageHideHandler) window.removeEventListener('pagehide', this.pageHideHandler);
    this.resizeObserver?.disconnect();
    this.cancelGestureResume();
    this.releaseArtwork();
    this.clearMediaSessionMetadata();
    this.stopPlayback();
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
    const trackMetadata = document.createElement('span');
    trackMetadata.className = 'site-media-player-track-metadata';
    trackMetadata.dataset.sitePlayerTrackMetadata = '';
    trackMetadata.hidden = true;
    details.append(eyebrow, title, trackMetadata, state);

    const timeline = document.createElement('div');
    timeline.className = 'site-media-player-timeline';
    const elapsed = document.createElement('span');
    elapsed.dataset.sitePlayerElapsed = '';
    elapsed.textContent = '0:00';
    const seek = document.createElement('input');
    seek.type = 'range';
    seek.min = '0';
    seek.max = '0';
    seek.step = '0.1';
    seek.value = '0';
    seek.dataset.sitePlayerSeek = '';
    seek.setAttribute('aria-label', 'Playback position');
    const duration = document.createElement('span');
    duration.dataset.sitePlayerDuration = '';
    duration.textContent = '0:00';
    timeline.append(elapsed, seek, duration);

    const transport = document.createElement('div');
    transport.className = 'site-media-player-transport';
    const rewind = this.controlButton('↶ 10', 'Skip back 10 seconds', 'rewind');
    const play = this.controlButton('▶', 'Play', 'play');
    play.classList.add('site-media-player-play');
    const forward = this.controlButton('10 ↷', 'Skip forward 10 seconds', 'forward');
    transport.append(rewind, play, forward);

    const actions = document.createElement('div');
    actions.className = 'site-media-player-actions';
    const mute = this.controlButton('🔊', 'Mute', 'mute');
    const volume = document.createElement('input');
    volume.type = 'range';
    volume.min = '0';
    volume.max = '1';
    volume.step = '0.05';
    volume.value = '1';
    volume.className = 'site-media-player-volume';
    volume.dataset.sitePlayerVolume = '';
    volume.setAttribute('aria-label', 'Volume');
    const rate = this.controlButton('1×', 'Change playback speed', 'rate');
    const more = document.createElement('details');
    more.className = 'site-media-player-more';
    const summary = document.createElement('summary');
    summary.setAttribute('aria-label', 'More playback options');
    summary.textContent = '•••';
    const moreMenu = document.createElement('div');
    moreMenu.className = 'site-media-player-more-menu';
    const pictureInPicture = this.controlButton(
      'Picture in picture', 'Open picture in picture', 'picture-in-picture');
    const fullscreen = this.controlButton('Fullscreen', 'Open fullscreen video', 'fullscreen');
    moreMenu.append(pictureInPicture, fullscreen);
    more.append(summary, moreMenu);
    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'site-media-player-close';
    close.dataset.sitePlayerClose = '';
    close.setAttribute('aria-label', 'Close media player');
    close.textContent = '×';
    actions.append(mute, volume, rate, more, close);
    bar.append(media, details, transport, timeline, actions);
    this.replaceChildren(bar);
  }

  controlButton(text, label, control) {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'site-media-player-control';
    button.dataset.sitePlayerControl = control;
    button.setAttribute('aria-label', label);
    button.textContent = text;
    return button;
  }

  bindControls() {
    this.querySelector('[data-site-player-close]').addEventListener('click', () => this.close());
    this.querySelector('[data-site-player-control="play"]').addEventListener('click', () => {
      const media = this.currentMedia();
      if (!media) return;
      const intendsToPlay = media.paused || media.ended;
      this.playbackIntentByMedia.set(media, intendsToPlay);
      if (intendsToPlay && this.session.snapshot()?.descriptor.mode === 'RADIO') {
        this.pendingPlaybackStart.add(media);
        void this.syncRadio({ requestPlay: true });
        return;
      }
      if (!intendsToPlay) {
        this.pendingPlaybackStart.delete(media);
        this.cancelGestureResume();
      }
      void toggleSiteMediaPlayback(media).catch(() => this.setStatus('Press play to start'));
    });
    this.querySelector('[data-site-player-control="rewind"]').addEventListener('click', () => {
      const media = this.currentMedia();
      if (!media || this.session.snapshot()?.descriptor.mode === 'RADIO') return;
      seekSiteMediaBy(media, -10);
      this.syncControls();
      this.persistPlayback();
    });
    this.querySelector('[data-site-player-control="forward"]').addEventListener('click', () => {
      const media = this.currentMedia();
      if (!media || this.session.snapshot()?.descriptor.mode === 'RADIO') return;
      seekSiteMediaBy(media, 10);
      this.syncControls();
      this.persistPlayback();
    });
    this.querySelector('[data-site-player-control="mute"]').addEventListener('click', () => {
      const media = this.currentMedia();
      if (!media) return;
      toggleSiteMediaMute(media);
      this.syncControls();
      this.persistPlayback();
    });
    this.querySelector('[data-site-player-control="rate"]').addEventListener('click', () => {
      const media = this.currentMedia();
      if (!media || this.session.snapshot()?.descriptor.mode === 'RADIO') return;
      media.playbackRate = nextSiteMediaPlaybackRate(media.playbackRate);
      this.syncControls();
      this.persistPlayback();
    });
    this.querySelector('[data-site-player-seek]').addEventListener('input', event => {
      const media = this.currentMedia();
      if (!media || this.session.snapshot()?.descriptor.mode === 'RADIO') return;
      media.currentTime = Number(event.currentTarget.value);
      this.syncControls();
      this.persistPlayback();
    });
    this.querySelector('[data-site-player-volume]').addEventListener('input', event => {
      const media = this.currentMedia();
      if (!media) return;
      media.volume = Number(event.currentTarget.value);
      if (media.volume > 0) media.muted = false;
      this.syncControls();
      this.persistPlayback();
    });
    this.querySelector('[data-site-player-control="picture-in-picture"]')
      .addEventListener('click', () => void this.openPictureInPicture());
    this.querySelector('[data-site-player-control="fullscreen"]')
      .addEventListener('click', () => void this.openFullscreen());
  }

  currentMedia() {
    return this.session?.snapshot()?.media || null;
  }

  async restorePlayback() {
    const resume = readSiteMediaResume(sessionStorage);
    if (!resume) return;
    if (!getAuthToken()) {
      clearSiteMediaResume(sessionStorage);
      return;
    }
    try {
      if (resume.descriptor.mode === 'RADIO') {
        await this.playSharedFolderRadio(resume);
      } else {
        await this.playSharedFolder({
          previewKind: resume.descriptor.kind,
          name: resume.descriptor.title,
          path: resume.descriptor.path,
        }, resume);
      }
    } catch (error) {
      if (isSharedFolderAccessDenied(error)) this.handleAccessLoss(error.status);
      else clearSiteMediaResume(sessionStorage);
    }
  }

  bindPlaybackEvents(playback) {
    const sync = () => this.syncControls(playback);
    const persist = () => {
      if (!playback.signal.aborted) this.persistPlayback();
    };
    const eventOptions = { signal: playback.signal };
    playback.media.addEventListener('loadedmetadata', () => {
      sync();
      if (playback.descriptor.mode === 'RADIO') this.reportRadioDuration(playback);
    }, eventOptions);
    playback.media.addEventListener('durationchange', sync, eventOptions);
    playback.media.addEventListener('timeupdate', () => {
      sync();
      const currentSecond = Math.floor(playback.media.currentTime || 0);
      if (currentSecond !== this.lastPersistedSecond) {
        this.lastPersistedSecond = currentSecond;
        persist();
      }
    }, eventOptions);
    playback.media.addEventListener('play', () => {
      this.playbackIntentByMedia.set(playback.media, true);
      this.pendingPlaybackStart.delete(playback.media);
      this.cancelGestureResume(playback);
      sync();
      persist();
    }, eventOptions);
    playback.media.addEventListener('pause', () => {
      if (!this.pendingPlaybackStart.has(playback.media)) {
        this.playbackIntentByMedia.set(playback.media, false);
      }
      sync();
      persist();
    }, eventOptions);
    for (const eventName of ['seeked', 'volumechange', 'ratechange']) {
      playback.media.addEventListener(eventName, () => {
        sync();
        persist();
      }, eventOptions);
    }
    playback.media.addEventListener('ended', () => {
      if (playback.descriptor.mode === 'RADIO') {
        void this.syncRadio({ requestPlay: true });
      } else {
        clearSiteMediaResume(sessionStorage);
      }
      sync();
    }, eventOptions);
  }

  resumePlaybackWhenReady(playback) {
    const sourceVersion = (this.sourceVersionByMedia.get(playback.media) || 0) + 1;
    this.sourceVersionByMedia.set(playback.media, sourceVersion);
    playback.media.addEventListener('loadedmetadata', () => {
      if (playback.signal.aborted
          || this.sourceVersionByMedia.get(playback.media) !== sourceVersion) return;
      const resume = this.resumeByMedia.get(playback.media);
      if (resume?.wasPlaying) this.pendingPlaybackStart.add(playback.media);
      void applySiteMediaResume(playback.media, resume)
        .then(() => {
          this.pendingPlaybackStart.delete(playback.media);
          this.cancelGestureResume(playback);
          this.syncControls(playback);
        })
        .catch(() => {
          this.syncControls(playback);
          if (resume?.wasPlaying) {
            this.persistPlayback();
            this.armGestureResume(playback);
            this.setStatus('Tap anywhere to continue');
          } else {
            this.pendingPlaybackStart.delete(playback.media);
            this.persistPlayback();
            this.setStatus('Press play to start');
          }
        });
    }, { once: true, signal: playback.signal });
  }

  persistPlayback() {
    const playback = this.session?.snapshot();
    if (!playback || playback.signal.aborted) return false;
    return saveSiteMediaResume(sessionStorage, playback.descriptor, playback.media, {
      wasPlaying: this.playbackIntentByMedia.get(playback.media)
        ?? playback.media.paused === false,
    });
  }

  armGestureResume(playback) {
    this.cancelGestureResume();
    this.gestureResumePlayback = playback;
    this.gestureResumeCancel = armSiteMediaGestureResume(
      playback.media,
      document,
      () => {
        if (this.session?.snapshot() !== playback || playback.signal.aborted) return;
        this.pendingPlaybackStart.delete(playback.media);
        this.playbackIntentByMedia.set(playback.media, true);
        this.gestureResumeCancel = null;
        this.gestureResumePlayback = null;
        this.setStatus('Ready');
        this.syncControls(playback);
        this.persistPlayback();
      },
      event => !event?.target?.closest?.('[data-site-player-control="play"]'));
  }

  cancelGestureResume(expectedPlayback = null) {
    if (expectedPlayback && this.gestureResumePlayback !== expectedPlayback) return;
    this.gestureResumeCancel?.();
    this.gestureResumeCancel = null;
    this.gestureResumePlayback = null;
  }

  syncControls(expectedPlayback = null) {
    const playback = this.session?.snapshot();
    if (!playback || (expectedPlayback && playback !== expectedPlayback)) return;
    const { media } = playback;
    const duration = Number.isFinite(media.duration) && media.duration >= 0 ? media.duration : 0;
    const position = Math.min(duration || Number.MAX_SAFE_INTEGER, Math.max(0, media.currentTime || 0));
    const seek = this.querySelector('[data-site-player-seek]');
    seek.max = String(duration);
    seek.value = String(position);
    const controlState = siteMediaControlState(playback.descriptor, duration);
    seek.disabled = controlState.seekDisabled;
    seek.style.setProperty('--site-media-progress', `${duration > 0 ? (position / duration) * 100 : 0}%`);
    this.querySelector('[data-site-player-elapsed]').textContent = formatSiteMediaTime(position);
    this.querySelector('[data-site-player-duration]').textContent = formatSiteMediaTime(duration);

    const play = this.querySelector('[data-site-player-control="play"]');
    const isPlaying = !media.paused && !media.ended;
    play.textContent = isPlaying ? 'Ⅱ' : '▶';
    play.setAttribute('aria-label', isPlaying ? 'Pause' : 'Play');
    const mute = this.querySelector('[data-site-player-control="mute"]');
    const isMuted = media.muted || media.volume === 0;
    mute.textContent = isMuted ? '🔇' : '🔊';
    mute.setAttribute('aria-label', isMuted ? 'Unmute' : 'Mute');
    this.querySelector('[data-site-player-volume]').value = String(media.volume);
    this.querySelector('[data-site-player-control="rate"]').textContent = `${media.playbackRate}×`;
    this.querySelector('[data-site-player-control="rewind"]').disabled =
      controlState.rewindDisabled;
    this.querySelector('[data-site-player-control="forward"]').disabled =
      controlState.forwardDisabled;
    this.querySelector('[data-site-player-control="rate"]').disabled = controlState.rateDisabled;
    this.querySelector('.site-media-player-eyebrow').textContent =
      controlState.live ? 'Radio · Live' : 'Now playing';

    const isVideo = playback.descriptor.kind === 'VIDEO';
    const pictureInPicture = this.querySelector('[data-site-player-control="picture-in-picture"]');
    const fullscreen = this.querySelector('[data-site-player-control="fullscreen"]');
    pictureInPicture.hidden = !isVideo || typeof media.requestPictureInPicture !== 'function';
    fullscreen.hidden = !isVideo || (typeof media.requestFullscreen !== 'function'
      && typeof media.webkitEnterFullscreen !== 'function');
    this.querySelector('.site-media-player-more').hidden =
      pictureInPicture.hidden && fullscreen.hidden;
  }

  async openPictureInPicture() {
    const media = this.currentMedia();
    if (typeof media?.requestPictureInPicture !== 'function') return;
    try {
      await media.requestPictureInPicture();
    } catch (_) {
      this.setStatus('Picture in picture is unavailable');
    }
  }

  async openFullscreen() {
    const media = this.currentMedia();
    if (!media) return;
    let restoreCustomControls = null;
    try {
      if (typeof media.requestFullscreen === 'function') {
        media.controls = true;
        restoreCustomControls = () => {
          if (document.fullscreenElement !== media) {
            media.controls = false;
            document.removeEventListener('fullscreenchange', restoreCustomControls);
          }
        };
        document.addEventListener('fullscreenchange', restoreCustomControls);
        await media.requestFullscreen();
      } else if (typeof media.webkitEnterFullscreen === 'function') {
        media.webkitEnterFullscreen();
      }
    } catch (_) {
      if (restoreCustomControls) {
        document.removeEventListener('fullscreenchange', restoreCustomControls);
      }
      media.controls = false;
      this.setStatus('Fullscreen is unavailable');
    }
  }

  beginRadioLifetime() {
    this.stopRadioSync();
    const generation = this.radioGeneration;
    this.radioController = new AbortController();
    this.radioDurationReporter = createSiteRadioDurationReporter({
      report: async request => {
        const response = await fetchJson(API.sharedFolder.radio.duration, {
          method: 'POST',
          headers: authHeaders(),
          redirectOnUnauthorized: false,
          cache: 'no-store',
          body: JSON.stringify(request),
          signal: this.radioController.signal,
        });
        validateSiteRadioResponse(response);
      },
    });
    return generation;
  }

  stopRadioSync() {
    this.radioGeneration = (this.radioGeneration || 0) + 1;
    this.radioScheduler?.stop();
    this.radioController?.abort();
    this.radioController = null;
    this.radioDurationReporter = null;
    this.radioPlayback = null;
    this.radioPlayRequested = false;
    this.radioSyncPromise = null;
  }

  async fetchRadioSnapshot(signal) {
    const response = await fetchJson(API.sharedFolder.radio.playback, {
      headers: authHeaders(),
      redirectOnUnauthorized: false,
      cache: 'no-store',
      signal,
    });
    return validateSiteRadioResponse(response);
  }

  /** Join the server-owned station, using saved state only for intent and audio preferences. */
  async playSharedFolderRadio(resume = null) {
    const replacingRadio = this.session?.snapshot()?.descriptor.mode === 'RADIO';
    const generation = this.beginRadioLifetime();
    let response;
    try {
      response = await this.fetchRadioSnapshot(this.radioController.signal);
    } catch (error) {
      if (generation === this.radioGeneration) this.stopRadioSync();
      if (replacingRadio) this.stopPlayback();
      throw error;
    }
    if (generation !== this.radioGeneration) return response;
    if (response.status === 'EMPTY') {
      if (resume?.descriptor.mode === 'RADIO') clearSiteMediaResume(sessionStorage);
      if (replacingRadio) this.stopPlayback();
      else this.stopRadioSync();
      return response;
    }

    const station = response.playback;
    const saved = resume || {
      descriptor: {
        mode: 'RADIO', kind: 'AUDIO', title: station.entry.name, path: station.entry.path,
      },
      positionSeconds: 0,
      wasPlaying: true,
      playbackRate: 1,
      muted: false,
      volume: 1,
    };
    const radioResume = siteRadioResumeState(saved, station);
    this.radioPlayback = station;
    await this.playSharedFolderEntry(station.entry, radioResume, radioResume.descriptor);
    if (generation === this.radioGeneration) this.radioScheduler.start();
    return response;
  }

  async syncRadio({ requestPlay = false } = {}) {
    if (requestPlay) this.radioPlayRequested = true;
    if (!this.radioController || this.radioController.signal.aborted) return false;
    if (this.radioSyncPromise) return this.radioSyncPromise;
    const generation = this.radioGeneration;
    const operation = this.performRadioSync(generation).catch(error => {
      this.radioPlayRequested = false;
      this.handleRadioSyncError(error);
      return false;
    });
    this.radioSyncPromise = operation;
    void operation.finally(() => {
      if (this.radioSyncPromise !== operation) return;
      this.radioSyncPromise = null;
      if (this.radioPlayRequested && generation === this.radioGeneration) {
        void this.syncRadio({ requestPlay: true });
      }
    });
    return operation;
  }

  async performRadioSync(generation) {
    const response = await this.fetchRadioSnapshot(this.radioController.signal);
    if (generation !== this.radioGeneration) return false;
    const current = this.session?.snapshot();
    if (!current || current.descriptor.mode !== 'RADIO') return false;
    const decision = siteRadioSyncDecision({
      stationSequence: current.descriptor.stationSequence,
      path: current.descriptor.path,
    }, response, current.media.currentTime);
    if (decision.action === 'EMPTY') {
      clearSiteMediaResume(sessionStorage);
      this.setStatus('The radio has no audio tracks');
      this.stopPlayback();
      return true;
    }

    const requestedPlay = this.radioPlayRequested;
    this.radioPlayRequested = false;
    if (decision.action === 'REPLACE') {
      const wasPlaying = requestedPlay
        || (this.playbackIntentByMedia.get(current.media) ?? current.media.paused === false);
      const resume = siteRadioResumeState({
        descriptor: current.descriptor,
        positionSeconds: current.media.currentTime,
        wasPlaying,
        playbackRate: 1,
        muted: current.media.muted,
        volume: current.media.volume,
      }, response.playback);
      this.radioPlayback = response.playback;
      await this.playSharedFolderEntry(response.playback.entry, resume, resume.descriptor);
      return true;
    }

    this.radioPlayback = response.playback;
    if (decision.action === 'SEEK') current.media.currentTime = decision.targetPositionSeconds;
    if (requestedPlay) await this.continueRadioPlayback(current);
    this.syncControls(current);
    this.persistPlayback();
    return true;
  }

  async continueRadioPlayback(playback) {
    if (this.session?.snapshot() !== playback || playback.signal.aborted) return;
    this.pendingPlaybackStart.add(playback.media);
    this.playbackIntentByMedia.set(playback.media, true);
    try {
      await playback.media.play();
      this.pendingPlaybackStart.delete(playback.media);
      this.cancelGestureResume(playback);
    } catch (_) {
      this.persistPlayback();
      this.armGestureResume(playback);
      this.setStatus('Tap anywhere to continue live radio');
    }
  }

  reportRadioDuration(playback) {
    if (!this.radioDurationReporter || !this.radioPlayback
        || this.session?.snapshot() !== playback || playback.signal.aborted) return;
    const source = playback.media.currentSrc || playback.media.src;
    void this.radioDurationReporter.loaded(
      this.radioPlayback, source, Number(playback.media.duration),
    ).catch(error => this.handleRadioSyncError(error));
  }

  handleRadioSyncError(error) {
    if (error?.name === 'AbortError') return;
    if (isSharedFolderAccessDenied(error)) {
      this.handleAccessLoss(error.status);
      return;
    }
    const playback = this.session?.snapshot();
    if (playback?.descriptor.mode === 'RADIO') {
      this.pendingPlaybackStart.delete(playback.media);
      this.setStatus('Live sync is temporarily unavailable');
    }
  }

  /** Begin one shared-folder item, replacing any previous item without replacing the player. */
  async playSharedFolder(entry, resume = null) {
    this.stopRadioSync();
    return this.playSharedFolderEntry(entry, resume, { mode: 'ITEM' });
  }

  async playSharedFolderEntry(entry, resume = null, descriptor = { mode: 'ITEM' }) {
    const kind = String(entry?.previewKind || '').toUpperCase();
    if (!['AUDIO', 'VIDEO'].includes(kind)) {
      throw new TypeError('The site player only accepts audio or video files.');
    }
    const media = document.createElement(kind.toLowerCase());
    media.controls = false;
    media.preload = 'metadata';
    media.playsInline = true;
    media.title = entry.name;
    if (!resume) clearSiteMediaResume(sessionStorage);
    this.cancelGestureResume();
    this.releaseArtwork();
    const playback = this.session.start({
      mode: descriptor.mode || 'ITEM',
      kind,
      title: entry.name,
      path: entry.path,
      ...(descriptor.stationSequence === undefined
        ? {} : { stationSequence: descriptor.stationSequence }),
    }, media);
    const initialResume = resume || {
      descriptor: playback.descriptor,
      positionSeconds: 0,
      wasPlaying: true,
      playbackRate: 1,
      muted: false,
      volume: 1,
    };
    this.resumeByMedia.set(media, initialResume);
    this.playbackIntentByMedia.set(media, initialResume.wasPlaying);
    if (initialResume.wasPlaying) this.pendingPlaybackStart.add(media);
    saveSiteMediaResume(sessionStorage, playback.descriptor, {
      currentTime: initialResume.positionSeconds,
      paused: !initialResume.wasPlaying,
      ended: false,
      playbackRate: initialResume.playbackRate,
      muted: initialResume.muted,
      volume: initialResume.volume,
    }, { wasPlaying: initialResume.wasPlaying });
    this.bindPlaybackEvents(playback);
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
    await prepareSharedFolderMediaAuth(url);
    playback.signal.throwIfAborted();
    if (playback.descriptor.kind === 'AUDIO') {
      void this.loadAudioMetadata(playback, new URL(url, window.location.href).href);
    }
    playback.media.addEventListener('error', () => {
      if (!playback.signal.aborted) void this.loadFallbackSource(entry, playback);
    }, { once: true });
    this.resumePlaybackWhenReady(playback);
    playback.media.src = url;
    playback.media.load();
    this.setStatus(playback.descriptor.mode === 'RADIO' ? 'Live' : 'Ready');
  }

  async loadAudioMetadata(playback, url) {
    try {
      const metadata = await readSiteAudioMetadata(
        url, loadAudioMetadataReader, playback.signal);
      if (playback.signal.aborted || this.session?.snapshot() !== playback) return;
      this.renderAudioPresentation(playback, metadata);
    } catch (_) {
      // Tag parsing is optional; filename playback remains the complete fallback.
    }
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
      await prepareSharedFolderMediaAuth(url);
      playback.signal.throwIfAborted();
      playback.media.addEventListener('error', () => {
        if (!playback.signal.aborted) this.showRetry(entry, playback);
      }, { once: true });
      const priorResume = this.resumeByMedia.get(playback.media);
      if (priorResume && Number.isFinite(playback.media.currentTime)
          && playback.media.currentTime > priorResume.positionSeconds) {
        this.resumeByMedia.set(playback.media, {
          ...priorResume,
          positionSeconds: playback.media.currentTime,
        });
      }
      this.resumePlaybackWhenReady(playback);
      playback.media.src = url;
      playback.media.load();
      this.setStatus(mediaStatusMessage(playable.status));
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
      clearAuthState();
      window.location.replace(loginRedirectUrl(currentRedirectTarget()));
    }
  }

  mountPlayback(playback) {
    this.querySelector('[data-site-player-retry]')?.remove();
    const mediaHost = this.querySelector('[data-site-player-media]');
    if (playback.descriptor.kind === 'AUDIO') {
      const artwork = document.createElement('span');
      artwork.className = 'site-media-player-artwork';
      artwork.setAttribute('aria-hidden', 'true');
      artwork.textContent = '♫';
      mediaHost.replaceChildren(artwork, playback.media);
      this.renderAudioPresentation(playback, null);
    } else {
      mediaHost.replaceChildren(playback.media);
      this.querySelector('[data-site-player-track-metadata]').hidden = true;
      this.clearMediaSessionMetadata();
    }
    this.querySelector('[data-site-player-title]').textContent = playback.descriptor.title;
    this.dataset.kind = playback.descriptor.kind.toLowerCase();
    this.dataset.mode = playback.descriptor.mode.toLowerCase();
    this.lastPersistedSecond = null;
    this.querySelector('.site-media-player-more').open = false;
    this.hidden = false;
    document.body.classList.add('site-media-player-active');
    this.syncControls(playback);
    this.updatePlayerHeight();
  }

  unmountPlayback() {
    clearSiteMediaResume(sessionStorage);
    this.cancelGestureResume();
    this.releaseArtwork();
    this.clearMediaSessionMetadata();
    this.hidden = true;
    this.removeAttribute('data-kind');
    this.removeAttribute('data-mode');
    this.querySelector('.site-media-player-eyebrow').textContent = 'Now playing';
    this.querySelector('[data-site-player-media]').replaceChildren();
    this.querySelector('[data-site-player-retry]')?.remove();
    document.body.classList.remove('site-media-player-active', 'site-player-shell-active');
    document.documentElement.style.removeProperty(PLAYER_HEIGHT_PROPERTY);
    this.navigator.currentFrame()?.element.remove();
    this.navigator = this.createNavigator();
  }

  renderAudioPresentation(playback, metadata) {
    if (playback.descriptor.kind !== 'AUDIO' || this.session?.snapshot() !== playback) return;
    const presentation = siteAudioPresentation(metadata, playback.descriptor.title);
    this.querySelector('[data-site-player-title]').textContent = presentation.title;
    const trackMetadata = this.querySelector('[data-site-player-track-metadata]');
    trackMetadata.textContent = presentation.subtitle;
    trackMetadata.hidden = presentation.subtitle.length === 0;

    this.releaseArtwork();
    let artwork = null;
    if (presentation.picture) {
      this.artworkUrl = URL.createObjectURL(new Blob(
        [presentation.picture.bytes], { type: presentation.picture.type }));
      artwork = document.createElement('img');
      artwork.className = 'site-media-player-artwork';
      artwork.src = this.artworkUrl;
      artwork.alt = '';
    } else {
      artwork = document.createElement('span');
      artwork.className = 'site-media-player-artwork';
      artwork.setAttribute('aria-hidden', 'true');
      artwork.textContent = '♫';
    }
    const mediaHost = this.querySelector('[data-site-player-media]');
    mediaHost.replaceChildren(artwork, playback.media);
    this.setMediaSessionMetadata(presentation);
  }

  setMediaSessionMetadata(presentation) {
    if (!navigator.mediaSession || typeof globalThis.MediaMetadata !== 'function') return;
    const artwork = this.artworkUrl && presentation.picture
      ? [{ src: this.artworkUrl, type: presentation.picture.type }] : [];
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: presentation.title,
        artist: presentation.artist,
        album: presentation.album || '',
        artwork,
      });
    } catch (_) {
      // Lock-screen metadata is optional and must never interrupt playback.
    }
  }

  clearMediaSessionMetadata() {
    if (!navigator.mediaSession) return;
    try {
      navigator.mediaSession.metadata = null;
    } catch (_) {
      // Some partial Media Session implementations expose a read-only surface.
    }
  }

  releaseArtwork() {
    if (!this.artworkUrl) return;
    URL.revokeObjectURL(this.artworkUrl);
    this.artworkUrl = null;
  }

  stopPlayback() {
    this.stopRadioSync();
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
