/** Samples baked skinning clips and submits the pose to the existing island mesh. */
const WHITE = [1, 1, 1];

export function prepareActorArt(data) {
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
  return { triangles, poses: data.poses, hands: data.hands };
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
  if (hands) return pose(...hands[index].map((_, offset) => sample(hands[index], hands[next], offset)));
}
