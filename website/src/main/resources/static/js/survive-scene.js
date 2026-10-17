/**
 * Draws the Survive camp as an SVG scene from the server snapshot. Display and transport only:
 * the Java world owns every rule, and hotspots carry the same actions as the buttons.
 */
const SVG_NS = 'http://www.w3.org/2000/svg';
const PHASES = ['DAY', 'DUSK', 'NIGHT'];
const PHASE_NAMES = { DAY: 'Day', DUSK: 'Dusk', NIGHT: 'Night' };
/** Phase lengths in seconds, used only to place the sun or moon along its arc. */
const PHASE_SECONDS = { DAY: 840, DUSK: 240, NIGHT: 360 };
/** Full health of each animal, used only to scale the health bar. */
const ENEMY_FULL_HEALTH = { HOG: 5, WOLF: 8, BEAR: 14 };
const ENEMY_NAMES = { HOG: 'hog', WOLF: 'wolf', BEAR: 'bear' };
const WEAPON_NAMES = { CLUB: 'club', STONE_AXE: 'stone axe', SPEAR: 'spear' };
const MAX_TENTS = 6;
const MAX_BOATS = 3;
const MAX_OTHER_SURVIVORS = 7;
const HEALTH_BAR_WIDTH = 100;
const LAYER_NAMES = ['sky', 'celestial', 'land', 'camp', 'survivors', 'enemy', 'effects', 'hotspots'];

const TENT_SLOTS = [[430, 330, 1], [535, 330, 1], [640, 330, 1], [480, 285, 0.8], [585, 285, 0.8], [690, 285, 0.8]];
const BOAT_SLOTS = [[800, 455], [880, 480], [820, 510]];
const SURVIVOR_SLOTS = [[470, 468], [440, 505], [502, 510], [728, 505], [760, 468], [410, 470], [700, 525]];
const PLAYER_SLOT = [680, 468];
const PLAYER_COMBAT_SLOT = [480, 470];
const PINES = [
  [30, 330, 130], [85, 345, 150], [140, 325, 120], [195, 350, 160], [250, 335, 135], [300, 345, 125],
  [55, 380, 120], [115, 390, 135], [170, 385, 125], [225, 395, 140], [280, 388, 115],
];
const ROCKS = [
  'M50 505 L80 455 L130 445 L160 480 L150 510 Z',
  'M140 512 L170 470 L215 462 L250 490 L240 515 Z',
  'M225 518 L250 488 L285 486 L300 510 Z',
];
const STARS = Array.from({ length: 28 }, (_, index) => [(index * 137 + 41) % 940 + 10, (index * 53 + 17) % 200 + 10]);

/**
 * Hit areas whose smaller side is at least 145 scene units: 44 CSS pixels when the scene is 300 pixels
 * wide, as it is inside the adventure panel on a 375-pixel-wide phone.
 */
const HOTSPOTS = {
  trees: { x: 0, y: 160, width: 318, height: 235 },
  rocks: { x: 30, y: 395, width: 280, height: 145 },
  forestEdge: { x: 318, y: 222, width: 145, height: 145 },
  tents: { x: 420, y: 200, width: 350, height: 145 },
  fire: { x: 518, y: 395, width: 145, height: 145 },
  boats: { x: 780, y: 395, width: 180, height: 145 },
  enemy: { x: 318, y: 375, width: 145, height: 160 },
};

const sceneMemory = new WeakMap();

/** Redraws the layers whose inputs changed since the last render of this SVG. */
export function renderSurviveScene(documentRoot, svgRoot, state, pending = false) {
  const memory = memoryFor(documentRoot, svgRoot);
  const draw = drawingKit(documentRoot);
  svgRoot.dataset.phase = phaseOf(state);
  svgRoot.setAttribute('aria-label', describeScene(state));
  svgRoot.setAttribute('class', pending ? 'survive-scene is-pending' : 'survive-scene');
  const layerViews = {
    sky: { phase: phaseOf(state) },
    celestial: celestialView(state),
    land: {},
    camp: campView(state),
    survivors: survivorsView(state),
    enemy: enemyView(state),
    effects: effectsView(state),
    hotspots: hotspotsView(state),
  };
  for (const name of LAYER_NAMES) {
    const signature = JSON.stringify(layerViews[name]);
    if (memory.signatures.get(name) !== signature) {
      memory.signatures.set(name, signature);
      memory.layers.get(name).replaceChildren(...LAYER_DRAWERS[name](draw, layerViews[name]));
    }
  }
}

