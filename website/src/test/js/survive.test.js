import assert from 'node:assert/strict';
import test from 'node:test';
import { disposeSurviveOnPageHide, formatCountdown, mountSurvive, renderSurviveState } from '../../main/resources/static/js/survive.js';

const snapshot = {
  name: '<img src=x onerror=alert(1)>', health: 8, strength: 3, stamina: 11,
  experience: 1, experienceToNextLevel: 2, wood: 4, food: 1, inventoryCapacity: 10,
  enemyHealth: 0, status: 'EXPLORING', revision: 7, worldRevision: 10,
  shelters: 1, boats: 0, survivors: ['Alice', 'Bob'], events: ['Earlier', '<script>bad</script>'],
  actions: ['GATHER', 'HUNT', 'EAT', 'REST'], message: 'You gathered one wood.',
  survivorId: 'alice-public-id', recipients: [{ survivorId: 'bob-public-id', name: '<Bob>' }],
  phase: 'DUSK', day: 3, secondsToNextPhase: 125, hunger: 6, stone: 2, weapon: 'SPEAR', weaponDurability: 9,
  enemy: null, bedsInUse: 1, beds: 2, guarding: false,
};

test('back/forward cache preserves mounted controls and final navigation releases them', () => {
  let disposalCount = 0;
  const mounted = { dispose() { disposalCount++; } };
  disposeSurviveOnPageHide(mounted, { persisted: true });
  assert.equal(disposalCount, 0);
  disposeSurviveOnPageHide(mounted, { persisted: false });
  assert.equal(disposalCount, 1);
});

function documentFixture() {
  const elements = new Map();
  let buttons = [];
  function element(id = '') {
    return {
      id, textContent: '', hidden: false, disabled: false, value: 'Chris', children: [],
      listeners: new Map(), dataset: {}, attributes: new Map(), focus() {}, contains() { return false; },
      setAttribute(name, value) { this.attributes.set(name, String(value)); },
      getAttribute(name) { return this.attributes.get(name) ?? null; },
      appendChild(child) { child.parent = this; this.children.push(child); return child; },
      closest() {
        for (let candidate = this; candidate; candidate = candidate.parent) {
          if (candidate.dataset.action) {
            return candidate;
          }
        }
        return null;
      },
      set innerHTML(_) { throw new Error('Untrusted content must never use innerHTML'); },
      addEventListener(name, handler) { this.listeners.set(name, handler); },
      removeEventListener(name) { this.listeners.delete(name); },
      replaceChildren(...children) {
        for (const child of children) {
          child.parent = this;
        }
        this.children = children;
      },
      querySelector() { return element(); },
      querySelectorAll(selector) {
        if (id === 'surviveActionBar' && selector === '[data-action]') {
          return buttons;
        }
        return [];
      },
    };
  }
  buttons = ['GATHER', 'GATHER_STONE', 'BUILD_BOAT', 'HUNT', 'ATTACK', 'DEFEND', 'FLEE', 'WAKE'].map(action => {
    const button = element();
    button.dataset.action = action;
    button.closest = () => button;
    return button;
  });
  const getElementById = id => {
    if (!elements.has(id)) {
      elements.set(id, element(id));
    }
    return elements.get(id);
  };
  return {
    hidden: false, buttons,
    getElementById,
    addEventListener() {},
    removeEventListener() {},
    querySelectorAll() { return buttons; },
    createElement() { return element(); },
    createElementNS() { return element(); },
  };
}

test('giving targets a public identity, retains the selected recipient and uses text nodes', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  const mounted = mountSurvive(documentRoot, async (url, options) => {
    requests.push({ url, options });
    return snapshot;
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    const recipient = documentRoot.getElementById('surviveRecipient');
    assert.equal(recipient.children[1].value, 'bob-public-id');
    assert.match(recipient.children[1].textContent, /<Bob>/);
    recipient.value = 'bob-public-id';
    documentRoot.getElementById('surviveGiftResource').value = 'WOOD';
    documentRoot.getElementById('surviveGiftQuantity').value = '2';
    renderSurviveState(documentRoot, snapshot);
    assert.equal(recipient.value, 'bob-public-id');
    assert.equal(documentRoot.getElementById('surviveGiftQuantity').value, '2');
    documentRoot.getElementById('surviveGift').listeners.get('submit')({ preventDefault() {} });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requests[1].url, '/api/survive/v1/gifts');
    assert.deepEqual(JSON.parse(requests[1].options.body), {
      recipientId: 'bob-public-id', resource: 'WOOD', quantity: 2, revision: 7,
    });
    renderSurviveState(documentRoot, { ...snapshot, recipients: [] });
    assert.equal(documentRoot.getElementById('surviveGive').disabled, true);
  } finally { mounted.dispose(); }
});

