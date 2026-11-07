import assert from 'node:assert/strict';
import test from 'node:test';
import { createIslandMaterials } from '../../main/resources/static/js/survive-world-materials.js';

test('the authored face is painted before uploading the existing shared actor atlas', () => {
  const images = [];
  const uploads = [];
  let textureUnit;
  const gl = new Proxy({
    TEXTURE0: 100,
    createTexture: () => ({}),
    activeTexture(unit) { textureUnit = unit - this.TEXTURE0; },
    texImage2D(...args) {
      const source = args.at(-1);
      if (source.operations) uploads.push({ textureUnit, width: source.width, operations: [...source.operations] });
    },
    isContextLost: () => false,
  }, { get: (target, key) => target[key] ?? (() => {}) });
  const documentRoot = {
    defaultView: { Image: class { constructor() { images.push(this); } }, Path2D: class {} },
    createElement() {
      const operations = [];
      return { operations, getContext: () => ({
        drawImage: image => operations.push(image.src),
        fill: () => operations.push('face paint'),
      }) };
    },
  };
  const materials = createIslandMaterials(gl, documentRoot, {});
  try {
    assert.equal(materials.authoredActorsReady, false);
    images.find(image => image.src.endsWith('/actor-materials.jpg')).onload();
    images.find(image => image.src.endsWith('/authored-actors.jpg')).onload();
    assert.deepEqual(uploads[0], {
      textureUnit: 5, width: 1024, operations: ['/images/survive/actor-materials.jpg'],
    });
    assert.equal(uploads[1].textureUnit, 6);
    assert.equal(uploads[1].width, 1024);
    assert.equal(uploads[1].operations[0], '/images/survive/authored-actors.jpg');
    assert.ok(uploads[1].operations.slice(1).includes('face paint'));
    assert.equal(materials.authoredActorsReady, true);
  } finally { materials.dispose(); }
});
