# TerrainLab development

- Target Paper 26.3, Java 25, Maven. Build with `mvn clean package`.
- Collection captures terrain only: never spawn NPCs/TNT or record entity data in source worlds or samples. The preview sandbox may spawn TNT only through the explicit `/terrain tnt` command; nothing from the sandbox is saved.
- Core observations are 25 x 25 x 11 (x,z,y). Serialized axis order is y,z,x.
- Preserve raw BlockData, biome labels, original coordinates, seeds as decimal strings, and snapshot schema versions.
- A larger context is captured separately; its sufficiency for future explosion/pathfinding experiments is not proven.
- Call world/entity APIs on the server thread. Copy data before background compression and file IO.
- Bound searches, chunk-loading concurrency and reads/writes per tick; report shortfalls without fabricating terrain.
- Keep generated source worlds and preview worlds separate; verify ownership and seeds before loading an existing world.
- Do not include player data, world saves, credentials, generated samples or build output in Git commits.
- Report successful compilation, data-format tests, and actual Paper runtime testing separately.