test('background reads preserve gift input editing and unchanged recipient options', async () => {
  const documentRoot = documentFixture();
  let completeRead;
  let reads = 0;
  const mounted = mountSurvive(documentRoot, async () => {
    if (++reads === 1) {
      return snapshot;
    }
    return new Promise(resolve => { completeRead = resolve; });
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    const recipient = documentRoot.getElementById('surviveRecipient');
    const option = recipient.children[0];
    const amount = documentRoot.getElementById('surviveGiftQuantity');
    amount.value = '3';
    mounted.refresh();
    assert.equal(amount.disabled, false);
    assert.equal(recipient.disabled, false);
    assert.equal(documentRoot.getElementById('surviveGive').disabled, true);
    completeRead(snapshot);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(amount.value, '3');
    assert.equal(recipient.children[0], option);
  } finally { mounted.dispose(); }
});

test('a departing selected survivor never silently retargets the gift', () => {
  const documentRoot = documentFixture();
  renderSurviveState(documentRoot, snapshot);
  documentRoot.getElementById('surviveRecipient').value = 'bob-public-id';
  documentRoot.getElementById('surviveGiftQuantity').value = '2';
  documentRoot.getElementById('surviveGiftResource').value = 'FOOD';
  const remainingCamp = { ...snapshot, recipients: [{ survivorId: 'charlie-public-id', name: 'Charlie' }] };
  renderSurviveState(documentRoot, remainingCamp);
  assert.equal(documentRoot.getElementById('surviveRecipient').value, '');
  assert.equal(documentRoot.getElementById('surviveGive').disabled, true);
  renderSurviveState(documentRoot, remainingCamp);
  assert.equal(documentRoot.getElementById('surviveRecipient').value, '');
  assert.equal(documentRoot.getElementById('surviveGiftQuantity').value, '2');
  assert.equal(documentRoot.getElementById('surviveGiftResource').value, 'FOOD');
});

test('renders survivor and shared camp using text nodes and server allowed actions', () => {
  const documentRoot = documentFixture();
  renderSurviveState(documentRoot, snapshot);
  assert.equal(documentRoot.getElementById('survivePlayerName').textContent, snapshot.name);
  assert.equal(documentRoot.getElementById('surviveHealth').value, 8);
  assert.equal(documentRoot.getElementById('surviveInventoryCount').textContent, '7 / 10');
  assert.equal(documentRoot.getElementById('surviveShelters').textContent, '1');
  assert.equal(documentRoot.getElementById('surviveEvents').children[0].textContent, '<script>bad</script>');
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'BUILD_BOAT').disabled, true);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'GATHER').disabled, false);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'ATTACK').hidden, true);
  renderSurviveState(documentRoot, { ...snapshot, status: 'COMBAT', enemy: 'WOLF', enemyHealth: 8, actions: ['ATTACK'] });
  assert.equal(documentRoot.getElementById('surviveEnemy').hidden, false);
  assert.equal(documentRoot.getElementById('surviveEnemyName').textContent, 'Grey wolf');
  assert.equal(documentRoot.getElementById('surviveEnemyHealth').textContent, '8 health');
  assert.equal(documentRoot.getElementById('surviveScene').textContent, 'A wolf circles you');
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'ATTACK').disabled, false);
  renderSurviveState(documentRoot, { ...snapshot, status: 'DEAD', actions: [] });
  assert.ok(documentRoot.buttons.every(button => button.disabled));
});

test('serializes actions with the server revision and reconciles failure without replay', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  let rejectAction;
  const requestJson = async (url, options) => {
    requests.push({ url, options });
    if (options.method === 'POST') {
      return new Promise((_, reject) => { rejectAction = reject; });
    }
    return snapshot;
  };
  const mounted = mountSurvive(documentRoot, requestJson);
  try {
    await new Promise(resolve => setImmediate(resolve));
    const gather = documentRoot.buttons[0];
    const click = documentRoot.getElementById('surviveActionBar').listeners.get('click');
    click({ target: gather });
    click({ target: gather });
    assert.equal(requests.filter(request => request.options.method === 'POST').length, 1);
    assert.deepEqual(JSON.parse(requests[1].options.body), { action: 'GATHER', revision: 7 });
    assert.equal(documentRoot.getElementById('surviveActionBar').getAttribute('aria-busy'), 'true');
    rejectAction(new Error('Lost response'));
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requests.length, 3);
    assert.equal(requests[2].url, '/api/survive/v1/game');
    assert.equal(documentRoot.getElementById('surviveError').hidden, false);
    assert.match(documentRoot.getElementById('surviveError').textContent, /Lost response/);
    assert.equal(gather.disabled, false);
  } finally { mounted.dispose(); }
});

