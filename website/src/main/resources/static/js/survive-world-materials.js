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
  // Blend color across the skin before tracing the sharper eyes and lips.
  for (const [x, y, radius, color] of [
    [695, 396, 12, 'rgba(154, 82, 66, 0.16)'],
    [661, 386, 15, 'rgba(125, 73, 51, 0.23)'],
    [702, 394, 6, 'rgba(246, 188, 147, 0.33)'],
  ]) {
    const wash = paint.createRadialGradient(x, y, 0, x, y, radius);
    wash.addColorStop(0, color);
    wash.addColorStop(1, 'transparent');
    paint.fillStyle = wash;
    paint.fillRect(x - radius, y - radius, radius * 2, radius * 2);
  }
  const layers = [
    // The upper lid and iris provide the strongest marks near the eye socket.
    ['rgba(103, 63, 48, 0.33)', 'M721 385 Q726 391 717 404 Q709 400 711 392 Z'],
    ['#604538', 'M719.8 387.2 Q722.1 393.4 714.4 400.9 Q711.2 396 719.8 387.2 Z'],
    ['#c7baa5', 'M719 389.2 Q719.7 394.1 714.5 399.2 Q713.7 394.7 719 389.2 Z'],
    ['#443a2e', 'M717.6 391.2 Q719.5 393.6 716.1 397 Q714.3 395.6 717.6 391.2 Z'],
    ['#e9d6bd', 'M718 392.1 Q718.6 392.7 717.7 393.6 Q716.9 393.4 718 392.1 Z'],
    ['#5c4233', 'M724.6 385.6 Q727.7 392.4 721.8 401.8 L720.4 401.5 Q723.2 394.2 722.1 388 Z'],
    // The center seam mirrors at the nose and the lips.
    ['rgba(119, 69, 52, 0.59)', 'M706.1 375 Q708.6 378.8 705.7 381.4 Q704.4 379.3 706.1 375 Z'],
    ['rgba(143, 81, 64, 0.65)', 'M696.7 374.2 Q698.5 378.1 695.2 384.3 Q694.2 377.3 695.6 374.2 Z'],
    ['rgba(88, 49, 40, 0.66)', 'M694.7 374.2 Q696 380.2 694.3 385 Q693.7 380.2 694.0 374.2 Z'],
    ['rgba(240, 165, 134, 0.62)', 'M692.8 374.2 Q694 378.8 692.2 382.3 Q691 377.6 691.8 374.2 Z'],
  ];
  for (const [color, outline] of layers) {
    paint.fillStyle = color;
    paint.fill(new Path2D(outline));
  }
}
