/** Native WebGL renderer for the island terrain, adventurers and landmarks. */
import { groundHeight, islandMesh, meshBuilder } from './survive-world-art.js';
import { cameraMatrix, combatPosition, projectPoint } from './survive-world-model.js';

const VERTEX_SHADER = `
attribute vec3 position;
attribute vec3 color;
uniform mat4 camera;
varying vec3 tint;
varying vec3 surface;
varying float depth;
void main() {
  gl_Position = camera * vec4(position, 1.0);
  tint = color;
  surface = position;
  depth = gl_Position.w;
}`;
const FRAGMENT_SHADER = `
precision mediump float;
varying vec3 tint;
varying vec3 surface;
varying float depth;
uniform float daylight;
uniform vec3 sky;
float grain(vec3 cell) {
  return fract(sin(dot(mod(cell, 79.0), vec3(13.7, 27.1, 9.3))) * 137.2);
}
void main() {
  float textureDetail = grain(floor(surface * 9.0)) * 0.14 + grain(floor(surface * 2.5)) * 0.13;
  vec3 lit = tint * (0.88 + textureDetail) * daylight;
  gl_FragColor = vec4(mix(lit, sky, smoothstep(36.0, 144.0, depth)), 1.0);
}`;

/** Allocates GPU resources once; the caller owns draw/dispose and context-loss recovery. */
export function createWorldRenderer(canvas) {
  const gl = canvas.getContext('webgl', { alpha: false, antialias: true });
  if (!gl) throw new Error('3D graphics are unavailable on this device.');
  const shaders = [];
  const buffers = [];
  const program = gl.createProgram();
  function dispose() {
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
    const scenery = islandMesh();
    const staticBuffer = gl.createBuffer();
    const actorBuffer = gl.createBuffer();
    buffers.push(staticBuffer, actorBuffer);
    gl.bindBuffer(gl.ARRAY_BUFFER, staticBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(scenery.vertices), gl.STATIC_DRAW);
    const positionAttribute = gl.getAttribLocation(program, 'position');
    const colorAttribute = gl.getAttribLocation(program, 'color');
    const cameraUniform = gl.getUniformLocation(program, 'camera');
    const daylightUniform = gl.getUniformLocation(program, 'daylight');
    const skyUniform = gl.getUniformLocation(program, 'sky');
    gl.enable(gl.DEPTH_TEST);
    function drawBuffer(buffer, count) {
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.enableVertexAttribArray(positionAttribute);
      gl.vertexAttribPointer(positionAttribute, 3, gl.FLOAT, false, 24, 0);
      gl.enableVertexAttribArray(colorAttribute);
      gl.vertexAttribPointer(colorAttribute, 3, gl.FLOAT, false, 24, 12);
      gl.drawArrays(gl.TRIANGLES, 0, count);
    }
    return {
      draw(position, yaw, state, others, animationSeconds = 0) {
        const pixelRatio = Math.min(1.5, globalThis.devicePixelRatio || 1);
        const width = Math.max(1, Math.round(canvas.clientWidth * pixelRatio));
        const height = Math.max(1, Math.round(canvas.clientHeight * pixelRatio));
        if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
        gl.viewport(0, 0, width, height);
        const matrix = cameraMatrix(position, yaw, width / height, groundHeight(position.x, position.z));
        const sky = state.phase === 'NIGHT' ? [0.09, 0.13, 0.20]
          : state.phase === 'DUSK' ? [0.61, 0.46, 0.39] : [0.62, 0.72, 0.74];
        gl.clearColor(...sky, 1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        gl.useProgram(program);
        gl.uniformMatrix4fv(cameraUniform, false, matrix);
        gl.uniform1f(daylightUniform, state.phase === 'NIGHT' ? 0.57 : state.phase === 'DUSK' ? 0.79 : 1.13);
        gl.uniform3fv(skyUniform, sky);
        drawBuffer(staticBuffer, scenery.vertices.length / 6);
        const actors = meshBuilder();
        const stride = avatar => avatar.moving ? Math.sin(animationSeconds * 11) : 0;
        actors.avatar(position.x, position.z, position.heading, [0.55, 0.41, 0.27], state.status, stride(position), state.weapon);
        // Bound dynamic geometry in a crowded camp while drawing nearby players first.
        const nearestPlayers = others.map(other => ({ other, distance: Math.hypot(other.x - position.x, other.z - position.z) }))
          .filter(player => player.distance <= 60).sort((a, b) => a.distance - b.distance).slice(0, 24);
        for (const { other } of nearestPlayers) {
          actors.avatar(other.x, other.z, other.heading, [0.34, 0.43, 0.45], other.status, stride(other));
        }
        for (let index = 0; index < Math.min(8, state.shelters); index++) {
          const x = (index % 4) * 5 - 8;
          const z = Math.floor(index / 4) * 6 - 8;
          const y = groundHeight(x, z);
          actors.box(x, y + 0.8, z, 3.3, 1.2, 3, [0.45, 0.36, 0.25]);
          actors.cone(x, y + 1.4, z, 2.8, 2.4, [0.60, 0.48, 0.32], 4);
        }
        for (let index = 0; index < Math.min(4, state.boats); index++) {
          actors.box(5 + index * 4, 0.3, 80, 2.3, 0.9, 4.5, [0.39, 0.23, 0.11]);
          actors.box(5 + index * 4, 0.9, 80, 1.5, 0.2, 3.5, [0.75, 0.52, 0.28]);
        }
        if (state.status === 'COMBAT') {
          const scale = state.enemy === 'BEAR' ? 1.6 : 1;
          const enemy = combatPosition(position, yaw);
          const y = groundHeight(enemy.x, enemy.z);
          actors.disk(enemy.x, y + 0.12, enemy.z, 1.5, state.nextStrikeAt ? [0.56, 0.16, 0.10] : [0.58, 0.43, 0.17], 16);
          actors.animal(enemy.x, enemy.z, state.enemy, scale);
        }
        gl.bindBuffer(gl.ARRAY_BUFFER, actorBuffer);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(actors.vertices), gl.DYNAMIC_DRAW);
        drawBuffer(actorBuffer, actors.vertices.length / 6);
        return { project: point => projectPoint(matrix, point) };
      },
      dispose,
    };
  } catch (error) {
    dispose();
    throw error;
  }
}
