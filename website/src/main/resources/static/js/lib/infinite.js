/**
 * Simple infinite scroll helper (window-based).
 *
 * Usage:
 *   const scroller = createInfiniteScroller({
 *     fetchPage: async ({ before, limit }) => [...items],
 *     onPage: (items) => { * append to DOM * },
 *     getCursor: (item) => item.createdOn || item.lastUpdatedOn,
 *     thresholdPx: 200,
 *     limit: 20,
 *   });
 *   scroller.loadInitial();
 */
export function createInfiniteScroller({ fetchPage, onPage, getCursor, thresholdPx = 200, limit = 20, onEmpty }) {
  let before = null;
  let cursor = null;
  let loading = false;
  let done = false;

  const cursorFn = getCursor || ((it) => it.createdOn || it.lastUpdatedOn);

  async function load(renew = false) {
    if (loading || done) return;
    loading = true;
    try {
      let firstRequest = renew;
      while (!done) {
        const page = await fetchPage({
          before: firstRequest ? null : before,
          cursor: firstRequest ? null : cursor,
          limit
        });
        const items = Array.isArray(page) ? page : page?.items;
        if (!Array.isArray(items)) {
          done = true;
          return;
        }
        if (!Array.isArray(page)) {
          cursor = page.nextCursor || null;
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
    } finally {
      loading = false;
    }
  }

  function onScroll() {
    const nearBottom = window.innerHeight + window.scrollY >= document.body.offsetHeight - thresholdPx;
    if (nearBottom) load(false);
  }

  function loadInitial() {
    before = null;
    cursor = null;
    loading = false;
    done = false;
    load(true);
  }

  function attach() {
    window.addEventListener('scroll', onScroll);
  }

  function detach() {
    window.removeEventListener('scroll', onScroll);
  }

  return { loadInitial, attach, detach };
}
