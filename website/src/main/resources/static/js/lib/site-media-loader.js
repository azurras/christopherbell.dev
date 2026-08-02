const RESUME_KEY = 'cbellSiteMediaResumeV1';

/**
 * Own the lazy site-media runtime and its one-time document integration.
 * Dynamic-import failures clear the cached promise so a later user action can retry.
 */
export function createSiteMediaLoader({
  loadComponent,
  loadApi,
  windowRoot,
  documentRoot,
  findPlayerHost = () => null,
  playerStylesheetUrl,
}) {
  let runtimePromise;

  function ensurePlayerStyles() {
    if (documentRoot.querySelector('link[data-site-media-player-styles]')) return;
    const link = documentRoot.createElement('link');
    link.rel = 'stylesheet';
    link.dataset.siteMediaPlayerStyles = 'true';
    link.href = playerStylesheetUrl;
    documentRoot.head.appendChild(link);
  }

  async function loadRuntime() {
    if (!runtimePromise) {
      runtimePromise = Promise.all([loadComponent(), loadApi()])
        .then(([, api]) => {
          ensurePlayerStyles();
          if (windowRoot.top === windowRoot && !api.siteMediaPlayerHost(windowRoot)) {
            documentRoot.body.appendChild(
              documentRoot.createElement(api.SITE_MEDIA_PLAYER_TAG),
            );
          }
          documentRoot.addEventListener('click', api.handleSiteNavigationClick, true);
          return api;
        })
        .catch(error => {
          runtimePromise = undefined;
          throw error;
        });
    }
    return runtimePromise;
  }

  return Object.freeze({
    async resumeSiteMediaIfPresent(storage) {
      if (!storage.getItem(RESUME_KEY)) return false;
      await loadRuntime();
      return true;
    },
    async playSharedFolderMedia(...args) {
      return (await loadRuntime()).playSharedFolderMedia(...args);
    },
    async playSharedFolderRadio(...args) {
      return (await loadRuntime()).playSharedFolderRadio(...args);
    },
    async playMusicTrack(...args) {
      return (await loadRuntime()).playMusicTrack(...args);
    },
    async playMusicRadio(...args) {
      return (await loadRuntime()).playMusicRadio(...args);
    },
    async stopSiteMediaPlayback() {
      if (!runtimePromise) {
        findPlayerHost()?.stopPlayback();
        return;
      }
      try {
        (await runtimePromise).stopSiteMediaPlayback();
      } catch {
        // Access-loss and logout cleanup must continue after a failed import.
      }
    },
  });
}

let defaultLoader;

function siteMediaLoader() {
  if (!defaultLoader) {
    defaultLoader = createSiteMediaLoader({
      loadComponent: () => import('../components/site-media-player.js'),
      loadApi: () => import('./site-media-player.js'),
      windowRoot: window,
      documentRoot: document,
      findPlayerHost: () => {
        try {
          return window.top?.document?.querySelector('site-media-player')
            ?? document.querySelector('site-media-player');
        } catch {
          return document.querySelector('site-media-player');
        }
      },
      playerStylesheetUrl: new URL('../../css/site-media-player.css', import.meta.url).href,
    });
  }
  return defaultLoader;
}

export function resumeSiteMediaIfPresent(storage = window.sessionStorage) {
  return siteMediaLoader().resumeSiteMediaIfPresent(storage);
}

export function playSharedFolderMedia(...args) {
  return siteMediaLoader().playSharedFolderMedia(...args);
}

export function playSharedFolderRadio(...args) {
  return siteMediaLoader().playSharedFolderRadio(...args);
}

export function playMusicTrack(...args) {
  return siteMediaLoader().playMusicTrack(...args);
}

export function playMusicRadio(...args) {
  return siteMediaLoader().playMusicRadio(...args);
}

export function stopSiteMediaPlayback() {
  return siteMediaLoader().stopSiteMediaPlayback();
}
