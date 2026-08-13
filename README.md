# Witchery Optimizer

Witchery Optimizer 0.2.4 is a **server-side-only** mod for Witchery 0.24.1 on Minecraft 1.7.10. It replaces permanent Poppet Shelf chunk tickets with authoritative, UUID-indexed `WorldSavedData`; clients do not need the mod.

## Safety and compatibility

- Stores complete nine-slot inventories, custom names, locations, stable observed order, versions and shelf UUIDs.
- Performs synchronous startup validation only for existing authoritative records, loading each record's exact chunk through registered dimensions and mirroring authority only to a Shelf with the exact persistent UUID. Unregistered or unavailable dimensions, unverifiable or failed disk loads, and mismatches in preloaded chunks remain fail-closed for retry. A missing block, TE, or UUID is durably tombstoned only after this validation has positively proved that the original persisted chunk was read. There is no full-world or region census; never-indexed shelves may remain absent until a ticket callback or natural TE attach imports them.
- Ticket restoration is only a one-time drain/bootstrap hint: every restored ticket independently attempts import and release, is never retained or reacquired, and anomalies become persisted diagnostics rather than permanent blockers. Startup authority-validation proof enables lookup.
- Corrupt persistence or failed WAL writes keep initialization and lookup fail-closed. The v0.2.4 validation marker is distinct from v0.2.3 census completion.
- Server logs report initialization, ticket drain, authority-validation counts/deletions, and pending writebacks without per-tick spam.
- Lookup preserves Witchery parity: player inventory and hunter-clothes behavior remain original; loaded shelves follow the exact `MinecraftServer.worldServers` array and each live `loadedTileEntityList` iterator order and use Witchery's private matcher. Sleeping worlds extend the persisted dimension order, with newly discovered dimensions appended numerically; records retain observation order.
- Dimension restriction keeps Witchery's numeric allowance: Overworld (0), Nether (-1), End (1), and configured Dream dimension. No personal dimension IDs are hardcoded.
- Generic NBT movers are deliberately unsupported. A UUID copied to another location is quarantined and its carried inventory is **not imported**; the authoritative source remains usable. Intentional player placement receives a new identity. This fail-closed policy avoids guessing move versus clone and guarantees no copied authoritative inventory.
- Shelf removal uses at-most-once semantics: an immutable authoritative snapshot is taken and the authority is durably WAL-tombstoned before Witchery can emit an inventory entity. Witchery's original `breakBlock` then reads that snapshot and retains its original slot iteration, stack splitting, randomization, entity construction/spawn, block-item behavior, and side effects. Spawned entities are immediately vanilla-owned. A crash after deletion may lose items but can never replay or duplicate them. Legacy interrupted removal records are tombstoned without drops at startup.
- Rejected cloned, conflicting, or tombstoned physical shelves remain removable through an exact cleanup context: Witchery keeps its normal block-removal behavior, but inventory slot reads return empty so unauthorized copied contents cannot drop. Legitimate authority elsewhere is untouched.
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
