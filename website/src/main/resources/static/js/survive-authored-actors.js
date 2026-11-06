/** Samples baked skinning clips and submits the pose to the existing island mesh. */
const WHITE = [1, 1, 1];

export function prepareActorArt(data, { adventurer = false } = {}) {
  const triangles = [];
  for (let index = 0; index < data.faces.length; index += 3) {
    const uv = [];
    for (let corner = index; corner < index + 3; corner++) {
      uv.push([data.uv[corner * 2] / 32767, data.uv[corner * 2 + 1] / 32767]);
    }
    triangles.push({
      indices: data.faces.slice(index, index + 3),
      uv,
    });
  }
  const compactFrames = clips => Object.fromEntries(Object.entries(clips).map(([clip, frames]) =>
    [clip, frames.map(frame => Int16Array.from(frame))]));
  const model = { triangles, poses: compactFrames(data.poses), hands: compactFrames(data.hands) };
  if (adventurer) refineAdventurerProportions(model);
  return model;
}

/** Refine the baked proportions once, keeping vertex order, UVs and clip lengths intact. */
function refineAdventurerProportions(model) {
  const rest = model.poses.idle[0].slice();
  const head = [];
  for (let offset = 0; offset < rest.length; offset += 3) {
    if (rest[offset + 1] >= 1940) head.push(offset);
  }
  const lengthenLegs = millimetres => millimetres + Math.max(0, Math.min(1150, millimetres)) * 0.19;
  for (const frames of Object.values(model.poses)) {
    for (const frame of frames) {
      let centerX = 0;
      let centerZ = 0;
      let baseY = Infinity;
      for (const offset of head) {
        centerX += frame[offset];
        centerZ += frame[offset + 2];
        baseY = Math.min(baseY, frame[offset + 1]);
      }
      centerX /= head.length;
      centerZ /= head.length;
      for (const offset of head) {
        const weight = Math.min(1, (rest[offset + 1] - 1940) / 140);
        frame[offset] += (centerX - frame[offset]) * weight * 0.18;
        frame[offset + 1] += (baseY - frame[offset + 1]) * weight * 0.23;
        frame[offset + 2] += (centerZ - frame[offset + 2]) * weight * 0.17;
      }
      for (let height = 1; height < frame.length; height += 3) frame[height] = lengthenLegs(frame[height]);
    }
  }
  for (const frames of Object.values(model.hands)) {
    for (const hand of frames) hand[1] = lengthenLegs(hand[1]);
  }
}

export function drawActorArt(triangle, model, pose, clip, progress, loop = false, color = WHITE) {
  const frames = model.poses[clip];
  const cursor = loop ? (progress % 1) * frames.length : progress * (frames.length - 1);
  const index = Math.floor(cursor);
  const next = loop ? (index + 1) % frames.length : Math.min(index + 1, frames.length - 1);
  const blend = cursor - index;
  const first = frames[index];
  const second = frames[next];
  const sample = (from, to, offset) => (from[offset] + (to[offset] - from[offset]) * blend) * 0.001;
  const points = [];
  for (let offset = 0; offset < first.length; offset += 3) {
    points.push(pose(sample(first, second, offset), sample(first, second, offset + 1), sample(first, second, offset + 2)));
  }
  for (const { indices: [a, b, c], uv } of model.triangles) {
    triangle(points[a], points[b], points[c], color, 12, uv);
  }
  const hands = model.hands[clip];
  if (hands) return pose(sample(hands[index], hands[next], 0), sample(hands[index], hands[next], 1),
    sample(hands[index], hands[next], 2));
}
