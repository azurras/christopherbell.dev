/**
 * Simple infinite scroll helper (window-based).
 *
 * Usage:
 *   const scroller = createInfiniteScroller({
 *     fetchPage: async ({ before, limit }) => [...items],
 *     onPage: (items) => { * append to DOM * },
 *     onError: (error) => { * report page-load failure * },
 *     onSuccess: () => { * clear recovered page-load feedback * },
 *     getCursor: (item) => item.createdOn || item.lastUpdatedOn,
 *     thresholdPx: 200,
 *     limit: 20,
 *   });
 *   scroller.loadInitial();
 */
export function createInfiniteScroller({
  fetchPage,
  onPage,
  onError = error => console.error('Infinite feed load failed.', error),
  onSuccess = () => {},
  getCursor,
  thresholdPx = 200,
  limit = 20,
  onEmpty,
}) {
  let before = null;
  let cursor = null;
  let loading = false;
  let done = false;
  let loadGeneration = 0;
  let seenCursors = new Set();

  const cursorFn = getCursor || ((it) => it.createdOn || it.lastUpdatedOn);

  async function load(renew = false) {
    if (loading || done) return;
    loading = true;
    const generation = loadGeneration;
    try {
      let firstRequest = renew;
      while (!done) {
        const requestCursor = firstRequest ? null : cursor;
        const page = await fetchPage({
          before: firstRequest ? null : before,
          cursor: requestCursor,
          limit
        });
        if (generation !== loadGeneration) return;
        const items = Array.isArray(page) ? page : page?.items;
        if (!Array.isArray(items)) {
          throw new TypeError('Feed page response must contain an items array.');
        }
        const nextCursor = Array.isArray(page) ? cursor : page.nextCursor || null;
        if (!Array.isArray(page) && nextCursor) {
          if (nextCursor === requestCursor || seenCursors.has(nextCursor)) {
            throw new Error('Feed page cursor did not advance.');
          }
          seenCursors.add(nextCursor);
        }
        onSuccess();
        if (!Array.isArray(page)) {
          cursor = nextCursor;
        }
        if (items.length > 0) {
          onPage(items);
          before = cursorFn(items[items.length - 1]);
          done = Array.isArray(page) ? items.length < limit : !cursor;
          return;
        }
        if (Array.isArray(page) || !cursor) {
          if (renew && typeof onEmpty === 'function') onEmpty();
          done = true;
          return;
        }
        firstRequest = false;
      }
    } catch (error) {
      if (generation === loadGeneration) onError(error);
    } finally {
      if (generation === loadGeneration) loading = false;
    }
  }

  function onScroll() {
    const nearBottom = window.innerHeight + window.scrollY >= document.body.offsetHeight - thresholdPx;
    if (nearBottom) return load(false);
  }

  function loadInitial() {
    loadGeneration += 1;
    before = null;
    cursor = null;
    loading = false;
    done = false;
    seenCursors = new Set();
    return load(true);
  }

  function attach() {
    window.addEventListener('scroll', onScroll);
  }

  function detach() {
    window.removeEventListener('scroll', onScroll);
  }

  return { loadInitial, attach, detach };
}
