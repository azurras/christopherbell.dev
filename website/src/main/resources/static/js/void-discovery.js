import { API } from './lib/api.js';
import { createFeedItem } from './lib/feed-render.js';
import { makeRendererContext } from './lib/feed-context.js';
import { initLazyMedia } from './lib/lazy-media.js';
import { initPostImageLightbox } from './lib/image-lightbox.js';
import { authHeaders, fetchJson, formatWhen, isLoggedIn, sanitize } from './lib/util.js';
import {
  createDiscoverySectionState,
  loadDiscoverySection,
  topicHref,
} from './lib/void-discovery.js';

const PAGE_SIZE = 12;

function definitions() {
  const topic = document.body.dataset.topic || '';
  if (topic) {
    return [{ key: 'topic', kind: 'posts', url: cursor => API.posts.discovery.topic(topic, cursor, PAGE_SIZE) }];
  }
  return [
    { key: 'new', kind: 'posts', url: cursor => API.posts.discovery.new(cursor, PAGE_SIZE) },
    { key: 'fading', kind: 'posts', url: cursor => API.posts.discovery.fading(cursor, PAGE_SIZE) },
    { key: 'revived', kind: 'posts', url: cursor => API.posts.discovery.revived(cursor, PAGE_SIZE) },
    { key: 'topics', kind: 'topics', url: cursor => API.posts.discovery.topics(cursor, PAGE_SIZE) },
    { key: 'people', kind: 'people', url: () => API.posts.discovery.people },
  ];
}

function appendTopicChips(card, post) {
  const topics = Array.isArray(post.topics) ? post.topics : [];
  if (!topics.length) return;
  const chips = document.createElement('nav');
  chips.className = 'void-post-topics';
  chips.setAttribute('aria-label', 'Post topics');
  topics.forEach(topic => {
    if (!topic?.canonical) return;
    const link = document.createElement('a');
    link.className = 'void-topic-chip';
    link.href = topicHref(topic.canonical);
    link.textContent = `#${topic.display || topic.canonical}`;
    chips.append(link);
  });
  if (chips.childElementCount) card.append(chips);
}

function renderPosts(container, items, rendererContext) {
  items.forEach(post => {
    const card = createFeedItem(post, rendererContext);
    appendTopicChips(card, post);
    container.append(card);
  });
  initLazyMedia(container);
}

function renderTopics(container, items) {
  items.forEach(topic => {
    if (!topic?.canonical) return;
    const link = document.createElement('a');
    link.className = 'void-topic-chip void-topic-chip-large';
    link.href = topicHref(topic.canonical);
    link.textContent = `#${topic.display || topic.canonical}`;
    container.append(link);
  });
}

function renderPeople(container, items) {
  items.forEach(person => {
    if (!person?.accountId || !person?.username) return;
    const card = document.createElement('article');
    card.className = 'void-person-card';
    const avatar = document.createElement('span');
    avatar.className = 'void-person-avatar';
    avatar.textContent = person.username.slice(0, 1).toUpperCase();
    const copy = document.createElement('div');
    const profile = document.createElement('a');
    profile.className = 'void-person-name';
    profile.href = `/u/${encodeURIComponent(person.username)}`;
    profile.textContent = `@${person.username}`;
    const detail = document.createElement('p');
    const shared = Array.isArray(person.sharedTopics) ? person.sharedTopics.slice(0, 3) : [];
    detail.textContent = shared.length ? `Shared: ${shared.map(topic => `#${topic}`).join(' · ')}` : 'Recently active in the Void';
    copy.append(profile, detail);
    card.append(avatar, copy);
    container.append(card);
  });
}

function emptyCopy(kind) {
  if (kind === 'topics') return 'No active topics are transmitting right now.';
  if (kind === 'people') return 'No recent people to suggest right now.';
  return 'No active conversations in this section right now.';
}

function renderSection(definition, state, rendererContext) {
  const panel = document.querySelector(`[data-discovery-section="${definition.key}"]`);
  if (!panel) return;
  const status = panel.querySelector('[data-discovery-status]');
  const items = panel.querySelector('[data-discovery-items]');
  const retry = panel.querySelector('[data-discovery-action="retry"]');
  const more = panel.querySelector('[data-discovery-action="more"]');
  status.textContent = state.loading && state.items.length === 0 ? 'Loading…' : state.error || '';
  retry.hidden = !state.error;
  more.hidden = state.loading || Boolean(state.error) || !state.nextCursor;
  more.disabled = state.loading;
  items.replaceChildren();
  if (definition.kind === 'posts') renderPosts(items, state.items, rendererContext);
  if (definition.kind === 'topics') renderTopics(items, state.items);
  if (definition.kind === 'people') renderPeople(items, state.items);
  if (!state.loading && !state.error && state.items.length === 0) status.textContent = emptyCopy(definition.kind);
}

async function startSection(definition, rendererContext) {
  const state = createDiscoverySectionState(definition.key);
  const panel = document.querySelector(`[data-discovery-section="${definition.key}"]`);
  if (!panel) return;
  const load = async append => {
    panel.querySelector('[data-discovery-status]').textContent = 'Loading…';
    await loadDiscoverySection(state, async cursor => {
      const payload = await fetchJson(definition.url(cursor), { headers: authHeaders() });
      return definition.kind === 'people' ? { items: payload, nextCursor: null } : payload;
    }, append);
    renderSection(definition, state, rendererContext);
  };
  panel.querySelector('[data-discovery-action="retry"]').addEventListener('click', () => void load(false));
  panel.querySelector('[data-discovery-action="more"]').addEventListener('click', () => void load(true));
  await load(false);
}

document.addEventListener('DOMContentLoaded', () => {
  initPostImageLightbox();
  const rendererContext = makeRendererContext({
    fetchJson,
    authHeaders,
    sanitize,
    formatWhen,
    isLoggedIn,
    canDelete: () => false,
    canEdit: () => false,
    currentUserName: localStorage.getItem('cbellUsername') || null,
  });
  definitions().forEach(definition => void startSection(definition, rendererContext));
});
