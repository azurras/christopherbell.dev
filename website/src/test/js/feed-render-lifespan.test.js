import assert from 'node:assert/strict';
import test from 'node:test';

import {
  directGifUrl,
  directImageUrl,
  expiresSoon,
  formatLifespanCountdown,
  githubCardDetail,
  linkPreviewCardMarkup,
  remainingLifespanMs,
  richEmbedMarkupForPost,
  richEmbedsForPost,
  soundCloudEmbedUrl,
  spotifyEmbedUrl,
  youtubeEmbedUrl,
  youtubeEmbedUrlsForPost
} from '../../main/resources/static/js/lib/feed-render.js';

test('formatLifespanCountdown shows days and a tabular clock', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now + 90061000).toISOString();

  assert.equal(formatLifespanCountdown(expiresOn, now), '1d 01:01:01');
});

test('formatLifespanCountdown shows the final second before expiry', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now + 1000).toISOString();

  assert.equal(formatLifespanCountdown(expiresOn, now), '00:00:01');
});

test('remainingLifespanMs returns zero once a post has expired', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);
  const expiresOn = new Date(now - 1).toISOString();

  assert.equal(remainingLifespanMs(expiresOn, now), 0);
});

test('lifespan helpers hide countdowns without a usable expiry', () => {
  assert.equal(remainingLifespanMs('not-a-date'), null);
  assert.equal(formatLifespanCountdown(), '');
});

test('expiresSoon starts at twelve hours remaining', () => {
  const now = Date.UTC(2026, 4, 21, 12, 0, 0);

  assert.equal(expiresSoon({ expiresOn: new Date(now + 12 * 60 * 60 * 1000).toISOString() }, now), true);
  assert.equal(expiresSoon({ expiresOn: new Date(now + 12 * 60 * 60 * 1000 + 1).toISOString() }, now), false);
});

