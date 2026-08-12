# Witchery Optimizer

Witchery Optimizer 0.2.2 is a **server-side-only** mod for Witchery 0.24.1 on Minecraft 1.7.10. It replaces permanent Poppet Shelf chunk tickets with authoritative, UUID-indexed `WorldSavedData`; clients do not need the mod.

## Safety and compatibility

- Stores complete nine-slot inventories, custom names, locations, stable observed order, versions and shelf UUIDs.
- Runs a read-only startup census over registered provider folders and sleeping `DIM<id>` save folders using Minecraft `RegionFile`/NBT APIs. It closes every stream, never requests chunks, never writes region files, and fails closed on unreadable, corrupt, ambiguous, escaping, malformed, or unmapped region folders.
- Ticket restoration is only a one-time drain/bootstrap hint: every restored ticket independently attempts import and release, is never retained or reacquired, and anomalies become persisted diagnostics rather than permanent blockers. Census proof alone enables lookup.
- Failed/interrupted initialization and census work retries automatically from server END ticks. Retry state survives restart with bounded exponential delays (transient: about 1s to 5m; corruption: about 30s to 1h), while lookup remains fail-closed until a complete durable census.
- Lookup preserves Witchery parity: player inventory and hunter-clothes behavior remain original; loaded shelves follow the exact `MinecraftServer.worldServers` array and each live `loadedTileEntityList` iterator order and use Witchery's private matcher. Sleeping worlds extend the persisted dimension order, with newly discovered dimensions appended numerically; records retain observation order.
- Dimension restriction keeps Witchery's numeric allowance: Overworld (0), Nether (-1), End (1), and configured Dream dimension. No personal dimension IDs are hardcoded.
- Generic NBT movers are deliberately unsupported. A UUID copied to another location is quarantined and its carried inventory is **not imported**; the authoritative source remains usable. Intentional player placement receives a new identity. This fail-closed policy avoids guessing move versus clone and guarantees no copied authoritative inventory.
- Removal WAL-persists a transaction UUID and full inventory before block mutation, then WAL-persists `RemovalDropsStarted` before Witchery's first loose drop is spawned. Every shelf-break `EntityItem` retains Witchery's original split/NBT/motion and receives the transaction UUID plus a deterministic ordinal in persistent Forge entity data. Startup census reads both tile and entity NBT: an untouched exact shelf with no started drops is restored; complete tagged drops terminally tombstone the shelf (including shelf-plus-drops); missing/incomplete evidence fails census and lookup closed. Reconciliation is replay-idempotent and census cannot complete while any prepared removal remains.
- Loaded shelves always serialize ordinary Witchery `Items`/`CustomName` NBT plus UUID. Unloaded consumption marks durable pending writeback; natural chunk load mirrors authoritative state and marks the chunk dirty; serialization embeds `WOWritebackVersion`, but pending clears only after a later natural region reload proves that exact authoritative version reached disk. Startup/shutdown logs report the exact pending count. Stale physical NBT cannot become authoritative while installed.

Clean uninstall is safe only when logs show no pending writeback and logs report zero pending writebacks after every changed shelf-owning chunk has saved, unloaded, and naturally reloaded to confirm the disk version. Keep a backup; uninstalling while pending mirrors exist can expose stale Witchery NBT.

Schema validation is strict. Missing schema, Schema 1, and schema-1 journal data produce an explicit unsupported-v0.1 startup failure and are never migrated, reset, or overwritten. A pure Witchery world with no optimizer data remains supported.

`TileEntity.onChunkUnload()V` is Forge-added and remains unobfuscated in production: the mixin target intentionally has `remap=false`. It has no SRG mapping; `remap=true` is invalid and rejected by the annotation processor. The required mixin configuration remains safe because the explicit descriptor is validated in the production artifact.

## Building

Place `witchery-1.7.10-0.24.1.jar` in `libs/`, use the project JDK 25, and run:

```text
gradlew.bat spotlessCheck check build validateProductionJar
```

The wrapper uses the repository-local `.gradle-user-home`. `validateProductionJar` checks the reobfuscated JAR manifest, mixin config/refmap/classes and the exact unobfuscated `onChunkUnload()V` target.

## License

GNU GPL v3.0. See [LICENSE](LICENSE).
