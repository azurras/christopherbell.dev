export const SHARED_FOLDER_API_PREFIX = '/api/shared-folder/2026-07-17/';
export const SHARED_FOLDER_AUTH_WORKER_PATH = '/shared-folder-auth-sw.js';
const SHARED_FOLDER_AUTH_WORKER_REVISION = '20260725';
const SHARED_FOLDER_AUTH_WORKER_URL =
  `${SHARED_FOLDER_AUTH_WORKER_PATH}?v=${SHARED_FOLDER_AUTH_WORKER_REVISION}`;
const DOWNLOAD_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** Return whether a request is same-origin and precisely inside the shared-folder API surface. */
export function isSharedFolderApiRequest(requestOrUrl, origin) {
  const url = new URL(
    typeof requestOrUrl === 'string' ? requestOrUrl : requestOrUrl.url,
    origin,
  );
  return url.origin === origin && url.pathname.startsWith(SHARED_FOLDER_API_PREFIX);
}

/** Add a non-secret one-time correlation id to one exact same-origin download URL. */
export function sharedFolderDownloadRequestUrl(contentUrl, downloadId, origin) {
  const url = new URL(contentUrl, origin);
  if (url.origin !== origin || url.pathname !== `${SHARED_FOLDER_API_PREFIX}content`
      || !DOWNLOAD_ID.test(downloadId)) {
    throw new Error('The shared-folder download request is invalid.');
  }
  url.searchParams.set('downloadId', downloadId);
  return url.href;
}

/** Map native streaming denial responses to UI behavior. */
export function sharedFolderStreamingDenial(status) {
  if (status === 401) {
    return {
      message: 'Your session expired. Redirecting to login.',
      redirectToLogin: true,
    };
  }
  return {
    message: 'Shared-folder access was denied. Your access may have been revoked.',
    redirectToLogin: false,
  };
}

/** Register and control the worker before assigning a native media or download URL. */
export async function prepareSharedFolderStreamingAuth() {
  if (!('serviceWorker' in navigator)) {
    throw new Error('This browser cannot securely stream shared-folder files.');
  }
  await navigator.serviceWorker.register(SHARED_FOLDER_AUTH_WORKER_URL, {
    scope: '/',
    type: 'module',
  });
  await navigator.serviceWorker.ready;
  return waitForExpectedController();
}

/** Prepare a cookie-authenticated native download request. */
export async function prepareSharedFolderDownloadAuth(requestUrl) {
  if (!isSharedFolderApiRequest(requestUrl, window.location.origin)) {
    throw new Error('The shared-folder download request is invalid.');
  }
  await prepareSharedFolderStreamingAuth();
}

/** Prepare a cookie-authenticated native media request. */
export async function prepareSharedFolderMediaAuth(requestUrl) {
  if (!isSharedFolderApiRequest(requestUrl, window.location.origin)) {
    throw new Error('The shared-folder media request is invalid.');
  }
  await prepareSharedFolderStreamingAuth();
}

function isExpectedWorker(worker) {
  if (!worker) return false;
  const url = new URL(worker.scriptURL);
  return url.pathname === SHARED_FOLDER_AUTH_WORKER_PATH
    && url.search === `?v=${SHARED_FOLDER_AUTH_WORKER_REVISION}`;
}

function waitForExpectedController() {
  if (isExpectedWorker(navigator.serviceWorker.controller)) {
    return Promise.resolve(navigator.serviceWorker.controller);
  }
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(() => {
      navigator.serviceWorker.removeEventListener('controllerchange', onControllerChange);
      reject(new Error('Secure shared-folder streaming did not become ready.'));
    }, 5000);
    const onControllerChange = () => {
      const controller = navigator.serviceWorker.controller;
      if (!isExpectedWorker(controller)) return;
      window.clearTimeout(timeout);
      navigator.serviceWorker.removeEventListener('controllerchange', onControllerChange);
      resolve(controller);
    };
    navigator.serviceWorker.addEventListener('controllerchange', onControllerChange);
  });
}
