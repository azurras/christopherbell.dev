/** Upload original material tiles and alpha foliage with neutral placeholders during loading. */
export function createIslandMaterials(gl, documentRoot, program) {
  const definitions = [
    ['grassTile', [0, 0]], ['earthTile', [1, 0]], ['stoneTile', [0, 1]], ['barkTile', [1, 1]], ['foliageCard', null],
    ['actorAtlas', null], ['authoredActorAtlas', null],
  ];
  const textures = [];
  const images = [];

  function bindTexture(index) {
    gl.activeTexture(gl.TEXTURE0 + index);
    gl.bindTexture(gl.TEXTURE_2D, textures[index]);
  }

  function dispose() {
    for (const image of images) image.onload = null;
    for (const texture of textures) gl.deleteTexture(texture);
  }

  try {
    gl.useProgram(program);
    definitions.forEach(([name, tile], index) => {
      textures.push(gl.createTexture());
      bindTexture(index);
      const pixel = name === 'foliageCard' ? [0, 0, 0, 0] : [115, 109, 86, 255];
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(pixel));
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, tile ? gl.REPEAT : gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, tile ? gl.REPEAT : gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.uniform1i(gl.getUniformLocation(program, name), index);
    });

    function upload(image, index, tile, tileSize = tile ? 512 : 256) {
      const canvas = documentRoot.createElement('canvas');
      canvas.width = tileSize;
      canvas.height = tileSize;
      const paint = canvas.getContext('2d');
      if (!paint) return;
      if (tile) {
        const width = Math.floor(image.width / 2);
        const height = Math.floor(image.height / 2);
        paint.drawImage(image, tile[0] * width, tile[1] * height, width, height, 0, 0, tileSize, tileSize);
      } else {
        paint.drawImage(image, 0, 0, tileSize, tileSize);
      }
      bindTexture(index);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, canvas);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    }

    function load(path, onLoad) {
      const image = new documentRoot.defaultView.Image();
      images.push(image);
      image.onload = () => { if (!gl.isContextLost()) onLoad(image); };
      image.src = path;
    }

    load('/images/survive/island-materials.jpg', image => {
      definitions.forEach(([, tile], index) => { if (tile) upload(image, index, tile); });
    });
    load('/images/survive/foliage.png', image => upload(image, 4, null));
    load('/images/survive/actor-materials.jpg', image => upload(image, 5, null, 1024));
    let authoredActorsReady = false;
    load('/images/survive/authored-actors.jpg', image => {
      upload(image, 6, null, 1024);
      authoredActorsReady = true;
    });
    return { dispose, get authoredActorsReady() { return authoredActorsReady; } };
  } catch (error) {
    dispose();
    throw error;
  }
}