/** A sentence-by-sentence account of the scene for screen readers. */
export function describeScene(state) {
  const sentences = [
    `${PHASE_NAMES[phaseOf(state)]}, day ${state.day}.`,
    `${countOf(state.shelters, 'shelter')}, ${countOf(state.boats, 'boat')}, `
      + `${countOf(state.survivors.length, 'survivor')} at camp.`,
    playerSentence(state),
  ];
  if (state.weapon) {
    sentences.push(`You hold a ${WEAPON_NAMES[state.weapon] ?? 'weapon'}.`);
  }
  return sentences.join(' ');
}

function playerSentence(state) {
  switch (state.status) {
    case 'COMBAT': {
      const enemyName = ENEMY_NAMES[state.enemy] ? `A ${ENEMY_NAMES[state.enemy]}` : 'A wild animal';
      return `${enemyName} with ${state.enemyHealth} health blocks your path.`;
    }
    case 'SLEEPING':
      return 'You are asleep in a shelter.';
    case 'DEAD':
      return 'You have died.';
    case 'ESCAPED':
      return 'You sailed away from the island.';
    default:
      return state.guarding ? 'You stand guard by the fire.' : 'You are at camp.';
  }
}

function countOf(count, singular) {
  return `${count} ${count === 1 ? singular : `${singular}s`}`;
}

function phaseOf(state) {
  return PHASES.includes(state.phase) ? state.phase : 'DAY';
}

function memoryFor(documentRoot, svgRoot) {
  if (!sceneMemory.has(svgRoot)) {
    const layers = new Map(LAYER_NAMES.map(name => {
      const layer = documentRoot.createElementNS(SVG_NS, 'g');
      layer.setAttribute('class', `scene-layer scene-layer-${name}`);
      svgRoot.appendChild(layer);
      return [name, layer];
    }));
    layers.get('hotspots').setAttribute('aria-hidden', 'true');
    svgRoot.setAttribute('viewBox', '0 0 960 540');
    sceneMemory.set(svgRoot, { layers, signatures: new Map() });
  }
  return sceneMemory.get(svgRoot);
}

/** Element and text factories bound to one document; values only ever reach attributes and text nodes. */
function drawingKit(documentRoot) {
  const shape = (tagName, attributes = {}, ...children) => {
    const element = documentRoot.createElementNS(SVG_NS, tagName);
    for (const [name, value] of Object.entries(attributes)) {
      element.setAttribute(name, String(value));
    }
    for (const child of children) {
      element.appendChild(child);
    }
    return element;
  };
  const label = (text, x, y, className) => {
    const element = shape('text', { x, y, class: className, 'text-anchor': 'middle' });
    element.textContent = text;
    return element;
  };
  return { shape, label };
}

function celestialView(state) {
  const phase = phaseOf(state);
  const phaseSeconds = PHASE_SECONDS[phase];
  const elapsedFraction = 1 - Math.min(phaseSeconds, Math.max(0, state.secondsToNextPhase)) / phaseSeconds;
  return { phase, progress: Math.round(elapsedFraction * 100) / 100 };
}

function campView(state) {
  return {
    tents: Math.min(state.shelters, MAX_TENTS),
    extraTents: Math.max(0, state.shelters - MAX_TENTS),
    boats: Math.min(state.boats, MAX_BOATS),
    extraBoats: Math.max(0, state.boats - MAX_BOATS),
    night: phaseOf(state) !== 'DAY',
  };
}

function survivorsView(state) {
  const others = Math.max(0, state.survivors.length - 1);
  return {
    others: Math.min(others, MAX_OTHER_SURVIVORS),
    extraOthers: Math.max(0, others - MAX_OTHER_SURVIVORS),
    name: state.name,
    status: state.status,
    guarding: state.status === 'EXPLORING' && state.guarding,
    weapon: state.weapon ?? null,
  };
}

