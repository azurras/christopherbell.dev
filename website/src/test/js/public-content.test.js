import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';

globalThis.HTMLElement = class {};
globalThis.customElements = { define() {} };

function filesUnder(root) {
  return fs.readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const child = path.join(root, entry.name);
    return entry.isDirectory() ? filesUnder(child) : [child];
  });
}

test('public components use versioned APIs and unwrap Response payloads', async () => {
  const { blogPostsFromResponse } = await import('../../main/resources/static/js/components/blog.js');
  const { galleryImagesFromResponse } = await import('../../main/resources/static/js/components/gallery.js');
  const { API } = await import('../../main/resources/static/js/lib/api.js');

  assert.equal(API.blog.posts, '/api/blog/v1/posts');
  assert.equal(API.photos.images, '/api/photo/v1');
  assert.deepEqual(blogPostsFromResponse({ payload: { posts: [{ id: 'post-1' }] } }), [
    { id: 'post-1' },
  ]);
  assert.deepEqual(galleryImagesFromResponse({ payload: { images: [{ id: 'photo-1' }] } }), [
    { id: 'photo-1' },
  ]);
  assert.deepEqual(blogPostsFromResponse({ posts: [{ id: 'unwrapped-post' }] }), [
    { id: 'unwrapped-post' },
  ]);
  assert.deepEqual(galleryImagesFromResponse({ images: [{ id: 'unwrapped-photo' }] }), [
    { id: 'unwrapped-photo' },
  ]);
  assert.deepEqual(blogPostsFromResponse({ payload: { posts: 'invalid' } }), []);
  assert.deepEqual(galleryImagesFromResponse({ payload: {} }), []);
});

test('gallery alt text prefers description then name and has a content fallback', async () => {
  const { galleryAltText } = await import('../../main/resources/static/js/components/gallery.js');

  assert.equal(
    galleryAltText({ description: 'Austin skyline', name: 'Skyline' }),
    'Austin skyline'
  );
  assert.equal(galleryAltText({ description: ' ', name: 'Skyline' }), 'Skyline');
  assert.equal(galleryAltText({ description: 'n/a', name: 'Skyline' }), 'Skyline');
  assert.equal(galleryAltText({}), 'Gallery photo');
});

test('The Bell archive has real links, local assets, and no insecure image source', () => {
  const archiveFiles = [
    'website/src/main/resources/templates/thebell/index.html',
    'website/src/main/resources/templates/thebell/tony.html',
  ];

  for (const file of archiveFiles) {
    const html = fs.readFileSync(file, 'utf8');
    assert.doesNotMatch(html, /href=(?:""|"3")/i, file);
    assert.doesNotMatch(html, /src="http:\/\//i, file);

    for (const match of html.matchAll(/(?:href|src)="(\/images\/thebell\/[^"?]+)"/g)) {
      assert.equal(
        fs.existsSync(path.join('website/src/main/resources/static', match[1])),
        true,
        `${file}: ${match[1]}`
      );
    }
  }
});

test('Bootstrap is self-hosted and no CDN include remains', () => {
  const mainCss = fs.readFileSync('website/src/main/resources/static/css/main.css', 'utf8');
  assert.match(mainCss, /\/webjars\/bootstrap\/5\.3\.3\/css\/bootstrap\.min\.css/);

  for (const file of filesUnder('website/src/main/resources')) {
    if (!/\.(?:css|html)$/i.test(file)) continue;
    assert.doesNotMatch(
      fs.readFileSync(file, 'utf8'),
      /cdn\.jsdelivr\.net\/npm\/bootstrap/i,
      file
    );
  }
});

test('restaurant website links allow only absolute HTTP(S) URLs', async () => {
  const { restaurantWebsiteUrl } = await import('../../main/resources/static/js/lib/restaurant-website.js');

  assert.equal(restaurantWebsiteUrl('https://example.com/menu'), 'https://example.com/menu');
  assert.equal(restaurantWebsiteUrl('http://example.com'), 'http://example.com');
  assert.equal(restaurantWebsiteUrl('javascript:alert(1)'), null);
  assert.equal(restaurantWebsiteUrl('data:text/html,unsafe'), null);
  assert.equal(restaurantWebsiteUrl('/relative-path'), null);
});
