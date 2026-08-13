# Witchery Optimizer 0.3.0

A server-only mod for GTNH 2.8.4, Minecraft 1.7.10, Forge 10.13.4.1614, and Witchery 0.24.1.

## Architecture

WorldSavedData plus its fsynced write-ahead log is the sole logical Poppet Shelf inventory. Witchery tile entities are ordinary-NBT IO mirrors. Mutations and consumption commit WAL-first. Witchery's original inventory matcher operates on authoritative nine-slot adapters, preserving matching, consumption, and shelf order.

The replacement Witchery ordered ticket callback strictly validates `poppetX/Y/Z`, synchronously loads and verifies the exact block and TE, and retains only processable tickets. Its ordinary callback imports physical inventory into a new authority WAL-first, assigns identity, mirrors and marks dirty, then releases every retained live ticket. Lookups open after successful storage initialization on the first END tick. Later world callbacks remain synchronous. No scan, hardcoded dimension, or persistent completion gate exists.

Loaded consumption commits authority, immediately mirrors, and marks dirty without a queue or ticket. Unloaded consumption persists `WritebackPending`. Up to eight independent NORMAL optimizer tickets use depth one and force one exact chunk. Expiry is checked on main-thread safe opportunities using both monotonic five seconds and a 100-tick cap. Under server lag, wall time can exceed five seconds because release occurs on the next server tick. Completion, expiry, disappearance, world unload, and server stop explicitly unforce/release. Restored optimizer tickets are rejected before becoming live; pending authority flags reconstruct jobs.

## Crash, removal, and compatibility

Authority consumption remains final if physical synchronization fails; a durable repair is queued. A crash after TE mirror acknowledgement but before chunk save may leave older physical NBT, but authority overwrites it on the next load, so no logical duplication occurs. If WAL-first shelf creation survives a crash before its TE identity saves, the identity-less TE at that exact location is assigned the existing authority UUID and overwritten from authority; its physical contents are never imported. Shelf replacement is intercepted before World mutation: authority is WAL-tombstoned before Witchery's original break/drop routine runs against the authoritative mirror. A WAL failure cancels the entire block change. Snapshot-captured block placement and multi-place operations cannot replace a Poppet Shelf, avoiding irreversible drops or tombstones before Forge resolves placement cancellation. Snapshot restoration never tombstones an authority. Copied identity at another location is emptied and never imported.

Removing the mod leaves the most recently saved ordinary Witchery `Items` and `CustomName`. An acknowledged mirror not yet saved at a crash may be older. No client installation is required.

## Building

Use the repository-local toolchain:

```text
GRADLE_USER_HOME=.gradle-user-home
JAVA_HOME=.jdk\jdk-25.0.4+7
gradlew.bat clean spotlessCheck checkstyleMain checkstyleTest test build validateProductionJar --rerun-tasks --no-daemon --no-configuration-cache --console=plain
```

Output targets Java 8 bytecode.

## License

GNU General Public License v3.0. See `LICENSE`.
