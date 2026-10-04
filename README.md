# SkullTaker

Server-side Fabric mod: when a player kills another player, the victim drops their head, with "Killed by <killer>" and the date on it. Vanilla clients can join.

## Commands

- `/skulltaker` – opens a leaderboard chest of player heads, sorted by kills, with pages
- `/skulltaker <player>` – shows one player's kills and deaths
- `/skulltaker reset [player]` – clears everyone's stats, or one player's (op level 2)

Stats are saved in `skulltaker.json` in the world folder.

## Requirements

Minecraft 26.1+, Fabric Loader 0.19.5+, Fabric API.
