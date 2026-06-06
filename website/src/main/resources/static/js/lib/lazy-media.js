/** Build iframe markup that activates only when lazy media initializes it. */
export function lazyIframeMarkup({ className, src, title, allow }, sanitize) {
  if (typeof sanitize !== 'function') return '';
  return `<iframe
        class="${sanitize(className)}"
        data-src="${sanitize(src)}"
        title="${sanitize(title)}"
        loading="lazy"
        referrerpolicy="strict-origin-when-cross-origin"
        allow="${sanitize(allow)}"
        allowfullscreen></iframe>`;
}

/** Activate deferred media near the viewport, or immediately without observer support. */
export function initLazyMedia(root = document) {
  const frames = [...root.querySelectorAll('iframe[data-src]')];
  if (!frames.length) return;

  if (!('IntersectionObserver' in window)) {
    frames.forEach(activateFrame);
    return;
  }

  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      activateFrame(entry.target);
      observer.unobserve(entry.target);
    }
  }, { rootMargin: '500px' });

  frames.forEach(frame => observer.observe(frame));
}

function activateFrame(frame) {
  const source = frame?.dataset?.src;
  if (!source) return;
  frame.setAttribute('src', source);
  frame.removeAttribute('data-src');
}
