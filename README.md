# Witchery Optimizer

Witchery Optimizer is a **server-side-only** mod that replaces Witchery 0.24.1's permanently chunk-loaded Poppet Shelves and global loaded-TileEntity scans with a UUID-indexed, world-persistent cache. It targets GT New Horizons 2.8.4 on Minecraft 1.7.10. Install it only on the dedicated server; clients do not need the mod and may connect without it.

## Behavior

- Migrates Poppet Shelves encountered through Witchery's restored Forge chunk tickets.
- Releases the shelves' permanent tickets after indexing their inventories.
- Preserves Witchery's handheld-poppet priority and protection checks.
- Resolves Witchery's player-name taglocks to UUIDs through the server profile cache.
- Reserves a cached poppet immediately in the event path without loading its chunk.
- Persists pending consumption before loading the shelf asynchronously from the event's perspective.
- Loads at most one pending shelf per server tick, validates the live stack, applies the consumption, and releases the temporary ticket.

Minecraft world and TileEntity access remains on the server thread. "Asynchronous" here means that protection-event handling is non-blocking; reconciliation is deferred to later server ticks rather than performed by an unsafe worker thread.

## Building

1. Obtain the original `witchery-1.7.10-0.24.1.jar` and place it in `libs/`.
2. Use JDK 25 to run the current GTNH Gradle convention plugin.
3. Run `./gradlew build` or `gradlew.bat build`.

The wrapper sets `GRADLE_USER_HOME` to `.gradle-user-home` inside the project unless it is already set, so builds do not create caches in the user's home directory. The resulting Java classes target Java 8.

The Witchery dependency is used only for local development and is excluded from source control and produced artifacts.

## License

Witchery Optimizer is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