function enemyView(state) {
  if (state.status !== 'COMBAT') {
    return null;
  }
  const fullHealth = Math.max(ENEMY_FULL_HEALTH[state.enemy] ?? state.enemyHealth, state.enemyHealth, 1);
  return { kind: state.enemy, healthWidth: Math.round(HEALTH_BAR_WIDTH * state.enemyHealth / fullHealth) };
}

function effectsView(state) {
  const newestEvent = state.events.at(-1) ?? '';
  const isRaid = newestEvent.includes('raiders');
  return { raid: isRaid ? newestEvent : null, burned: isRaid && newestEvent.includes('destroyed') };
}

function hotspotsView(state) {
  const fireAction = state.actions.includes('GUARD') ? 'GUARD' : 'REST';
  const tentAction = state.status === 'SLEEPING' ? 'WAKE' : 'SLEEP';
  const placements = [
    ['GATHER', HOTSPOTS.trees], ['GATHER_STONE', HOTSPOTS.rocks], ['HUNT', HOTSPOTS.forestEdge],
    [tentAction, HOTSPOTS.tents], [fireAction, HOTSPOTS.fire], ['ESCAPE', HOTSPOTS.boats],
  ];
  if (state.status === 'COMBAT') {
    placements.push(['ATTACK', HOTSPOTS.enemy]);
  }
  return placements.map(([action, area]) => ({ action, area, available: state.actions.includes(action) }));
}

function drawSky({ shape }, view) {
  const shapes = [
    shape('defs', {},
      shape('linearGradient', { id: 'surviveSkyGradient', x1: 0, y1: 0, x2: 0, y2: 1 },
        shape('stop', { offset: '0%', class: 'scene-sky-top' }),
        shape('stop', { offset: '100%', class: 'scene-sky-bottom' }))),
    shape('rect', { x: 0, y: 0, width: 960, height: 540, fill: 'url(#surviveSkyGradient)', class: 'scene-sky' }),
  ];
  if (view.phase === 'NIGHT') {
    STARS.forEach(([x, y], index) => {
      shapes.push(shape('circle', { cx: x, cy: y, r: 1.6, class: 'scene-star', style: `animation-delay: ${(index % 6) * 0.7}s` }));
    });
  }
  shapes.push(shape('path', { d: 'M0 330 Q160 250 330 300 T640 280 T960 300 L960 540 L0 540 Z', class: 'scene-hill-far' }));
  return shapes;
}

function drawCelestial({ shape }, view) {
  const x = 80 + view.progress * 800;
  const y = 190 - Math.sin(view.progress * Math.PI) * 120;
  const body = view.phase === 'NIGHT'
    ? shape('circle', { cx: x, cy: y, r: 22, class: 'scene-moon' })
    : shape('circle', { cx: x, cy: y + (view.phase === 'DUSK' ? 40 : 0), r: 28, class: 'scene-sun' });
  return [shape('path', { d: 'M80 190 Q480 -50 880 190', class: 'scene-phase-arc' }), body];
}

function drawLand({ shape }) {
  const shapes = [
    shape('path', { d: 'M0 380 Q240 340 480 370 T780 365 L780 540 L0 540 Z', class: 'scene-ground' }),
    shape('path', { d: 'M805 385 Q880 378 960 372 L960 540 L748 540 Q770 450 805 385 Z', class: 'scene-water' }),
    shape('path', { d: 'M748 540 Q770 450 805 385', class: 'scene-shore' }),
    shape('path', { d: 'M332 392 Q336 300 390 268 Q444 300 448 392 Z', class: 'scene-forest-edge' }),
    shape('path', { d: 'M362 392 Q372 340 390 326 Q408 340 418 392 Z', class: 'scene-trail' }),
  ];
  for (const [x, base, height] of PINES) {
    const halfWidth = height * 0.28;
    shapes.push(shape('rect', { x: x - 4, y: base - 14, width: 8, height: 16, class: 'scene-trunk' }));
    shapes.push(shape('path', { d: `M${x - halfWidth} ${base - 10} L${x} ${base - height} L${x + halfWidth} ${base - 10} Z`, class: 'scene-pine' }));
  }
  for (const outline of ROCKS) {
    shapes.push(shape('path', { d: outline, class: 'scene-rock' }));
  }
  return shapes;
}

