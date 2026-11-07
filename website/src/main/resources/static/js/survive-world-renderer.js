/** Native WebGL renderer for the island terrain, adventurers and landmarks. */
import { groundHeight, islandMesh, meshBuilder, MESH_VERTEX_FLOATS } from './survive-world-art.js';
import { createIslandMaterials } from './survive-world-materials.js';
import { drawBoat, drawCampFlame, drawShelter } from './survive-world-props.js';
import { actionPose, combatStance } from './survive-action-pose.js';
import { prepareActorArt } from './survive-authored-actors.js';
import { createDynamicMesh } from './survive-dynamic-mesh.js';
import { cameraMatrix, fishingHeading, projectPoint } from './survive-world-model.js';
import { fetchJson } from './lib/util.js';

const VERTEX_SHADER = `
attribute vec3 position;
attribute vec3 color;
attribute float material;
attribute vec2 texCoord;
uniform mat4 camera;
varying vec3 tint;
varying vec3 surface;
varying float depth;
varying float surfaceMaterial;
varying vec2 uv;
void main() {
  gl_Position = camera * vec4(position, 1.0);
  tint = color;
  surface = position;
  depth = gl_Position.w;
  surfaceMaterial = material;
  uv = texCoord;
}`;

const RAIDER_CLOTH = [0.47, 0.16, 0.12];
const RAIDER_ARMOR_TINT = [1.20, 0.67, 0.57];
const FRAGMENT_SHADER = `
precision mediump float;
varying vec3 tint;
varying vec3 surface;
varying float depth;
varying float surfaceMaterial;
varying vec2 uv;
uniform float daylight;
uniform vec3 sky;
uniform float time;
uniform float fireTime;
uniform sampler2D grassTile;
uniform sampler2D earthTile;
uniform sampler2D stoneTile;
uniform sampler2D barkTile;
uniform sampler2D foliageCard;
uniform sampler2D actorAtlas;
uniform sampler2D authoredActorAtlas;
float grain(vec3 cell) {
  return fract(sin(dot(mod(cell, 79.0), vec3(13.7, 27.1, 9.3))) * 137.2);
}
float cloudNoise(vec2 point) {
  vec2 cell = floor(point);
  vec2 blend = smoothstep(0.0, 1.0, fract(point));
  float rowA = mix(grain(vec3(cell, 4.0)), grain(vec3(cell + vec2(1.0, 0.0), 4.0)), blend.x);
  float rowB = mix(grain(vec3(cell + vec2(0.0, 1.0), 4.0)), grain(vec3(cell + vec2(1.0), 4.0)), blend.x);
  return mix(rowA, rowB, blend.y);
}
float flameTongue(vec2 point, float offset, float width, float height, float phase) {
  float rise = clamp(point.y / height, 0.0, 1.0);
  float bend = rise * (0.13 * sin(phase + rise * 5.0) + 0.08);
  float edge = width * (1.0 - rise * 0.91);
  return (1.0 - smoothstep(edge * 0.32, edge, abs(point.x - offset - bend)))
    * (1.0 - smoothstep(height * 0.62, height, point.y)) * smoothstep(0.0, 0.08, point.y);
}
float spark(vec2 point, float seed) {
  float rise = fract(fireTime * 0.59 + seed);
  vec2 ember = vec2(0.19 * sin(seed * 25.0 + rise * 5.0), 0.28 + rise * 0.68);
  return (1.0 - smoothstep(0.004, 0.022, length((point - ember) * vec2(1.0, 1.6))))
    * sin(rise * 3.14159);
}
void main() {
  if (surfaceMaterial > 10.0 && surfaceMaterial < 11.0) {
    vec2 point = vec2(uv.x - 0.5, uv.y);
    float pulse = fireTime * 6.0;
    float body = max(flameTongue(point, -0.16, 0.20, 0.54 + 0.10 * sin(pulse), pulse),
      flameTongue(point, 0.13, 0.19, 0.49 + 0.09 * sin(pulse + 2.1), pulse + 2.1));
    float core = flameTongue(point, -0.02, 0.20, 0.64 + 0.08 * sin(pulse + 4.2), pulse + 4.2);
    float embers = max(spark(point, 0.18), spark(point, 0.68));
    float alpha = max(max(body * 0.81, core), embers);
    if (alpha < 0.025) discard;
    vec3 flame = mix(vec3(1.0, 0.23, 0.035), vec3(1.0, 0.91, 0.52), max(embers, core * (1.0 - point.y)));
    gl_FragColor = vec4(flame, alpha);
    return;
  }
  if (surfaceMaterial > 9.0 && surfaceMaterial < 10.0) {
    if (uv.x >= 0.99) discard;
    gl_FragColor = vec4(tint, (1.0 - uv.x) * 0.48);
    return;
  }
  if (length(surface.xz) > 100.0 && surface.y > 0.0 || surface.y > 34.0) {
    float elevation = smoothstep(0.0, 105.0, surface.y);
    vec3 zenith = sky * vec3(0.60, 0.79, 1.08);
    vec2 cloudPosition = surface.xz * 0.025 + vec2(0.008, -0.004) * time;
    float cloudShape = cloudNoise(cloudPosition) * 0.73 + cloudNoise(cloudPosition * 2.3) * 0.27;
    float cloud = smoothstep(0.52, 0.74, cloudShape) * smoothstep(0.0, 28.0, surface.y);
    vec3 cloudColor = mix(sky, vec3(0.91, 0.93, 0.94), smoothstep(0.62, 1.06, daylight));
    gl_FragColor = vec4(mix(mix(sky, zenith, elevation), cloudColor, cloud * 0.85), 1.0);
    return;
  }
  float textureDetail = grain(floor(surface * 9.0)) * 0.14 + grain(floor(surface * 2.5)) * 0.13;
  vec3 albedo = tint * (0.88 + textureDetail);
  if (surfaceMaterial > 13.0) {
    albedo = vec3(0.14, 0.31, 0.30);
  } else if (surfaceMaterial > 11.0) {
    albedo = tint * texture2D(authoredActorAtlas, uv).rgb;
  } else if (surfaceMaterial > 5.0) {
    float index = floor(surfaceMaterial) - 5.0;
    vec2 offset = vec2(mod(index, 2.0), floor(index / 2.0)) * 0.5;
    vec3 detail = texture2D(actorAtlas, offset + 0.015 + clamp(uv, 0.0, 1.0) * 0.47).rgb;
    float grainLight = dot(detail, vec3(0.299, 0.587, 0.114));
    albedo = albedo * (0.66 + grainLight * 1.12) + detail * 0.13;
  } else if (surfaceMaterial > 4.0) {
    vec4 leaves = texture2D(foliageCard, uv);
    if (leaves.a < 0.45) discard;
    albedo = leaves.rgb * (vec3(0.38, 0.46, 0.40) + tint * 0.67);
  } else if (surfaceMaterial > 3.0) {
    albedo = mix(albedo, texture2D(barkTile, uv * vec2(0.73, 0.32)).rgb, 0.70);
  } else if (surfaceMaterial > 2.0) {
    albedo = mix(albedo, texture2D(stoneTile, uv / 2.7).rgb * 0.86, 0.72);
  } else if (surfaceMaterial > 0.5) {
    vec3 grass = texture2D(grassTile, uv / 3.3).rgb;
    vec3 path = texture2D(earthTile, uv / 4.1).rgb;
    vec3 ground = mix(grass, path, clamp((surfaceMaterial - 1.0) / 0.9, 0.0, 1.0));
    ground *= clamp(tint.g * 2.6 + 0.15, 0.50, 1.13);
    albedo = mix(albedo, ground, 0.67);
  }
  vec3 lit = albedo * daylight;
  if (abs(surface.x - 4.0) < 3.4 && abs(surface.z) < 3.4 && surface.y < 2.6) {
    float firelight = 1.0 - smoothstep(0.35, 3.4, distance(surface, vec3(4.0, 0.45, 0.0)));
    float flicker = 0.90 + 0.10 * sin(fireTime * 6.0);
    lit += albedo * vec3(0.82, 0.37, 0.11) * firelight * flicker * (1.42 - daylight);
  }
  if (surface.y < -0.64 || surfaceMaterial > 13.0) {
    float ripple = sin(surface.x * 1.6 + surface.z * 0.7 + time * 2.4)
      * sin(surface.z * 1.2 - surface.x * 0.4 - time * 1.8);
    float foam = (1.0 - smoothstep(0.1, 1.6, abs(length(surface.xz) - 78.0))) * (0.4 + ripple * 0.3);
    lit = mix(lit * (1.0 + ripple * 0.12), vec3(0.62, 0.70, 0.68) * daylight, foam);
  }
  gl_FragColor = vec4(mix(lit, sky, smoothstep(36.0, 144.0, depth)), 1.0);
}`;

