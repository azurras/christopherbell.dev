import assert from 'node:assert/strict';
import test from 'node:test';
import { createIslandMaterials } from '../../main/resources/static/js/survive-world-materials.js';

test('the authored atlas includes face and gear paint and preserves the separate actor atlas', () => {
  const images = [];
  const uploads = [];
  let textureUnit;
  const gl = new Proxy({
    TEXTURE0: 100,
    createTexture: () => ({}),
    activeTexture(unit) { textureUnit = unit - this.TEXTURE0; },
    texImage2D(...args) {
      const source = args.at(-1);
      if (source.operations) {
        uploads.push({ textureUnit, width: source.width, height: source.height, operations: [...source.operations] });
      }
    },
    isContextLost: () => false,
  }, { get: (target, key) => target[key] ?? (() => {}) });
  const documentRoot = {
    defaultView: { Image: class { constructor() { images.push(this); } }, Path2D: class {} },
    createElement() {
      const operations = [];
      return { operations, getContext: () => ({
        drawImage: image => operations.push(image.src),
        createRadialGradient: () => ({ addColorStop() {} }),
        fillRect(x, y, width, height) {
          operations.push(x === 508 ? { x, y, width, height, color: this.fillStyle } : 'skin shading');
        },
        fill: () => operations.push('face paint'),
      }) };
    },
  };
  const materials = createIslandMaterials(gl, documentRoot, {});
  try {
    assert.equal(materials.authoredActorsReady, false);
    images.find(image => image.src.endsWith('/foliage-atlas.png')).onload();
    images.find(image => image.src.endsWith('/actor-materials.jpg')).onload();
    images.find(image => image.src.endsWith('/authored-actors.jpg')).onload();
    assert.deepEqual(uploads[0], {
      textureUnit: 4, width: 512, height: 256, operations: ['/images/survive/foliage-atlas.png'],
    });
    assert.deepEqual(uploads[1], {
      textureUnit: 5, width: 1024, height: 1024, operations: ['/images/survive/actor-materials.jpg'],
    });
    assert.equal(uploads[2].textureUnit, 6);
    assert.equal(uploads[2].width, 1024);
    assert.equal(uploads[2].operations[0], '/images/survive/authored-actors.jpg');
    assert.equal(uploads[2].operations[1], 'skin shading');
    assert.ok(uploads[2].operations.slice(1).includes('face paint'));
    assert.deepEqual(uploads[2].operations.at(-1), { x: 508, y: 256, width: 8, height: 18, color: '#746046' });
    assert.equal(materials.authoredActorsReady, true);
  } finally { materials.dispose(); }
});
