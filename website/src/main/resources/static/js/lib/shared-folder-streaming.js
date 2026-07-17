export const SHARED_FOLDER_API_PREFIX = '/api/shared-folder/2026-07-17/';
export const SHARED_FOLDER_AUTH_WORKER_PATH = '/shared-folder-auth-sw.js';

/** Return whether a request is same-origin and precisely inside the shared-folder API surface. */
export function isSharedFolderApiRequest(requestOrUrl, origin) {
  const url = new URL(
    typeof requestOrUrl === 'string' ? requestOrUrl : requestOrUrl.url,
    origin,
  );
  return url.origin === origin && url.pathname.startsWith(SHARED_FOLDER_API_PREFIX);
}

/** Clone a request with the current JWT while retaining headers such as Range and its URL. */
export function attachSharedFolderAuthorization(request, token, origin) {
  if (!isSharedFolderApiRequest(request, origin)) return request;
  const headers = new Headers(request.headers);
  headers.set('Authorization', `Bearer ${token}`);
  return new Request(request, { headers });
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

/** Register, control, and acknowledge the worker before a native URL is assigned to media or an anchor. */
export async function prepareSharedFolderStreamingAuth(token) {
  if (!token) throw new Error('Authentication is required for shared-folder streaming.');
  if (!('serviceWorker' in navigator)) {
    throw new Error('This browser cannot securely stream shared-folder files.');
  }
  await navigator.serviceWorker.register(SHARED_FOLDER_AUTH_WORKER_PATH, {
    scope: '/',
    type: 'module',
  });
  await navigator.serviceWorker.ready;
  const controller = await waitForExpectedController();
  await setWorkerToken(controller, token);
}

/** Remove this browser client’s transient streaming token after logout. */
export function clearSharedFolderStreamingAuth() {
  const controller = navigator.serviceWorker?.controller;
  if (isExpectedWorker(controller)) {
    controller.postMessage({ type: 'shared-folder-auth-clear' });
  }
}

function isExpectedWorker(worker) {
  return !!worker
    && new URL(worker.scriptURL).pathname === SHARED_FOLDER_AUTH_WORKER_PATH;
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

function setWorkerToken(controller, token) {
  return new Promise((resolve, reject) => {
    const channel = new MessageChannel();
    const timeout = window.setTimeout(() => reject(new Error('Secure shared-folder streaming did not acknowledge authentication.')), 5000);
    channel.port1.onmessage = event => {
      window.clearTimeout(timeout);
      if (event.data?.type === 'shared-folder-auth-ready') {
        resolve();
      } else {
        reject(new Error('Secure shared-folder streaming rejected authentication.'));
      }
    };
    controller.postMessage({ type: 'shared-folder-auth-token', token }, [channel.port2]);
  });
}
