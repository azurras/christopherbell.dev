/**
 * Initialize a simple post composer.
 *
 * @param {object} opts
 *  - selectors: { composer:'#composer', prompt:'#composerPrompt', textarea:'#postText', counter:'#charCount', button:'#postBtn', alert:'#homeAlert' }
 *  - maxLength: number (default 280)
 *  - isLoggedIn: ()=>boolean
 *  - onSubmit: (text:string)=>Promise<void>
 * @returns {{destroy:()=>void, reset:()=>void}}
 */
export function initComposer({ selectors, maxLength = 280, isLoggedIn, onSubmit }) {
  const composer = document.querySelector(selectors.composer);
  const prompt = document.querySelector(selectors.prompt);
  const textarea = document.querySelector(selectors.textarea);
  const counter = document.querySelector(selectors.counter);
  const button = document.querySelector(selectors.button);
  const alertEl = document.querySelector(selectors.alert);

  function toggle() {
    const auth = isLoggedIn();
    if (composer) composer.classList.toggle('d-none', !auth);
    if (prompt) prompt.classList.toggle('d-none', auth);
  }

  function updateCounter() {
    if (!counter || !textarea) return;
    const len = (textarea.value || '').length;
    counter.textContent = `${len} / ${maxLength}`;
  }

  async function handleSubmit() {
    if (alertEl) alertEl.classList.add('d-none');
    const text = (textarea?.value || '').trim();
    if (!text) return;
    if (text.length > maxLength) {
      if (alertEl) { alertEl.textContent = `Post text exceeds ${maxLength} characters.`; alertEl.classList.remove('d-none'); }
      return;
    }
    try {
      if (button) button.disabled = true;
      await onSubmit(text);
      reset();
    } catch (err) {
      if (alertEl) {
        alertEl.textContent = err.message || 'Unable to submit post.';
        alertEl.classList.remove('d-none');
      }
    } finally {
      if (button) button.disabled = false;
    }
  }

  function handleKeydown(event) {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
      event.preventDefault();
      handleSubmit();
    }
  }

  function reset() {
    if (textarea) textarea.value = '';
    updateCounter();
  }

  // Wire up
  toggle();
  updateCounter();
  textarea?.addEventListener('input', updateCounter);
  textarea?.addEventListener('keydown', handleKeydown);
  button?.addEventListener('click', handleSubmit);

  return {
    destroy() {
      textarea?.removeEventListener('input', updateCounter);
      textarea?.removeEventListener('keydown', handleKeydown);
      button?.removeEventListener('click', handleSubmit);
    },
    reset,
  };
}
