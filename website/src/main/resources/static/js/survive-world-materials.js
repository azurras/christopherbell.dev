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

    function upload(image, index, tile, tileSize = tile ? 512 : 256, paintDetail) {
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
      paintDetail?.(paint);
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
      upload(image, 6, null, 1024, paint => paintAdventurerFace(paint, documentRoot.defaultView.Path2D));
      authoredActorsReady = true;
    });
    return { dispose, get authoredActorsReady() { return authoredActorsReady; } };
  } catch (error) {
    dispose();
    throw error;
  }
}

/** Paint landmarks on the mirrored skin chart of the 1024 px authored adventurer atlas. */
function paintAdventurerFace(paint, Path2D) {
  const layers = [
    // Socket shadow and warm lower lid follow the tilt of the projected eye.
    ['rgba(103, 63, 48, 0.29)', 'M721 386 Q724 393 715 403 Q709 398 713 391 Z'],
    ['#695044', 'M719.4 388.5 Q720.5 393.7 714.1 400.4 Q712.5 395.4 719.4 388.5 Z'],
    ['#c9b6a1', 'M718.9 390 Q718.8 394.2 714.6 398.6 Q714.2 394.8 718.9 390 Z'],
    ['#493e32', 'M717.7 391.8 Q719.3 393.6 716.4 396.6 Q714.8 395.5 717.7 391.8 Z'],
    ['#634735', 'M724.2 388 Q725.8 394 721 402 L719.6 401 Q722 395 722.2 390 Z'],
    // Nose and lip contours stay softer than the eyes at gameplay distance.
    ['rgba(121, 71, 54, 0.35)', 'M709.8 377 Q711.5 381.1 708.2 384 Q708.8 380.1 709.8 377 Z'],
    ['rgba(117, 64, 55, 0.65)', 'M701.8 375.2 Q703.4 380.5 700.7 385.5 Q699.5 382.5 700.7 378.8 Z'],
    ['rgba(240, 164, 133, 0.55)', 'M699.5 376 Q700.1 380.4 699 383 Q697.8 379.1 699.5 376 Z'],
  ];
  for (const [color, outline] of layers) {
    paint.fillStyle = color;
    paint.fill(new Path2D(outline));
  }
}
