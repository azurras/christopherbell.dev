/** Reuse typed vertex storage and GPU capacity across frames. */
export function createDynamicMesh(gl, buffer) {
  let vertices = new Float32Array(0);
  let length = 0;
  let allocatedBytes = 0;
  return {
    append(x, y, z, red, green, blue, material, u, v) {
      if (length + 9 > vertices.length) {
        const expanded = new Float32Array(Math.max(4096, vertices.length * 2));
        expanded.set(vertices);
        vertices = expanded;
      }
      vertices[length++] = x;
      vertices[length++] = y;
      vertices[length++] = z;
      vertices[length++] = red;
      vertices[length++] = green;
      vertices[length++] = blue;
      vertices[length++] = material;
      vertices[length++] = u;
      vertices[length++] = v;
    },
    reset() { length = 0; },
    upload() {
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      if (allocatedBytes < vertices.byteLength) {
        allocatedBytes = vertices.byteLength;
        gl.bufferData(gl.ARRAY_BUFFER, allocatedBytes, gl.DYNAMIC_DRAW);
      }
      if (length) gl.bufferSubData(gl.ARRAY_BUFFER, 0, vertices.subarray(0, length));
      return length;
    },
  };
}
