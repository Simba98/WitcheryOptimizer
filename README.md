# Witchery Optimizer

Witchery Optimizer is a **server-side-only** mod that replaces Witchery 0.24.1's permanently chunk-loaded Poppet Shelves and loaded-TileEntity scans with an authoritative UUID-indexed `WorldSavedData` inventory. It targets GT New Horizons 2.8.4 on Minecraft 1.7.10. Clients do not need the mod.

## Behavior

- Stores every shelf's complete nine-slot `ItemStack` inventory, custom name, location, stable order and persistent UUID.
- Searches `MinecraftServer.worldServers` in its original order, then shelves in stable creation order, without loading chunks.
- Uses Witchery's private inventory matcher through a Mixin invoker, preserving exact taglock, second-tag, damage, destruction and returned-stack behavior. Successful lookup is consumed and persisted immediately on the server thread.
- Suppresses new permanent tickets. On first installation over plain Witchery, restored Witchery shelf tickets load and import otherwise-unloaded shelf TEs before their tickets are released; every loaded shelf TE is also imported on attachment.
- Persists restart-safe plain-Witchery import state. A malformed ticket or a ticket that does not resolve to a shelf fails closed and disables optimized shelf lookup rather than risking item loss. Upgrading from WitcheryOptimizer v0.1 is intentionally unsupported.
- Writes idempotent post-state/removal operations to an fsynced, atomically replaced journal in the world directory before returning protection success or allowing shelf drops. Tombstones prevent stale chunk NBT from resurrecting broken identities.
- Keeps loaded tile entities synchronized and serializes the UUID plus authoritative contents into chunk/mover NBT.

For safety, an arriving shelf UUID at a different persisted location is treated as a clone and receives a new identity, even if the original chunk is unloaded; its self-contained TE inventory is imported instead of hijacking the original record. Automatic TE movement is unsupported in v0.2.0. A mover serializing the shelf at a different coordinate creates a fresh UUID and imports its carried inventory; the original authoritative identity is never relocated. Coordinate-only movers are likewise treated as break/new shelf.

When Witchery's restriction is enabled, allowed dimensions are resolved once from the actual server-world array: exact vanilla provider classes plus the configured Dream dimension. Subclassed replacement providers fail closed rather than broadening Witchery's intended set. No numeric vanilla dimension IDs are present in optimizer lookup code.

Minecraft override injections use MCP names with `remap=true`. Witchery-owned custom methods, fields and the private matcher have no MCP mappings and therefore necessarily use `remap=false`; forcing remapping would produce invalid runtime targets.

## Building

1. Obtain the original `witchery-1.7.10-0.24.1.jar` and place it in `libs/`.
2. Use JDK 25 to run the current GTNH Gradle convention plugin.
3. Run `./gradlew build` or `gradlew.bat build`. `check` also opens the exact reobfuscated production JAR and verifies its manifest, mixin JSON, refmap and listed classes.

The wrapper sets `GRADLE_USER_HOME` to `.gradle-user-home` inside the project unless it is already set, so builds do not create caches in the user's home directory. The resulting Java classes target Java 8.

The Witchery dependency is used only for local development and is excluded from source control and produced artifacts.

## License

Witchery Optimizer is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).

Plain-Witchery ticket import is fail-closed and persisted. If a corrupt or unresolved restored ticket marks import FAILED, repair or remove the corrupt Forge ticket data and reset the world's WitcheryImportState to UNKNOWN before restarting; optimizer shelf lookup remains disabled meanwhile.

Stale shelf NBT carrying a tombstoned UUID, or lacking an identity at a tombstoned location, is quarantined. Only a real server-side Forge player placement event authorizes one fresh identity at that coordinate.
