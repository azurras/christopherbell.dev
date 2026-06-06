import { sanitize } from './util.js';

const initializedRoots = new WeakSet();

/** Render the modal shell used for expanded post images. */
export function imageLightboxMarkup(src) {
  return `<div class="post-image-lightbox" role="dialog" aria-modal="true" aria-label="Post image preview" tabindex="-1">
    <button type="button" class="post-image-lightbox-close" aria-label="Close image preview">&times;</button>
    <img src="${sanitize(src)}" alt="Expanded post image">
  </div>`;
}

/** Render a stable fallback that keeps the original image URL reachable. */
export function imageFallbackMarkup(src) {
  return `<div class="post-image-fallback">
    <span>Image unavailable</span>
    <a href="${sanitize(src)}" target="_blank" rel="noopener noreferrer">Open source</a>
  </div>`;
}

/**
 * Attach click handling for post image previews.
 *
 * The delegated listener keeps dynamically-rendered feed items working without
 * per-page reinitialization.
 */
export function initPostImageLightbox(root = document) {
  if (!root?.addEventListener || initializedRoots.has(root)) return;
  initializedRoots.add(root);

  root.addEventListener('click', event => {
    const trigger = event.target?.closest?.('[data-post-image-src]');
    if (!trigger) return;

    event.preventDefault();
    const src = trigger.getAttribute('data-post-image-src');
    if (!src) return;

    const wrapper = document.createElement('div');
    wrapper.innerHTML = imageLightboxMarkup(src);
    const dialog = wrapper.firstElementChild;
    if (!dialog) return;

    document.body.appendChild(dialog);
    const previouslyFocused = document.activeElement;
    const close = () => {
      document.removeEventListener('keydown', handleKeydown);
      dialog.remove();
      previouslyFocused?.focus?.();
    };
    function handleKeydown(keyEvent) {
      if (keyEvent.key === 'Escape') {
        close();
        return;
      }
      if (keyEvent.key !== 'Tab') return;

      const focusable = [...dialog.querySelectorAll('button, a, img, [tabindex]:not([tabindex="-1"])')]
          .filter(element => !element.hasAttribute('disabled'));
      if (!focusable.length) {
        keyEvent.preventDefault();
        dialog.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (keyEvent.shiftKey && document.activeElement === first) {
        keyEvent.preventDefault();
        last.focus();
      } else if (!keyEvent.shiftKey && document.activeElement === last) {
        keyEvent.preventDefault();
        first.focus();
      }
    }

    dialog.querySelector('.post-image-lightbox-close')?.addEventListener('click', close);
    dialog.addEventListener('click', clickEvent => {
      if (clickEvent.target === dialog) close();
    });
    document.addEventListener('keydown', handleKeydown);
    dialog.querySelector('.post-image-lightbox-close')?.focus();
  });
}