test('no survivor shows join form and joining sends only the chosen name', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  const mounted = mountSurvive(documentRoot, async (url, options) => {
    requests.push({ url, options });
    return options.method === 'POST' ? snapshot : {};
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(documentRoot.getElementById('surviveJoin').hidden, false);
    assert.equal(documentRoot.getElementById('surviveGame').hidden, true);
    documentRoot.getElementById('surviveName').value = ' Alice ';
    documentRoot.getElementById('surviveJoin').listeners.get('submit')({ preventDefault() {} });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(JSON.parse(requests[1].options.body), { name: 'Alice' });
    assert.equal(documentRoot.getElementById('surviveJoin').hidden, true);
  } finally { mounted.dispose(); }
});


test('saved live survivors show durable ownership and only terminal characters can restart', () => {
  const documentRoot = documentFixture();
  renderSurviveState(documentRoot, { ...snapshot, saved: true });
  assert.match(documentRoot.getElementById('surviveSaveStatus').textContent, /Saved to your account/);
  assert.equal(documentRoot.getElementById('surviveRestart').disabled, true);
  renderSurviveState(documentRoot, { ...snapshot, saved: true, status: 'DEAD', actions: [] });
  assert.equal(documentRoot.getElementById('surviveRestart').disabled, false);
  renderSurviveState(documentRoot, { ...snapshot, saved: false });
  assert.match(documentRoot.getElementById('surviveSaveStatus').textContent, /Temporary guest/);
  assert.equal(documentRoot.getElementById('surviveRestart').disabled, false);
});

test('shows the shared clock, hunger, stone, weapon and beds from the server snapshot', () => {
  const documentRoot = documentFixture();
  renderSurviveState(documentRoot, snapshot);
  assert.equal(documentRoot.getElementById('surviveClock').dataset.phase, 'DUSK');
  assert.equal(documentRoot.getElementById('survivePhase').textContent, 'Dusk');
  assert.equal(documentRoot.getElementById('surviveDay').textContent, 'Day 3');
  assert.equal(documentRoot.getElementById('surviveNextPhase').textContent, 'Nightfall in');
  assert.equal(documentRoot.getElementById('surviveCountdown').textContent, '2:05');
  assert.equal(documentRoot.getElementById('surviveHungerText').textContent, '6 / 10');
  assert.equal(documentRoot.getElementById('surviveHungerWarning').hidden, true);
  assert.equal(documentRoot.getElementById('surviveStone').textContent, '2');
  assert.equal(documentRoot.getElementById('surviveWeapon').textContent, 'Spear · 9 uses left');
  assert.equal(documentRoot.getElementById('surviveBeds').textContent, '1 / 2');
  renderSurviveState(documentRoot, { ...snapshot, hunger: 3, weapon: null, weaponDurability: 0, guarding: true });
  assert.equal(documentRoot.getElementById('surviveHungerWarning').hidden, false);
  assert.equal(documentRoot.getElementById('surviveWeapon').textContent, 'Bare hands');
  assert.equal(documentRoot.getElementById('surviveStatus').textContent, 'On guard');
});

test('a sleeping survivor sees only the wake control', () => {
  const documentRoot = documentFixture();
  renderSurviveState(documentRoot, { ...snapshot, status: 'SLEEPING', actions: ['WAKE'] });
  const shown = documentRoot.buttons.filter(button => !button.hidden).map(button => button.dataset.action);
  assert.deepEqual(shown, ['WAKE']);
  assert.equal(documentRoot.getElementById('surviveStatus').textContent, 'Asleep');
  renderSurviveState(documentRoot, snapshot);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'WAKE').hidden, true);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'GATHER_STONE').hidden, true);
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'GATHER').hidden, false);
});

test('the action bar shows menus while exploring and only situation moves otherwise', () => {
  const documentRoot = documentFixture();
  const menus = documentRoot.getElementById('surviveMenus');
  const startOver = documentRoot.getElementById('surviveStartOver');
  renderSurviveState(documentRoot, snapshot);
  assert.equal(menus.hidden, false);
  assert.equal(startOver.hidden, true);
  renderSurviveState(documentRoot, { ...snapshot, status: 'COMBAT', enemy: 'HOG', enemyHealth: 5, actions: ['ATTACK', 'DEFEND', 'FLEE'] });
  assert.equal(menus.hidden, true);
  const shown = documentRoot.buttons.filter(button => !button.hidden).map(button => button.dataset.action);
  assert.deepEqual(shown, ['ATTACK', 'DEFEND', 'FLEE']);
  renderSurviveState(documentRoot, { ...snapshot, status: 'DEAD', health: 0, actions: [] });
  assert.equal(menus.hidden, true);
  assert.equal(startOver.hidden, false);
  renderSurviveState(documentRoot, { ...snapshot, status: 'ESCAPED', actions: [] }, true);
  assert.equal(startOver.hidden, false);
  assert.equal(startOver.disabled, true);
});