test('link preview markup keeps rich metadata outside post text', () => {
  const markup = linkPreviewCardMarkup({
    url: 'https://example.com/lunch',
    domain: 'example.com',
    title: '<Lunch Picks>',
    description: 'Three places nearby',
    imageUrl: 'https://example.com/lunch.jpg'
  }, text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;'));

  assert.match(markup, /class="post-link-preview"/);
  assert.match(markup, /&lt;Lunch Picks&gt;/);
  assert.match(markup, /Three places nearby/);
  assert.match(markup, /lunch\.jpg/);
});

test('youtubeEmbedUrl supports common YouTube URL formats', () => {
  assert.equal(
    youtubeEmbedUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s'),
    'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'
  );
  assert.equal(
    youtubeEmbedUrl('https://youtu.be/dQw4w9WgXcQ.'),
    'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'
  );
  assert.equal(
    youtubeEmbedUrl('https://youtube.com/shorts/dQw4w9WgXcQ?feature=share'),
    'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'
  );
  assert.equal(
    youtubeEmbedUrl('https://www.youtube.com/embed/dQw4w9WgXcQ'),
    'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'
  );
});

test('youtubeEmbedUrl rejects non-YouTube and malformed video ids', () => {
  assert.equal(youtubeEmbedUrl('https://example.com/watch?v=dQw4w9WgXcQ'), '');
  assert.equal(youtubeEmbedUrl('https://youtube.com/watch?v=bad'), '');
});

test('youtubeEmbedUrlsForPost deduplicates text and preview YouTube links', () => {
  const post = {
    text: 'watch https://youtu.be/dQw4w9WgXcQ and https://youtube.com/watch?v=dQw4w9WgXcQ',
    linkPreviews: [
      { url: 'https://www.youtube.com/embed/dQw4w9WgXcQ' },
      { url: 'https://www.youtube.com/shorts/abcdefghijk' }
    ]
  };

  assert.deepEqual(youtubeEmbedUrlsForPost(post), [
    'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ',
    'https://www.youtube-nocookie.com/embed/abcdefghijk'
  ]);
});

test('directImageUrl supports common web image formats only', () => {
  assert.equal(directImageUrl('https://example.com/cat.jpg?size=large'), 'https://example.com/cat.jpg?size=large');
  assert.equal(directImageUrl('https://example.com/cat.webp.'), 'https://example.com/cat.webp');
  assert.equal(directImageUrl('https://example.com/cat.avif'), 'https://example.com/cat.avif');
  assert.equal(directImageUrl('https://pbs.twimg.com/media/example?format=jpg&name=large'), 'https://pbs.twimg.com/media/example?format=jpg&name=large');
  assert.equal(directImageUrl('https://images.example.com/render?id=42&fm=webp'), 'https://images.example.com/render?id=42&fm=webp');
  assert.equal(directImageUrl('https://example.com/vector.svg'), '');
  assert.equal(directImageUrl('https://example.com/file?id=42&format=svg'), '');
  assert.equal(directImageUrl('not-a-url'), '');
});

test('directGifUrl supports animated gif links only', () => {
  assert.equal(directGifUrl('https://example.com/reaction.gif?size=large'), 'https://example.com/reaction.gif?size=large');
  assert.equal(directGifUrl('https://pbs.twimg.com/media/example?format=gif&name=large'), 'https://pbs.twimg.com/media/example?format=gif&name=large');
  assert.equal(directGifUrl('https://images.example.com/render?id=42&fm=gif.'), 'https://images.example.com/render?id=42&fm=gif');
  assert.equal(directGifUrl('https://example.com/photo.jpg'), '');
  assert.equal(directGifUrl('not-a-url'), '');
});

test('spotifyEmbedUrl supports allowlisted Spotify content types', () => {
  assert.equal(
    spotifyEmbedUrl('https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=test'),
    'https://open.spotify.com/embed/track/4uLU6hMCjMI75M1A2tKUQC'
  );
  assert.equal(
    spotifyEmbedUrl('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'),
    'https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M'
  );
  assert.equal(spotifyEmbedUrl('https://open.spotify.com/user/example'), '');
});

test('soundCloudEmbedUrl creates a widget iframe URL for SoundCloud links', () => {
  assert.equal(
    soundCloudEmbedUrl('https://soundcloud.com/example/song-name?utm_source=feed'),
    'https://w.soundcloud.com/player/?url=https%3A%2F%2Fsoundcloud.com%2Fexample%2Fsong-name%3Futm_source%3Dfeed'
  );
  assert.equal(soundCloudEmbedUrl('https://example.com/example/song-name'), '');
});

test('githubCardDetail identifies repositories, issues, and pull requests', () => {
  assert.deepEqual(githubCardDetail('https://github.com/openai/codex'), {
    href: 'https://github.com/openai/codex',
    owner: 'openai',
    repo: 'codex',
    label: 'Repository',
    title: 'openai/codex'
  });
  assert.deepEqual(githubCardDetail('https://github.com/openai/codex/issues/42'), {
    href: 'https://github.com/openai/codex/issues/42',
    owner: 'openai',
    repo: 'codex',
    label: 'Issue #42',
    title: 'openai/codex'
  });
  assert.deepEqual(githubCardDetail('https://github.com/openai/codex/pull/7'), {
    href: 'https://github.com/openai/codex/pull/7',
    owner: 'openai',
    repo: 'codex',
    label: 'Pull request #7',
    title: 'openai/codex'
  });
  assert.equal(githubCardDetail('https://gist.github.com/openai/codex'), null);
});

test('richEmbedsForPost deduplicates provider URLs across text and previews', () => {
  const post = {
    text: [
      'Watch https://youtu.be/dQw4w9WgXcQ',
      'image https://example.com/lunch.png',
      'music https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC',
      'code https://github.com/openai/codex'
    ].join(' '),
    linkPreviews: [
      { url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' },
      { url: 'https://example.com/lunch.png' },
      { url: 'https://soundcloud.com/example/song-name' }
    ]
  };

  assert.deepEqual(richEmbedsForPost(post).map(embed => embed.type), [
    'youtube',
    'image',
    'spotify',
    'github',
    'soundcloud'
  ]);
});

test('richEmbedsForPost identifies direct gif links separately from static images', () => {
  const post = {
    text: 'gif https://example.com/reaction.gif static https://example.com/lunch.png'
  };

  assert.deepEqual(richEmbedsForPost(post).map(embed => embed.type), [
    'gif',
    'image'
  ]);
});

test('richEmbedMarkupForPost renders provider-specific markup safely', () => {
  const markup = richEmbedMarkupForPost({
    text: 'image https://example.com/lunch.jpg code https://github.com/openai/codex'
  }, text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;'));

  assert.match(markup, /class="post-rich-embeds"/);
  assert.match(markup, /post-rich-image/);
  assert.match(markup, /lunch\.jpg/);
  assert.match(markup, /post-rich-card-github/);
  assert.match(markup, /openai\/codex/);
});

test('richEmbedMarkupForPost renders direct gifs as animated image cards', () => {
  const markup = richEmbedMarkupForPost({
    text: 'gif https://example.com/reaction.gif'
  }, text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;'));

  assert.match(markup, /post-rich-gif-trigger/);
  assert.match(markup, /post-rich-gif-badge/);
  assert.match(markup, /Animated GIF/);
  assert.match(markup, /data-post-image-src="https:\/\/example.com\/reaction.gif"/);
});

test('richEmbedMarkupForPost groups multiple direct images', () => {
  const markup = richEmbedMarkupForPost({
    text: 'one https://example.com/1.jpg two https://example.com/2.png'
  }, text => String(text).replaceAll('<', '&lt;').replaceAll('>', '&gt;'));

  assert.match(markup, /post-rich-embeds-images/);
  assert.match(markup, /data-image-count="2"/);
  assert.match(markup, /data-post-image-src="https:\/\/example.com\/1.jpg"/);
  assert.match(markup, /data-post-image-src="https:\/\/example.com\/2.png"/);
});
