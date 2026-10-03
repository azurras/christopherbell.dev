import assert from 'node:assert/strict';
import test from 'node:test';

const customElements = new Map();
globalThis.HTMLElement = class {};
globalThis.customElements = {
  define(name, constructor) {
    customElements.set(name, constructor);
  },
  get(name) {
    return customElements.get(name);
  },
};

class TestElement {
  constructor(tagName) {
    this.tagName = tagName;
    this.children = [];
    this.className = '';
    this.textContent = '';
  }

  appendChild(child) {
    this.children.push(child);
    return child;
  }

  replaceChildren(...children) {
    this.children = children;
  }
}

globalThis.document = {
  createElement: tagName => new TestElement(tagName),
};

await import('../../main/resources/static/js/components/blog.js');
const BlogPosts = customElements.get('blog-posts');

function render(posts) {
  const container = new TestElement('div');
  const component = new BlogPosts();
  component.posts = posts;
  component.querySelector = selector => {
    assert.equal(selector, '.blogPosts');
    return container;
  };
  component.updatePosts();
  return container;
}

test('empty post list renders an explicit Blog empty state', () => {
  const container = render([]);

  assert.equal(container.children.length, 1);
  assert.equal(container.children[0].tagName, 'p');
  assert.equal(container.children[0].className, 'text-center blog-empty-state');
  assert.equal(container.children[0].textContent, 'No posts have been published yet.');
});

test('configured post content continues to render as literal text', () => {
  const container = render([{
    author: 'Christopher',
    contentText: '<em>literal text</em>',
    title: 'A real post',
  }]);
  const article = container.children[0];
  const content = article.children.find(child => child.tagName === 'pre');

  assert.equal(article.tagName, 'article');
  assert.equal(content.textContent, '<em>literal text</em>');
});