test('a pending request marks the action bar busy without disabling the focused move', () => {
  const documentRoot = documentFixture();
  renderSurviveState(documentRoot, snapshot, true);
  assert.equal(documentRoot.getElementById('surviveActionBar').getAttribute('aria-busy'), 'true');
  assert.equal(documentRoot.buttons.find(button => button.dataset.action === 'GATHER').disabled, false);
  renderSurviveState(documentRoot, snapshot, false);
  assert.equal(documentRoot.getElementById('surviveActionBar').getAttribute('aria-busy'), 'false');
});

test('countdowns read as minutes and padded seconds and never go negative', () => {
  assert.equal(formatCountdown(0), '0:00');
  assert.equal(formatCountdown(59), '0:59');
  assert.equal(formatCountdown(840), '14:00');
  assert.equal(formatCountdown(-3), '0:00');
});

function sceneHotspot(documentRoot, action) {
  const scene = documentRoot.getElementById('surviveCampScene');
  const descendants = node => node.children.flatMap(child => [child, ...descendants(child)]);
  return descendants(scene).find(node => node.dataset.action === action);
}

test('clicking an allowed scene hotspot sends that action once with the current revision', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  let finishAction;
  const mounted = mountSurvive(documentRoot, async (url, options) => {
    requests.push({ url, options });
    if (options.method === 'POST') {
      return new Promise(resolve => { finishAction = resolve; });
    }
    return snapshot;
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    const sceneClick = documentRoot.getElementById('surviveCampScene').listeners.get('click');
    const trees = sceneHotspot(documentRoot, 'GATHER');
    assert.match(trees.getAttribute('class'), /is-available/);
    sceneClick({ target: trees });
    sceneClick({ target: trees });
    const posts = requests.filter(request => request.options.method === 'POST');
    assert.equal(posts.length, 1);
    assert.deepEqual(JSON.parse(posts[0].options.body), { action: 'GATHER', revision: 7 });
    finishAction(snapshot);
  } finally { mounted.dispose(); }
});

test('scene hotspots the server does not allow send nothing', async () => {
  const documentRoot = documentFixture();
  const requests = [];
  const mounted = mountSurvive(documentRoot, async (url, options) => {
    requests.push({ url, options });
    return snapshot;
  });
  try {
    await new Promise(resolve => setImmediate(resolve));
    const sceneClick = documentRoot.getElementById('surviveCampScene').listeners.get('click');
    const boats = sceneHotspot(documentRoot, 'ESCAPE');
    assert.doesNotMatch(boats.getAttribute('class'), /is-available/);
    sceneClick({ target: boats });
    sceneClick({ target: documentRoot.getElementById('surviveCampScene') });
    assert.equal(requests.filter(request => request.options.method === 'POST').length, 0);
  } finally { mounted.dispose(); }
  assert.equal(documentRoot.getElementById('surviveCampScene').listeners.has('click'), false);
});

test('the status card shows inventory, weapon, stats and the three newest events', () => {
  const documentRoot = documentFixture();
  const events = ['First', 'Second', 'Third', 'Fourth', '<b>Newest</b>'];
  renderSurviveState(documentRoot, { ...snapshot, events });
  const recent = documentRoot.getElementById('surviveRecentEvents').children.map(item => item.textContent);
  assert.deepEqual(recent, ['<b>Newest</b>', 'Fourth', 'Third']);
  assert.equal(documentRoot.getElementById('surviveEvents').children.length, 5);
  assert.equal(documentRoot.getElementById('surviveWood').textContent, '4');
  assert.equal(documentRoot.getElementById('surviveStone').textContent, '2');
  assert.equal(documentRoot.getElementById('surviveInventoryCount').textContent, '7 / 10');
  assert.equal(documentRoot.getElementById('surviveWeapon').textContent, 'Spear · 9 uses left');
  assert.equal(documentRoot.getElementById('surviveCampSummary').textContent, '1 shelter · 0 boats · 2 survivors');
});

test('rendering never opens or closes the side panel sections', () => {
  const documentRoot = documentFixture();
  const camp = documentRoot.getElementById('surviveSectionCamp');
  const journal = documentRoot.getElementById('surviveSectionJournal');
  camp.open = true;
  journal.open = false;
  renderSurviveState(documentRoot, snapshot);
  renderSurviveState(documentRoot, { ...snapshot, worldRevision: 11, events: [...snapshot.events, 'Later'] });
  assert.equal(camp.open, true);
  assert.equal(journal.open, false);
  assert.equal(documentRoot.getElementById('surviveJournalUnread').hidden, false);
});
