/** Small native WebGL renderer: generated low-poly meshes, no assets or build dependencies. */
import { cameraMatrix, combatPosition, LANDMARKS, projectPoint } from './survive-world-model.js';

const VERTEX_SHADER = `
attribute vec3 position;
attribute vec3 color;
uniform mat4 camera;
varying vec3 tint;
varying float depth;
void main() {
  gl_Position = camera * vec4(position, 1.0);
  tint = color;
  depth = gl_Position.w;
}`;
const FRAGMENT_SHADER = `
precision mediump float;
varying vec3 tint;
varying float depth;
uniform float daylight;
uniform vec3 sky;
void main() {
  vec3 lit = tint * daylight;
  gl_FragColor = vec4(mix(lit, sky, smoothstep(70.0, 230.0, depth)), 1.0);
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
      draw(position, yaw, state, others, feedback = null) {
        const pixelRatio = Math.min(1.5, globalThis.devicePixelRatio || 1);
        const width = Math.max(1, Math.round(canvas.clientWidth * pixelRatio));
        const height = Math.max(1, Math.round(canvas.clientHeight * pixelRatio));
        if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
        gl.viewport(0, 0, width, height);
        const matrix = cameraMatrix(position, yaw, width / height);
        const sky = state.phase === 'NIGHT' ? [0.035, 0.065, 0.14]
          : state.phase === 'DUSK' ? [0.5, 0.32, 0.32] : [0.48, 0.73, 0.83];
        gl.clearColor(...sky, 1);
        gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
        gl.useProgram(program);
        gl.uniformMatrix4fv(cameraUniform, false, matrix);
        gl.uniform1f(daylightUniform, state.phase === 'NIGHT' ? 0.42 : state.phase === 'DUSK' ? 0.72 : 1);
        gl.uniform3fv(skyUniform, sky);
        drawBuffer(staticBuffer, scenery.vertices.length / 6);
        const actors = meshBuilder();
        actors.avatar(position.x, position.z, position.heading, [0.95, 0.61, 0.24], state.status);
        for (const other of others) actors.avatar(other.x, other.z, other.heading, [0.27, 0.72, 0.83], other.status);
        for (let index = 0; index < Math.min(8, state.shelters); index++) {
          const x = (index % 4) * 5 - 8;
          const z = Math.floor(index / 4) * 6 - 8;
          actors.box(x, 0.8, z, 3.3, 1.2, 3, [0.48, 0.35, 0.22]);
          actors.cone(x, 1.4, z, 2.8, 2.7, [0.72, 0.48, 0.29], 4);
        }
        for (let index = 0; index < Math.min(4, state.boats); index++) {
          actors.box(5 + index * 4, 0.3, 80, 2.3, 0.9, 4.5, [0.39, 0.23, 0.11]);
          actors.box(5 + index * 4, 0.9, 80, 1.5, 0.2, 3.5, [0.75, 0.52, 0.28]);
        }
        if (state.status === 'COMBAT') {
          const scale = state.enemy === 'BEAR' ? 1.6 : 1;
          const enemy = combatPosition(position, yaw);
          actors.disk(enemy.x, 0.08, enemy.z, 2, [0.7, 0.14, 0.09], 16);
          actors.animal(enemy.x, enemy.z, scale);
        }
        if (feedback?.kind === 'gain' && feedback.ageSeconds < 1.5) {
          for (let index = 0; index < 8; index++) {
            const angle = index * Math.PI / 4;
            const radius = 0.5 + feedback.ageSeconds;
            actors.cone(position.x + Math.cos(angle) * radius, 1.7 + feedback.ageSeconds * 1.2,
              position.z + Math.sin(angle) * radius, 0.12, 0.3, [1, 0.86, 0.35], 4);
          }
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

function islandMesh() {
  const mesh = meshBuilder();
  mesh.disk(0, -0.5, 0, 500, [0.12, 0.4, 0.51], 64);
  mesh.disk(0, -0.2, 0, 79, [0.82, 0.73, 0.5], 64);
  mesh.disk(0, 0, 0, 68, [0.32, 0.47, 0.28], 64);
  mesh.disk(0, 0.02, 0, 15, [0.57, 0.46, 0.3], 28);
  for (const landmark of LANDMARKS.slice(1)) {
    // A broad footpath links camp to each destination, so players can navigate without a map.
    const steps = Math.ceil(Math.hypot(landmark.x, landmark.z) / 3);
    for (let step = 0; step <= steps; step++) {
      mesh.disk(landmark.x * step / steps, 0.035, landmark.z * step / steps, 1.7, [0.56, 0.51, 0.34], 8);
    }
    mesh.disk(landmark.x, 0.045, landmark.z, landmark.radius - 1, [0.42, 0.49, 0.29], 24);
    mesh.box(landmark.x + 3, 1.1, landmark.z + 2, 0.25, 2.2, 0.25, [0.33, 0.22, 0.13]);
    mesh.box(landmark.x + 3, 2, landmark.z + 2, 2.5, 0.8, 0.3, [0.74, 0.61, 0.38]);
  }
  for (let index = 0; index < 100; index++) {
    const angle = index * 2.39996;
    const radius = 47 + (index * 17 % 26);
    const x = Math.cos(angle) * radius;
    const z = Math.sin(angle) * radius;
    if (Math.abs(x) < 8 && z > 0 || Math.hypot(x + 36, z - 25) < 16) continue;
    mesh.tree(x, z, 4 + index % 4);
  }
  for (let index = 0; index < 25; index++) {
    const angle = index * 2.39996;
    const radius = 3 + Math.sqrt(index) * 2;
    mesh.tree(-24 + Math.cos(angle) * radius, -15 + Math.sin(angle) * radius, 5 + index % 4);
  }
  for (let index = 0; index < 14; index++) {
    mesh.cone(27 + index % 4 * 2.8, 0, -17 + Math.floor(index / 4) * 3, 2 + index % 3, 2 + index % 4,
      [0.49, 0.52, 0.51], 5);
  }
  mesh.animal(-36, 25, 1);
  mesh.animal(-41, 21, 0.8);
  mesh.disk(4, 0.1, 0, 2.4, [0.26, 0.25, 0.22], 12);
  mesh.box(4, 0.35, 0, 2.5, 0.4, 0.45, [0.27, 0.16, 0.08]);
  mesh.cone(4, 0.35, 0, 0.9, 2, [1, 0.45, 0.12], 6);
  mesh.cone(4, 0.35, 0, 0.5, 1.3, [1, 0.8, 0.25], 5);
  mesh.box(0, 0.25, 75, 4, 0.45, 12, [0.5, 0.35, 0.19]);
  return mesh;
}

/** Flat-shaded triangles are built in world space, using one interleaved position/color buffer. */
function meshBuilder() {
  const vertices = [];
  function triangle(a, b, c, color) {
    const ab = b.map((component, index) => component - a[index]);
    const ac = c.map((component, index) => component - a[index]);
    const normal = [ab[1] * ac[2] - ab[2] * ac[1], ab[2] * ac[0] - ab[0] * ac[2], ab[0] * ac[1] - ab[1] * ac[0]];
    const length = Math.hypot(...normal) || 1;
    const shade = 0.65 + 0.35 * Math.abs((normal[0] * 0.35 + normal[1] * 0.8 + normal[2] * 0.45) / length);
    for (const point of [a, b, c]) vertices.push(...point, ...color.map(component => component * shade));
  }
  function box(x, y, z, width, height, depth, color) {
    const points = [];
    for (const dy of [-1, 1]) for (const dz of [-1, 1]) for (const dx of [-1, 1]) {
      points.push([x + dx * width / 2, y + dy * height / 2, z + dz * depth / 2]);
    }
    for (const [a, b, c, d] of [[0, 1, 3, 2], [4, 6, 7, 5], [0, 4, 5, 1], [2, 3, 7, 6], [0, 2, 6, 4], [1, 5, 7, 3]]) {
      triangle(points[a], points[b], points[c], color);
      triangle(points[a], points[c], points[d], color);
    }
  }
  function cone(x, y, z, radius, height, color, sides = 8) {
    for (let side = 0; side < sides; side++) {
      const angle = side / sides * Math.PI * 2;
      const next = (side + 1) / sides * Math.PI * 2;
      triangle([x, y + height, z], [x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius],
        [x + Math.cos(next) * radius, y, z + Math.sin(next) * radius], color);
    }
  }
  function disk(x, y, z, radius, color, sides) { cone(x, y, z, radius, 0, color, sides); }
  function tree(x, z, height) {
    box(x, height * 0.25, z, 0.55, height * 0.5, 0.55, [0.35, 0.23, 0.13]);
    cone(x, height * 0.2, z, height * 0.37, height * 0.65, [0.14, 0.32, 0.23]);
    cone(x, height * 0.48, z, height * 0.28, height * 0.52, [0.2, 0.4, 0.28]);
  }
  function avatar(x, z, heading, color, status) {
    const height = ['SLEEPING', 'DEAD', 'ESCAPED'].includes(status) ? 0.45 : 1;
    box(x, 1.2 * height, z, 0.75, 0.85 * height, 0.5, color);
    box(x, 1.95 * height, z, 0.55, 0.55, 0.55, [0.83, 0.67, 0.49]);
    box(x - 0.22, 0.4 * height, z, 0.28, 0.8 * height, 0.32, [0.2, 0.25, 0.28]);
    box(x + 0.22, 0.4 * height, z, 0.28, 0.8 * height, 0.32, [0.2, 0.25, 0.28]);
    box(x + Math.sin(heading) * 0.35, 1.95 * height, z + Math.cos(heading) * 0.35, 0.18, 0.15, 0.18, [0.74, 0.5, 0.35]);
  }
  function animal(x, z, scale) {
    box(x, 0.75 * scale, z, 1.7 * scale, scale, 0.9 * scale, [0.43, 0.3, 0.22]);
    box(x + scale, 0.65 * scale, z, 0.7 * scale, 0.6 * scale, 0.6 * scale, [0.54, 0.38, 0.3]);
    for (const dx of [-0.5, 0.5]) for (const dz of [-0.3, 0.3]) {
      box(x + dx * scale, 0.3 * scale, z + dz * scale, 0.2 * scale, 0.6 * scale, 0.2 * scale, [0.23, 0.2, 0.18]);
    }
  }
  return { vertices, box, cone, disk, tree, avatar, animal };
}
