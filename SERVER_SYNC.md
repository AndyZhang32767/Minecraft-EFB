# EFB 0.7.0 server synchronization

## 中文快速说明

服务端和客户端使用同一个 `efb-0.7.0.jar`，安装 EFB 的一侧仍需 Fabric API。本版没有新增 Kotlin、YACL 等依赖。

- 服务端安装 EFB 后，未安装 EFB 的玩家仍可加入；安装 EFB 的玩家自动接收共享航图。
- 所有服务器 OP 均可创建、编辑和删除导航点、机场、跑道、程序及地图航路，服务器保存后自动同步。普通玩家可以查看这些数据并规划自己的航线。
- 当前飞行计划（含 DEP/ARR、VIA）与航段进度按玩家 UUID 独立保存，ND 显示设置保存在本机；Xaero 地形图不通过本协议分享。
- 服务端未安装 EFB 时，客户端自动使用原有本地航图。单人世界也保持本地模式，开放至局域网后启用共享模式。
- 共享数据位于世界存档的 `efb/` 目录。旧版客户端本地航图保留，不会自动上传或合并；新服务器的共享航图初始为空。
- 多个 OP 同时编辑时，过期修改会被拒绝并显示最新数据；请根据提示重新编辑。撤销 OP 权限会同步更新界面。
- 升级时用新 JAR 替换旧 EFB JAR，不要同时放入两个版本。

The same `efb-0.7.0.jar` works on Fabric clients and dedicated servers running Minecraft 1.21.11 and Java 21. Fabric Loader 0.18.4+ and Fabric API 0.141.4+1.21.11 remain required wherever EFB is installed. No additional library dependency was introduced.

| Server | Client | Behavior |
| --- | --- | --- |
| EFB 0.7.0 | EFB 0.7.0 | Automatic server chart download; OPs edit, other players view and plan flights |
| EFB 0.7.0 | No EFB | Normal connection and gameplay; no EFB interface required |
| No EFB | EFB 0.7.0 | Existing local chart editing and ND behavior |
| Singleplayer | EFB 0.7.0 | Existing local charts; publishing to LAN enables shared mode |

Replace an older EFB JAR instead of installing two EFB versions together. Xaero's World Map is optional and needed only on clients that want satellite imagery. The server does not need Xaero or any rendering library. Older EFB clients without the v6 protocol are treated as unsupported clients and remain local.

## Shared data and permissions

Navigation points, NDB/VOR/ILS transmitters, airports, runways, STAR-authored procedures and permanent map airways belong to the server. Each dimension has its own chart. The server accepts edits from players in its operator list, including lower OP levels; permission is checked again for every upload. Grant or revoke OP using the normal server tools. The UI notices permission changes within about one second.

Flight plans (including DEP/ARR and VIA), the selected ND route, navigation progress and both radio receivers remain personal. Shared packets omit radio tuning entirely; non-OP players can tune both receivers. Station coordinates, channels, coverage and VOR/ILS parameters are public chart data. Client chart caches are scoped by player UUID as well as server/world and dimension; ND display settings remain local to the game installation. Ordinary players can create, reorder, reset and delete their own flight plans using shared points. Removing a shared point removes its dependent runway/airway links and invalid references in each client's personal plans.

Joining, switching dimensions and reconnecting fetch the current chart. Accepted edits are saved before being broadcast to compatible players in that dimension. Players in other dimensions receive the latest chart when they return. Clients without the EFB receiving channel receive no chart packets and are never required to install EFB.

The chart header shows local, connecting, server view, OP edit or saving status. Shared edit controls are unavailable during a pending save. Concurrent edits use a server revision: a stale submission is rejected and the current chart is restored with a message. Reopen an editor after another OP updates its data; stale forms and deletion confirmations cannot overwrite newer data.

## Storage and migration

Version 0.7.0 uses protocol v6: update both server and clients. On first disk read, schema v1-v5 charts automatically upgrade to schema v6. Public navigation IDs and geometry are preserved. Personal DEP/ARR airport anchors become the selected runway thresholds; an airport with no selected runway remains metadata only. Enroute fixes, gaps and surviving navigation targets are preserved. A permanent chart.v1/v2/v3/v4/v5.json.bak is kept before rewriting. Rejoining fetches the current server snapshot automatically.

Runways are managed under their airport, and slash-separated runway ends retain suffixes (for example 27L and 09R). Procedures are authored in inbound STAR order; departure SID reverses that sequence and selects the opposite runway end. There is no APP selector. One-way airways follow the stored fix order and appear blue with direction arrows.

Private plans retain runway/procedure selections and discontinuities. Removing a plan leg leaves a gap; "+" inserts at the gap, "X" explicitly connects by DIR, and the tail "+" restores ordinary appending. Shared procedure edits invalidate affected private terminal selections and leave gaps. Plan data never appears in chart uploads.

Legacy client plans without an account identity are claimed by the first account loading that cache. A persistent `plans-owner.txt` prevents other accounts on that installation from inheriting them. Navigation data can still be used by each account. Neither migration nor mode switching uploads plans.

Server data is stored inside the world save:

```text
<world>/efb/
  server-id.txt
  charts/<shared identity hash>/<dimension hash>/
    chart.json
    chart.json.bak
```

Back up the entire `efb` directory with the world. The persistent server ID separates personal plans when a server replaces its world at the same address. Do not delete `server-id.txt` during ordinary upgrades.

Local-only charts and synchronized personal-plan caches use separate client identities. Existing local charts are preserved and are **not automatically uploaded or merged** into a server's authoritative chart. A new server starts with an empty shared chart. OPs can create the server's points through N → Waypoint / Airport (runways and procedures) / Airways.

Unreadable or unsupported server chart files stay untouched and make that dimension read-only. Cached points and personal plans are retained while server storage is unavailable. Check the server log and restore a backup while the server is stopped. A failed write does not publish an unsaved change.

## Map imagery and limits

Synchronization covers EFB chart annotations, not Xaero terrain files or explored regions. Each client continues to use its own Xaero map. Satellite imagery still requires that client to have explored or obtained the relevant Xaero map.

A shared dimension supports up to 2,048 points, 2,048 runways, 4,096 airways, 4,096 procedures and 512 KiB of serialized chart data. Transfers use bounded 20 KiB fragments with a timeout. The first version sends full chart snapshots after edits; it is designed for small and medium shared charts, not continuous bulk imports.

## Verification

`gradlew.bat build runClientGameTest` runs unit tests and real Fabric packet round trips on a temporary LAN-published integrated server. Tests cover OP writes, permission revocation, forged non-OP uploads, stale revisions, dimension switching, private flight plans, optional channel negotiation, storage failures and bounded transfers. The same retained test also verifies transmitter sidebar edits, non-OP tuning, reception and compact RAD/NAV scrolling. These are automated local checks; a multi-machine public-server soak test is separate.

Procedures retain inbound STAR order in schema v6. Older outbound SID procedures are reversed during migration, including the runway end, while retaining the private enroute sequence and migrating airport anchors to runway thresholds. Xaero integration is read-only and never schedules loads, refreshes regions, changes settings or writes Xaero files.
