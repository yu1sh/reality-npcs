# Reality NPCs

`reality-npcs` is a server-authoritative Forge 1.20.1 Mod for one fixed NPC
template: the server-owned `guide`. It is an independent repository and does
not depend on Claims, Parties, Economy, Identity, Permissions, or any other
project Mod.

## Fixed v1 contract

- Java 17, Gradle 8.8, Forge 47.4.10, `mod_id=reality_npcs`, and package
  `io.github.yu1sh.reality.npcs`.
- Every guide has a generated stable ID, entity UUID, dimension, fixed spawn
  anchor, and enabled state in the `reality_npcs` world `SavedData`.
- A guide is a vanilla Villager configured as a non-combat, no-AI guide. Its
  trade offers are empty, player interaction is cancelled with a chat notice,
  and it has no player-facing custom UI, reward, inventory, combat, or owner
  permission. The operator administrator GUI is described below.
- Server enforcement runs every 20 ticks. A guide outside 16 blocks from its
  saved anchor is returned to the nearest safe supported position within that
  radius. Dimension travel is cancelled.
- Active guides are limited to 32 per server and 16 per dimension. The limits
  are checked before every spawn or recreate and disabled records do not count
  as active guides.
- Each accepted operator mutation attempt from a command or the administrator
  GUI is rate-limited to one attempt per 30 seconds per player UUID or
  `console`. A failed spawn/recreate attempt from either path also consumes
  the interval. There is no override or emergency budget.
- Player operators must have vanilla permission level 2 or higher. The only
  non-player principal accepted is the server console; command blocks and
  other sources fail closed.
- Administrative operations are audited in append-only JSON Lines at
  `logs/reality_npcs/audit.jsonl`. The log records timestamp, actor, action,
  stable ID, world/dimension, reason, and result. The parent directories may be
  created, no automatic deletion or rotation purge is implemented, and the
  minimum retention requirement is 180 days.
- On restart, records are restored from world `SavedData`. If an enabled guide
  entity or its dimension is missing, the record is disabled and a `restore`
  audit entry is written. A disabled record remains available for inspection
  and `recreate`.
- There is no world-reset special operation, direct data-editing path, manager
  role, player request path, or Claims/Parties/Economy integration.

## Commands

Player operator:

```text
/realitynpcs spawn guide
/realitynpcs gui
/realitynpcs list
/realitynpcs disable <stable-id>
/realitynpcs delete <stable-id>
/realitynpcs recreate <stable-id>
```

The player command uses the player's current position and dimension as the
anchor. Console spawn requires an explicit dimension and integer coordinates:

```text
/realitynpcs spawn guide <dimension> <x> <y> <z>
```

For example, `minecraft:overworld 0 64 0`. Console `list`, `disable`,
`delete`, and `recreate` use the persisted record; no implicit cross-dimension
movement is performed.

## Command / GUI parity

`/realitynpcs gui` opens the server-owned administrator menu for a
permission-level-2+ operator player. The menu receives an authoritative
snapshot containing every persisted guide's stable ID, enabled state, dimension,
anchor, entity UUID, entity presence, and current SavedData revision. The client
presents list/detail controls for Spawn guide and Refresh, plus Disable, Delete,
and Recreate for a selected record. It provides the following equivalent
operations:

| Existing command | GUI list/detail operation | Server validation path |
| --- | --- | --- |
| `spawn guide` | Spawn guide | Current server player position/dimension, permission, session/request identity, revision, caps, rate limit, safe position, entity add, persistence, audit |
| `list` | Snapshot / Refresh | Current server SavedData, permission, audit; no client-owned list state |
| `disable <stable-id>` | Disable selected record | Server-resolved stable ID, current record state, permission, session/request identity, revision, dimension/entity lookup, persistence, audit |
| `delete <stable-id>` | Delete selected record | Server-resolved stable ID, permission, session/request identity, revision, dimension/entity lookup, persistence, audit |
| `recreate <stable-id>` | Recreate selected record | Server-resolved stable ID, current record state, permission, session/request identity, revision, caps, dimension/entity lookup, safe position, persistence, audit |

GUI request packets do not carry an authoritative actor, position,
world/dimension, NPC state, or operation proof. The server obtains the actor
from the network sender. Spawn uses the sender's current server level and block
position, while selected-record operations re-resolve the current SavedData
record and its stored dimension. A refresh rebuilds the snapshot from current
server SavedData. Requests are accepted only for the server-issued menu session
with a strictly increasing request ID; duplicate/replayed or out-of-order
requests are rejected and audited, while requests for another menu session are
ignored. The client revision is only a stale-snapshot hint; before any NPC state
mutation, the server requires it to match the current SavedData revision.
Commands remain available as the management and automation fallback.

## Build and validation

```text
./gradlew --version
./gradlew clean compileJava
```

The implementation task intentionally does not include GameTest, broad test
expansion, server smoke, or real-play validation. Real play has not been
performed.

## License

Apache-2.0. See [LICENSE](LICENSE).
