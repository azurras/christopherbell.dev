# Survive actor art

- Adventurer mesh, armor and skin textures, and idle, walk and spear motions by
  mujtaba-io, released under CC0. [Medieval Soldier](https://opengameart.org/content/low-poly-psx-style-medieval-soldier-rigged-animated)
- Boar mesh, painted hide texture and walk animation by Teh_Bucket, released
  under CC0. [Boar](https://opengameart.org/content/boar)

Survive adapts the meshes to island scale, combines the textures into one atlas,
and adds tool gestures and hand attachment points to the adventurer. The atlas
places armor, skin, boar and untextured surfaces in reading order with a six-pixel
gutter. The JSON stores triangle vertex indices, per-corner atlas UVs in
32767ths, per-triangle material indices and animation poses in millimetres.
Blender evaluates the source armatures offline. The browser interpolates the
resulting vertex and hand positions.
