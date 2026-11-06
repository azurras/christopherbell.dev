# Survive actor art

- Adventurer mesh, painted texture, and idle, walk, run and attack motions by
  Quaternius, released under CC0. [LowPoly RPG Characters](https://opengameart.org/content/lowpoly-rpg-characters)
- Boar mesh, painted hide texture and walk animation by Teh_Bucket, released
  under CC0. [Boar](https://opengameart.org/content/boar)
- Wolf mesh, painted markings, and idle and walk animations by tomek, used
  under CC0. [3D Wolf Animation](https://opengameart.org/content/3d-wolf-animation-for-game)
- Bear mesh and painted hide by Phelippeau Rudy, released under CC0. Survive
  recolors the hide and adds breathing and paw movement. [White Bear](https://lpc.opengameart.org/content/white-bear-low-poly)

Survive adapts the meshes to island scale, combines the textures into one atlas,
and adds tool gestures and hand attachment points to the adventurer. The atlas
places the wolf, adventurer, boar and bear in reading order with a six-pixel
gutter. The JSON stores triangle vertex indices, per-corner atlas UVs
in 32767ths and animation poses in millimetres.
Blender evaluates the source armatures offline. The browser refines the
adventurer's head and leg proportions once on load, then interpolates the
resulting vertex and hand positions. The keeper adds Survive's mantle and cap.
