import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { describeScene, renderSurviveScene } from '../../main/resources/static/js/survive-scene.js';

const SVG_NS = 'http://www.w3.org/2000/svg';

const camp = {
  name: 'Alice', health: 8, status: 'EXPLORING', phase: 'DAY', day: 4, secondsToNextPhase: 420,
  shelters: 1, boats: 0, survivors: ['Alice', 'Bob'], events: ['Alice arrived at camp.'],
  actions: ['GATHER', 'GATHER_STONE', 'HUNT', 'REST'], enemy: null, enemyHealth: 0,
  weapon: null, weaponDurability: 0, guarding: false, revision: 3,
};

function sceneFixture() {
  function node(tagName) {
    return {
      tagName, attributes: new Map(), children: [], dataset: {}, textContent: '', parent: null,
      setAttribute(name, value) { this.attributes.set(name, String(value)); },
      getAttribute(name) { return this.attributes.get(name) ?? null; },
      appendChild(child) {
        child.parent = this;
        this.children.push(child);
        return child;
      },
      replaceChildren(...children) {
        for (const child of children) {
          child.parent = this;
        }
        this.children = children;
      },
      closest() {
        for (let candidate = this; candidate; candidate = candidate.parent) {
          if (candidate.dataset.action) {
            return candidate;
          }
        }
        return null;
      },
      set innerHTML(_) { throw new Error('Untrusted content must never use innerHTML'); },
    };
  }
  const documentRoot = {
    createElementNS(namespace, tagName) {
      assert.equal(namespace, SVG_NS);
      return node(tagName);
    },
  };
  return { documentRoot, svg: node('svg') };
}

function descendantsOf(root) {
  return root.children.flatMap(child => [child, ...descendantsOf(child)]);
}

function withClass(root, className) {
  return descendantsOf(root).filter(node => (node.getAttribute('class') ?? '').split(' ').includes(className));
}

function texts(root) {
  return descendantsOf(root).filter(node => node.tagName === 'text').map(node => node.textContent);
}

function hotspot(root, action) {
  return descendantsOf(root).find(node => node.dataset.action === action);
}

function isAvailable(node) {
  return (node.getAttribute('class') ?? '').split(' ').includes('is-available');
}

function render(state, pending = false) {
  const { documentRoot, svg } = sceneFixture();
  renderSurviveScene(documentRoot, svg, state, pending);
  return svg;
}

test('the sky follows the shared phase with the sun by day and stars and moon at night', () => {
  const day = render(camp);
  assert.equal(day.dataset.phase, 'DAY');
  assert.equal(withClass(day, 'scene-sun').length, 1);
  assert.equal(withClass(day, 'scene-star').length, 0);
  const night = render({ ...camp, phase: 'NIGHT' });
  assert.equal(night.dataset.phase, 'NIGHT');
  assert.equal(withClass(night, 'scene-moon').length, 1);
  assert.ok(withClass(night, 'scene-star').length > 10);
  assert.equal(render({ ...camp, phase: 'DUSK' }).dataset.phase, 'DUSK');
});

