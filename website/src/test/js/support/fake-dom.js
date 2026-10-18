/**
 * A minimal DOM for keyboard and pointer tests under Node: elements, attribute selectors,
 * bubbling events, focus and `closest`. It supports only what the Survive menus use.
 */
class FakeElement {
  constructor(documentRoot, tagName, attributes = {}) {
    this.ownerDocument = documentRoot;
    this.tagName = tagName.toUpperCase();
    this.attributes = new Map();
    this.children = [];
    this.parent = null;
    this.dataset = {};
    this.listeners = new Map();
    this.hidden = false;
    this.disabled = false;
    this.textContent = '';
    for (const [name, value] of Object.entries(attributes)) {
      this.setAttribute(name, value);
    }
  }

  get id() { return this.getAttribute('id') ?? ''; }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
    if (name.startsWith('data-')) {
      const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      this.dataset[key] = String(value);
    }
  }

  getAttribute(name) { return this.attributes.get(name) ?? null; }

  hasAttribute(name) { return this.attributes.has(name); }

  removeAttribute(name) { this.attributes.delete(name); }

  appendChild(child) {
    child.parent = this;
    this.children.push(child);
    return child;
  }

  replaceChildren(...children) {
    for (const child of children) {
      child.parent = this;
    }
    this.children = children;
  }

  contains(node) {
    for (let candidate = node; candidate; candidate = candidate.parent) {
      if (candidate === this) {
        return true;
      }
    }
    return false;
  }

  descendants() {
    return this.children.flatMap(child => [child, ...child.descendants()]);
  }

  matches(selector) {
    return selector.split(',').some(part => matchesSimple(this, part.trim()));
  }

  /** Like the browser, returns a NodeList-shaped value without array methods such as `filter`. */
  querySelectorAll(selector) {
    return nodeListOf(this.descendants().filter(node => node.matches(selector)));
  }

  querySelector(selector) {
    return this.descendants().find(node => node.matches(selector)) ?? null;
  }

  closest(selector) {
    for (let candidate = this; candidate instanceof FakeElement; candidate = candidate.parent) {
      if (candidate.matches(selector)) {
        return candidate;
      }
    }
    return null;
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, new Set());
    }
    this.listeners.get(type).add(handler);
  }

  removeEventListener(type, handler) {
    this.listeners.get(type)?.delete(handler);
  }

  focus() { this.ownerDocument.activeElement = this; }

  click() { return this.dispatch('click'); }

  /** Dispatches a bubbling event and returns it so tests can read `defaultPrevented`. */
  dispatch(type, properties = {}) {
    const event = {
      type, target: this, defaultPrevented: false, propagationStopped: false, ...properties,
      preventDefault() { this.defaultPrevented = true; },
      stopPropagation() { this.propagationStopped = true; },
    };
    for (let node = this; node && !event.propagationStopped; node = node.parent) {
      for (const handler of [...(node.listeners.get(type) ?? [])]) {
        handler(event);
      }
    }
    return event;
  }

  set innerHTML(_) { throw new Error('Untrusted content must never use innerHTML'); }
}

function nodeListOf(nodes) {
  const nodeList = {
    length: nodes.length,
    item: index => nodes[index] ?? null,
    forEach: (callback, thisArgument) => nodes.forEach(callback, thisArgument),
    [Symbol.iterator]: () => nodes[Symbol.iterator](),
  };
  nodes.forEach((node, index) => { nodeList[index] = node; });
  return Object.freeze(nodeList);
}

function matchesSimple(element, selector) {
  const attribute = /^\[([\w-]+)(?:="([^"]*)")?\]$/.exec(selector);
  if (attribute) {
    const [, name, value] = attribute;
    return value === undefined ? element.hasAttribute(name) : element.getAttribute(name) === value;
  }
  if (selector.startsWith('#')) {
    return element.id === selector.slice(1);
  }
  return element.tagName === selector.toUpperCase();
}

/** Builds a document whose body tree is created with `h(tag, attributes, ...children)`. */
export function createFakeDocument() {
  const documentRoot = {
    activeElement: null,
    listeners: new Map(),
    getElementById(id) { return documentRoot.body.querySelector(`#${id}`); },
    querySelectorAll(selector) { return documentRoot.body.querySelectorAll(selector); },
    createElement(tagName) { return new FakeElement(documentRoot, tagName); },
    addEventListener(type, handler) { FakeElement.prototype.addEventListener.call(documentRoot, type, handler); },
    removeEventListener(type, handler) { FakeElement.prototype.removeEventListener.call(documentRoot, type, handler); },
  };
  documentRoot.body = new FakeElement(documentRoot, 'body');
  documentRoot.body.parent = documentRoot;
  const h = (tagName, attributes = {}, ...children) => {
    const element = new FakeElement(documentRoot, tagName, attributes);
    for (const child of children) {
      if (typeof child === 'string') {
        element.textContent += child;
      }
      else {
        element.appendChild(child);
      }
    }
    return element;
  };
  return { documentRoot, h };
}