function drawCamp({ shape, label }, view) {
  const shapes = [];
  TENT_SLOTS.slice(0, view.tents).forEach(([x, base, scale]) => {
    const width = 90 * scale;
    const height = 70 * scale;
    shapes.push(shape('g', { class: 'scene-tent' },
      shape('path', { d: `M${x} ${base} L${x + width / 2} ${base - height} L${x + width} ${base} Z`, class: 'scene-tent-canvas' }),
      shape('path', { d: `M${x + width / 2 - 9 * scale} ${base} L${x + width / 2} ${base - 28 * scale} L${x + width / 2 + 9 * scale} ${base} Z`, class: 'scene-tent-door' })));
  });
  if (view.extraTents > 0) {
    shapes.push(label(`+${view.extraTents}`, 770, 250, 'scene-count'));
  }
  BOAT_SLOTS.slice(0, view.boats).forEach(([x, y]) => {
    shapes.push(shape('g', { class: 'scene-boat' },
      shape('path', { d: `M${x} ${y} L${x + 60} ${y} L${x + 50} ${y + 14} L${x + 10} ${y + 14} Z`, class: 'scene-hull' }),
      shape('path', { d: `M${x + 30} ${y} L${x + 30} ${y - 38} L${x + 52} ${y - 6} Z`, class: 'scene-sail' })));
  });
  if (view.extraBoats > 0) {
    shapes.push(label(`+${view.extraBoats}`, 935, 445, 'scene-count'));
  }
  shapes.push(shape('g', { class: 'scene-fire' },
    shape('circle', { cx: 590, cy: 470, r: view.night ? 70 : 40, class: 'scene-fire-glow' }),
    shape('path', { d: 'M555 495 L625 475 M555 475 L625 495', class: 'scene-logs' }),
    shape('path', { d: 'M570 488 Q575 450 590 430 Q605 450 610 488 Z', class: 'scene-flame' }),
    shape('path', { d: 'M580 488 Q584 462 592 450 Q600 465 600 488 Z', class: 'scene-flame-core' })));
  return shapes;
}

function figure({ shape }, [x, y], className) {
  return shape('g', { class: className },
    shape('rect', { x: x - 12, y: y - 46, width: 24, height: 46, rx: 9, class: 'scene-body' }),
    shape('circle', { cx: x, cy: y - 59, r: 13, class: 'scene-head' }));
}

function weaponShape({ shape }, weapon, [x, y]) {
  switch (weapon) {
    case 'CLUB':
      return shape('path', { d: `M${x + 10} ${y - 20} L${x + 22} ${y - 44}`, class: 'scene-weapon scene-weapon-club' });
    case 'STONE_AXE':
      return shape('g', { class: 'scene-weapon scene-weapon-axe' },
        shape('path', { d: `M${x + 10} ${y - 18} L${x + 20} ${y - 48}`, class: 'scene-weapon-shaft' }),
        shape('path', { d: `M${x + 16} ${y - 50} L${x + 30} ${y - 46} L${x + 24} ${y - 38} Z`, class: 'scene-weapon-head' }));
    case 'SPEAR':
      return shape('g', { class: 'scene-weapon scene-weapon-spear' },
        shape('path', { d: `M${x + 12} ${y - 6} L${x + 26} ${y - 68}`, class: 'scene-weapon-shaft' }),
        shape('path', { d: `M${x + 22} ${y - 66} L${x + 28} ${y - 80} L${x + 30} ${y - 64} Z`, class: 'scene-weapon-head' }));
    default:
      return null;
  }
}

