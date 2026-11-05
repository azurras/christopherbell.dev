# Survive actor art

- Adventurer mesh, painted texture, and idle, walk, run and attack motions by
  Quaternius, released under CC0. [LowPoly RPG Characters](https://opengameart.org/content/lowpoly-rpg-characters)
- Boar mesh, painted hide texture and walk animation by Teh_Bucket, released
  under CC0. [Boar](https://opengameart.org/content/boar)

Survive adapts the meshes to island scale, combines the textures into one atlas,
and adds tool gestures and hand attachment points to the adventurer. The atlas
places the adventurer in the upper right and the boar in the lower left with a
six-pixel gutter. The JSON stores triangle vertex indices, per-corner atlas UVs
in 32767ths and animation poses in millimetres.
Blender evaluates the source armatures offline. The browser interpolates the
resulting vertex and hand positions.