/** Allocates GPU resources once; the caller owns draw/dispose and context-loss recovery. */
export function createWorldRenderer(canvas) {
  const gl = canvas.getContext('webgl', { alpha: false, antialias: true });
  if (!gl) throw new Error('3D graphics are unavailable on this device.');
  const shaders = [];
  const buffers = [];
  const actorRequests = new AbortController();
  const actorArt = {};
  const program = gl.createProgram();
  let materials;
  function dispose() {
    actorRequests.abort();
    materials?.dispose();
    for (const buffer of buffers) gl.deleteBuffer(buffer);
    for (const shader of shaders) gl.deleteShader(shader);
    gl.deleteProgram(program);
  }
  try {
    for (const [kind, source] of [[gl.VERTEX_SHADER, VERTEX_SHADER], [gl.FRAGMENT_SHADER, FRAGMENT_SHADER]]) {
      const shader = gl.createShader(kind);
      shaders.push(shader);
      gl.shaderSource(shader, source);
      gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error('The island shader could not compile.');
      gl.attachShader(program, shader);
    }
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error('The island renderer could not start.');
    materials = createIslandMaterials(gl, canvas.ownerDocument, program);
    for (const kind of ['adventurer', 'boar', 'wolf', 'bear']) {
      fetchJson(`/images/survive/${kind}.json`, { signal: actorRequests.signal })
        .then(data => { actorArt[kind] = prepareActorArt(data, { adventurer: kind === 'adventurer' }); })
        .catch(() => { /* The procedural model remains available. */ });
    }
    const scenery = islandMesh();
    const staticBuffer = gl.createBuffer();
    const actorBuffer = gl.createBuffer();
    const dynamicMesh = createDynamicMesh(gl, actorBuffer);
    const fallbackActors = meshBuilder(undefined, dynamicMesh.append);
    const authoredActors = meshBuilder(actorArt, dynamicMesh.append);
    buffers.push(staticBuffer, actorBuffer);
    gl.bindBuffer(gl.ARRAY_BUFFER, staticBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(scenery.vertices), gl.STATIC_DRAW);
    const positionAttribute = gl.getAttribLocation(program, 'position');
    const colorAttribute = gl.getAttribLocation(program, 'color');
    const materialAttribute = gl.getAttribLocation(program, 'material');
    const uvAttribute = gl.getAttribLocation(program, 'texCoord');
    const cameraUniform = gl.getUniformLocation(program, 'camera');
    const daylightUniform = gl.getUniformLocation(program, 'daylight');
    const skyUniform = gl.getUniformLocation(program, 'sky');
    const timeUniform = gl.getUniformLocation(program, 'time');
    const fireTimeUniform = gl.getUniformLocation(program, 'fireTime');
    gl.enable(gl.DEPTH_TEST);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
    const vertexBytes = MESH_VERTEX_FLOATS * Float32Array.BYTES_PER_ELEMENT;
    const attributes = [[positionAttribute, 3, 0], [colorAttribute, 3, 12], [materialAttribute, 1, 24], [uvAttribute, 2, 28]];
    function drawBuffer(buffer, count) {
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      for (const [attribute, components, offset] of attributes) {
        gl.enableVertexAttribArray(attribute);
        gl.vertexAttribPointer(attribute, components, gl.FLOAT, false, vertexBytes, offset);
      }
      gl.drawArrays(gl.TRIANGLES, 0, count);
    }
    return {
      draw(position, yaw, state, others, animationSeconds, wildlife, enemy, gesture, cameraZoom, counter) {
        const pixelBudgetRatio = Math.sqrt(2200000 / Math.max(1, canvas.clientWidth * canvas.clientHeight));
        const pixelRatio = Math.min(2, pixelBudgetRatio, globalThis.devicePixelRatio || 1);
        const width = Math.max(1, Math.round(canvas.clientWidth * pixelRatio));
        const height = Math.max(1, Math.round(canvas.clientHeight * pixelRatio));
        if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
        gl.viewport(0, 0, width, height);
        const matrix = cameraMatrix(position, yaw, width / height, groundHeight(position.x, position.z), cameraZoom, groundHeight);
        const sky = state.phase === 'NIGHT' ? [0.09, 0.13, 0.20]
          : state.phase === 'DUSK' ? [0.61, 0.46, 0.39] : [0.58, 0.70, 0.83];
        gl.clearColor(...sky, 1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        gl.useProgram(program);
        gl.uniformMatrix4fv(cameraUniform, false, matrix);
        gl.uniform1f(daylightUniform, state.phase === 'NIGHT' ? 0.57 : state.phase === 'DUSK' ? 0.79 : 1.13);
        gl.uniform3fv(skyUniform, sky);
        gl.uniform1f(timeUniform, animationSeconds % 120);
        gl.uniform1f(fireTimeUniform, position.animate ? animationSeconds % 120 : 0);
        drawBuffer(staticBuffer, scenery.vertices.length / MESH_VERTEX_FLOATS);
        dynamicMesh.reset();
        const actors = materials.authoredActorsReady ? authoredActors : fallbackActors;
        const stride = avatar => avatar.moving ? Math.sin(animationSeconds * 11) : 0;
        const fishingPose = position.fishing ? { kind: 'fish', progress: 0.14, weight: 0.65,
          bobberDip: position.fishing === 'bite' && position.animate
            ? 0.17 * (1 + Math.sin(animationSeconds * 14)) : 0 } : null;
        const pose = actionPose(gesture) || fishingPose
          || combatStance(state.status === 'COMBAT' && state.nextStrikeAt, state.defending)
          || (state.status === 'SLEEPING' && position.animate
            ? { kind: 'sleep', weight: Math.sin(animationSeconds * 2.6) } : null);
        const restingHeading = position.x === 0 && position.z === 0 ? Math.PI : position.heading;
        const heading = pose?.kind === 'fish' ? fishingHeading(position.x, position.z)
          : state.status === 'COMBAT' ? Math.atan2(enemy.x - position.x, enemy.z - position.z) : restingHeading;
        const playerPose = counter?.recoil ? { ...pose, recoil: counter.recoil } : pose;
        actors.avatar(position.x, position.z, heading, [0.24, 0.31, 0.42], state.status,
          pose?.kind === 'run' ? Math.sin(animationSeconds * 19) : stride(position), state.weapon, playerPose, true, animationSeconds);
        // Bound dynamic geometry in a crowded camp while drawing nearby players first.
        const nearestPlayers = others.map(other => ({ other, distance: Math.hypot(other.x - position.x, other.z - position.z) }))
          .filter(player => player.distance <= 60).sort((a, b) => a.distance - b.distance).slice(0, 24);
        for (const [index, { other, distance }] of nearestPlayers.entries()) {
          const pose = combatStance(other.appearance?.engaged, other.appearance?.defending);
          actors.avatar(other.x, other.z, other.heading, [0.34, 0.43, 0.45], other.status,
            pose ? 0 : stride(other), other.appearance?.weapon, pose,
            index < 6 && distance < 13, animationSeconds);
        }
        const keeper = state.waystationKeeper;
        if (keeper) {
          const distance = Math.hypot(keeper.x - position.x, keeper.z - position.z);
          if (distance <= 32) {
            actors.avatar(keeper.x, keeper.z, 0, [0.32, 0.43, 0.25], 'EXPLORING', 0,
              null, null, distance < 16, animationSeconds, [0.93, 0.91, 0.76], true);
          }
        }
        for (let index = 0; index < Math.min(8, state.shelters); index++) {
          const x = (index % 4) * 5 - 8;
          const z = Math.floor(index / 4) * 6 - 8;
          const y = groundHeight(x, z);
          drawShelter(actors, x, y, z);
        }
        for (let index = 0; index < Math.min(4, state.boats); index++) {
          drawBoat(actors, 5 + index * 4, 80);
        }
        for (const animal of wildlife) {
          if (animal.kind === 'RAIDER') {
            actors.avatar(animal.x, animal.z, animal.heading, RAIDER_CLOTH, 'EXPLORING', stride(animal),
              'CLUB', null, true, animationSeconds, RAIDER_ARMOR_TINT);
          } else {
            actors.animal(animal.x, animal.z, animal.kind, animal.kind === 'BEAR' ? 1.6 : 1, animal.heading,
              stride(animal), 0, Math.hypot(animal.x - position.x, animal.z - position.z) < 16, animationSeconds);
          }
        }
        if (state.status === 'COMBAT') {
          const scale = state.enemy === 'BEAR' ? 1.6 : 1;
          const radius = scale * 0.86;
          const ringColor = state.nextStrikeAt ? [0.67, 0.23, 0.15] : [0.64, 0.48, 0.21];
          drawTargetRing(actors, enemy, radius, ringColor);
          const facing = Math.atan2(position.x - enemy.x, position.z - enemy.z);
          const enemyX = enemy.x + Math.sin(facing) * (counter?.forward ?? 0);
          const enemyZ = enemy.z + Math.cos(facing) * (counter?.forward ?? 0);
          if (state.enemy === 'RAIDER') {
            actors.avatar(enemyX, enemyZ, facing, RAIDER_CLOTH, 'COMBAT', stride(enemy), 'CLUB',
              counter, true, animationSeconds, RAIDER_ARMOR_TINT);
          } else {
            actors.animal(enemyX, enemyZ, state.enemy, scale, facing, stride(enemy),
              !counter && pose?.kind === 'strike' ? pose.weight : 0,
              true, animationSeconds);
          }
        }
        if (Math.hypot(position.x - 4, position.z) < 45) drawCampFlame(actors, yaw);
        drawBuffer(actorBuffer, dynamicMesh.upload() / MESH_VERTEX_FLOATS);
        return { project: point => projectPoint(matrix, point) };
      },
      dispose,
    };
  } catch (error) {
    dispose();
    throw error;
  }
}

function drawTargetRing(mesh, enemy, radius, color) {
  const sides = 28;
  const point = (distance, angle) => {
    const x = enemy.x + Math.cos(angle) * distance;
    const z = enemy.z + Math.sin(angle) * distance;
    return [x, groundHeight(x, z) + 0.10, z];
  };
  for (let segment = 0; segment < sides; segment++) {
    const angle = segment / sides * Math.PI * 2;
    const next = (segment + 1) / sides * Math.PI * 2;
    const a = point(radius, angle);
    const b = point(radius, next);
    const c = point(radius - 0.055, next);
    const d = point(radius - 0.055, angle);
    mesh.triangle(a, b, c, color);
    mesh.triangle(a, c, d, color);
  }
}