test('tents, boats and other survivors are drawn up to a cap with a count beyond it', () => {
  const crowded = render({
    ...camp, shelters: 8, boats: 5,
    survivors: ['Alice', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J'],
  });
  assert.equal(withClass(crowded, 'scene-tent').length, 6);
  assert.equal(withClass(crowded, 'scene-boat').length, 3);
  assert.equal(withClass(crowded, 'scene-survivor').length, 7);
  assert.equal(withClass(crowded, 'scene-player').length, 1);
  assert.deepEqual(texts(crowded).filter(text => text.startsWith('+')), ['+2', '+2', '+2']);
  const empty = render({ ...camp, shelters: 0, survivors: ['Alice'] });
  assert.equal(withClass(empty, 'scene-tent').length, 0);
  assert.equal(withClass(empty, 'scene-survivor').length, 0);
});

test('the player is named, armed and shown guarding, asleep, dead or escaped', () => {
  const armed = render({ ...camp, weapon: 'SPEAR', weaponDurability: 9 });
  assert.ok(texts(armed).includes('Alice'));
  assert.equal(withClass(armed, 'scene-weapon-spear').length, 1);
  assert.equal(withClass(render({ ...camp, phase: 'DUSK', guarding: true }), 'scene-torch').length, 1);
  const asleep = render({ ...camp, status: 'SLEEPING', phase: 'NIGHT', actions: ['WAKE'] });
  assert.equal(withClass(asleep, 'scene-player').length, 0);
  assert.ok(texts(asleep).includes('z z'));
  assert.equal(withClass(render({ ...camp, status: 'DEAD', health: 0, actions: [] }), 'scene-grave').length, 1);
  assert.equal(withClass(render({ ...camp, status: 'ESCAPED', actions: [] }), 'scene-escape-boat').length, 1);
});

test('an enemy appears in combat with a health bar scaled to that animal', () => {
  const fight = render({ ...camp, status: 'COMBAT', enemy: 'WOLF', enemyHealth: 4, actions: ['ATTACK', 'DEFEND', 'FLEE'] });
  assert.equal(withClass(fight, 'scene-enemy-wolf').length, 1);
  const [healthBar] = withClass(fight, 'scene-enemy-health');
  assert.equal(healthBar.getAttribute('width'), '50');
  assert.ok(isAvailable(hotspot(fight, 'ATTACK')));
  assert.equal(withClass(render(camp), 'scene-enemy').length, 0);
  assert.equal(hotspot(render(camp), 'ATTACK'), undefined);
});

test('a raid as the newest journal entry flashes the camp and burned structures smoke', () => {
  const burned = render({ ...camp, events: ['Night 3: raiders of strength 8 overran a defense of 2 and destroyed 1 shelter and 0 boats.'] });
  assert.equal(withClass(burned, 'scene-raid-flash').length, 1);
  assert.ok(withClass(burned, 'scene-smoke').length > 0);
  const repelled = render({ ...camp, events: ['Night 3: 2 survivors on guard repelled raiders of strength 8.'] });
  assert.equal(withClass(repelled, 'scene-raid-flash').length, 1);
  assert.equal(withClass(repelled, 'scene-smoke').length, 0);
  assert.equal(withClass(render({ ...camp, events: ['Night 3: raiders came.', 'Alice: You gathered one stone.'] }), 'scene-raid-flash').length, 0);
});

test('hotspots name their action and glow only when the server allows it', () => {
  const day = render(camp);
  assert.ok(isAvailable(hotspot(day, 'GATHER')));
  assert.ok(isAvailable(hotspot(day, 'GATHER_STONE')));
  assert.ok(isAvailable(hotspot(day, 'HUNT')));
  assert.ok(isAvailable(hotspot(day, 'REST')));
  assert.equal(isAvailable(hotspot(day, 'SLEEP')), false);
  assert.equal(isAvailable(hotspot(day, 'ESCAPE')), false);
  const dusk = render({ ...camp, phase: 'DUSK', actions: ['GATHER', 'GUARD', 'SLEEP'] });
  assert.ok(isAvailable(hotspot(dusk, 'GUARD')));
  assert.equal(hotspot(dusk, 'REST'), undefined);
  assert.ok(isAvailable(hotspot(dusk, 'SLEEP')));
  const asleep = render({ ...camp, status: 'SLEEPING', phase: 'NIGHT', actions: ['WAKE'] });
  assert.ok(isAvailable(hotspot(asleep, 'WAKE')));
  assert.equal(isAvailable(hotspot(asleep, 'GATHER')), false);
  const [hotspotLayer] = withClass(day, 'scene-layer-hotspots');
  assert.equal(hotspotLayer.getAttribute('aria-hidden'), 'true');
});

test('a pending request marks the scene inert', () => {
  assert.match(render(camp, true).getAttribute('class'), /\bis-pending\b/);
  assert.doesNotMatch(render(camp, false).getAttribute('class'), /\bis-pending\b/);
});

test('unchanged layers are kept between renders so animations do not restart', () => {
  const { documentRoot, svg } = sceneFixture();
  renderSurviveScene(documentRoot, svg, camp);
  const [firstFire] = withClass(svg, 'scene-fire');
  const [firstSun] = withClass(svg, 'scene-sun');
  renderSurviveScene(documentRoot, svg, { ...camp, secondsToNextPhase: 300, revision: 4 });
  assert.equal(withClass(svg, 'scene-fire')[0], firstFire);
  assert.notEqual(withClass(svg, 'scene-sun')[0], firstSun);
  renderSurviveScene(documentRoot, svg, { ...camp, shelters: 2 });
  assert.equal(withClass(svg, 'scene-tent').length, 2);
});

test('hostile names render as text and the description reads the scene aloud', () => {
  const hostile = '<img src=x onerror=alert(1)>';
  const svg = render({ ...camp, name: hostile, survivors: [hostile] });
  assert.ok(texts(svg).includes(hostile));
  assert.equal(svg.getAttribute('aria-label'), describeScene({ ...camp, name: hostile, survivors: [hostile] }));
});

test('the description names the phase, camp and what the player is doing', () => {
  assert.equal(describeScene(camp),
    'Day, day 4. 1 shelter, 0 boats, 2 survivors at camp. You are at camp.');
  assert.equal(describeScene({ ...camp, phase: 'NIGHT', status: 'COMBAT', enemy: 'BEAR', enemyHealth: 9, weapon: 'STONE_AXE' }),
    'Night, day 4. 1 shelter, 0 boats, 2 survivors at camp. A bear with 9 health blocks your path. You hold a stone axe.');
  assert.match(describeScene({ ...camp, status: 'SLEEPING' }), /You are asleep in a shelter\.$/);
  assert.match(describeScene({ ...camp, guarding: true, phase: 'DUSK' }), /^Dusk, .*You stand guard by the fire\.$/);
  assert.match(describeScene({ ...camp, status: 'DEAD' }), /You have died\.$/);
});

test('unknown values from a newer server draw neutral fallbacks instead of failing', () => {
  const svg = render({ ...camp, phase: 'ECLIPSE', status: 'COMBAT', enemy: 'DRAGON', enemyHealth: 30, weapon: 'BOW' });
  assert.equal(svg.dataset.phase, 'DAY');
  assert.equal(withClass(svg, 'scene-enemy').length, 1);
  assert.match(describeScene({ ...camp, status: 'COMBAT', enemy: 'DRAGON', enemyHealth: 30 }), /A wild animal with 30 health/);
});

test('the page exposes the scene as one labelled image above the move buttons', () => {
  const html = readFileSync(new URL('../../main/resources/templates/survive.html', import.meta.url), 'utf8');
  assert.match(html, /<svg id="surviveCampScene" class="survive-scene" role="img" aria-label="[^"]+"/);
  assert.ok(html.indexOf('id="surviveCampScene"') < html.indexOf('id="surviveActions"'));
});