function drawSurvivors(draw, view) {
  const { shape, label } = draw;
  const shapes = SURVIVOR_SLOTS.slice(0, view.others).map(slot => figure(draw, slot, 'scene-survivor'));
  if (view.extraOthers > 0) {
    shapes.push(label(`+${view.extraOthers}`, 410, 420, 'scene-count'));
  }
  const [x, y] = view.status === 'COMBAT' ? PLAYER_COMBAT_SLOT : PLAYER_SLOT;
  switch (view.status) {
    case 'SLEEPING':
      shapes.push(label('z z', 590, 250, 'scene-sleep'));
      break;
    case 'DEAD':
      shapes.push(shape('path', { d: `M${x - 14} ${y} L${x - 14} ${y - 30} Q${x} ${y - 46} ${x + 14} ${y - 30} L${x + 14} ${y} Z`, class: 'scene-grave' }));
      shapes.push(label(view.name, x, y - 54, 'scene-name'));
      break;
    case 'ESCAPED':
      shapes.push(shape('g', { class: 'scene-escape-boat' },
        shape('path', { d: 'M880 420 L940 420 L930 434 L890 434 Z', class: 'scene-hull' }),
        shape('path', { d: 'M910 420 L910 380 L934 414 Z', class: 'scene-sail' })));
      shapes.push(label(view.name, 910, 370, 'scene-name'));
      break;
    default: {
      shapes.push(figure(draw, [x, y], 'scene-player'));
      const weapon = weaponShape(draw, view.weapon, [x, y]);
      if (weapon) {
        shapes.push(weapon);
      }
      if (view.guarding) {
        shapes.push(shape('g', { class: 'scene-torch' },
          shape('path', { d: `M${x - 12} ${y - 14} L${x - 18} ${y - 52}`, class: 'scene-torch-stick' }),
          shape('circle', { cx: x - 18, cy: y - 58, r: 7, class: 'scene-flame' })));
      }
      shapes.push(label(view.name, x, y - 82, 'scene-name'));
    }
  }
  return shapes;
}

const EYE_POSITIONS = {
  hog: { cx: 430, cy: 470 }, wolf: { cx: 434, cy: 455 }, bear: { cx: 440, cy: 441 }, unknown: { cx: 420, cy: 470 },
};

function drawEnemy({ shape }, view) {
  if (!view) {
    return [];
  }
  const kind = ENEMY_NAMES[view.kind] ? view.kind.toLowerCase() : 'unknown';
  const silhouettes = {
    hog: [shape('ellipse', { cx: 390, cy: 480, rx: 34, ry: 20 }), shape('circle', { cx: 425, cy: 474, r: 13 })],
    wolf: [shape('ellipse', { cx: 385, cy: 470, rx: 40, ry: 15 }), shape('path', { d: 'M418 466 L446 456 L436 448 L430 438 L424 452 Z' }),
      shape('path', { d: 'M355 480 L352 505 M372 482 L370 505 M398 482 L400 505 M415 478 L418 505', class: 'scene-legs' })],
    bear: [shape('ellipse', { cx: 385, cy: 465, rx: 50, ry: 32 }), shape('circle', { cx: 432, cy: 445, r: 20 }),
      shape('circle', { cx: 440, cy: 426, r: 7 })],
    unknown: [shape('ellipse', { cx: 390, cy: 475, rx: 40, ry: 22 })],
  };
  return [
    shape('g', { class: `scene-enemy scene-enemy-${kind}` }, ...silhouettes[kind]),
    shape('circle', { ...EYE_POSITIONS[kind], r: 3.5, class: 'scene-eye' }),
    shape('rect', { x: 340, y: 395, width: HEALTH_BAR_WIDTH, height: 8, rx: 4, class: 'scene-enemy-health-track' }),
    shape('rect', { x: 340, y: 395, width: view.healthWidth, height: 8, rx: 4, class: 'scene-enemy-health' }),
  ];
}

function drawEffects({ shape }, view) {
  if (!view.raid) {
    return [];
  }
  const shapes = [shape('rect', { x: 0, y: 0, width: 960, height: 540, class: 'scene-raid-flash' })];
  if (view.burned) {
    for (const [x, delay] of [[470, 0], [560, 0.4], [650, 0.8]]) {
      shapes.push(shape('circle', { cx: x, cy: 280, r: 24, class: 'scene-smoke', style: `animation-delay: ${delay}s` }));
    }
  }
  return shapes;
}

function drawHotspots({ shape }, placements) {
  return placements.map(({ action, area, available }) => {
    const hitArea = shape('rect', { ...area, rx: 18, class: available ? 'scene-hotspot is-available' : 'scene-hotspot' });
    hitArea.dataset.action = action;
    return hitArea;
  });
}

const LAYER_DRAWERS = {
  sky: drawSky,
  celestial: drawCelestial,
  land: drawLand,
  camp: drawCamp,
  survivors: drawSurvivors,
  enemy: drawEnemy,
  effects: drawEffects,
  hotspots: drawHotspots,
};
