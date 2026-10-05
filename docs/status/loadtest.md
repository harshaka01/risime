# Load test: realtime path (server)

The tool is `mix risime.loadtest` (decision 011). All runs were on spark2 (20 cores) on
2026-10-05, against a temporary dev-mode server from `main` on 127.0.0.1:4100
(`LOG_LEVEL=warning`), with the dev Postgres and Cassandra in Docker. The load generator ran on
the same machine.

Traffic: N users, each sending 1 DM per interval to a random user. Recipients ack
`delivered`, and `read` for every second message, so each message means about 2.5 pushes from
the client. Latencies are in ms.

## Standard run: 200 users, 1 msg/s each, 180 s (about 200 msg/s)
| | sent | errors | send→reply p50 / p95 / p99 / max | send→push p50 / p95 / p99 / max |
|---|---|---|---|---|
| before fixes | 35 947 | 0 | 4.54 / 5.95 / 6.70 / 29.3 | 4.54 / 5.97 / 6.85 / 29.2 |
| after fixes | 35 953 | 0 | 4.28 / 5.64 / 6.29 / 24.8 | 4.28 / 5.65 / 6.40 / 24.8 |

200 users is comfortable: no errors, no missing pushes, p99 under 7 ms. To find the
bottlenecks the load was pushed past the target:

## Stress runs
| run | load | errors | reply p50 / p95 / p99 | push p50 / p95 / p99 | notes |
|---|---|---|---|---|---|
| 1000 users × 1/s, 60 s, before | 996 msg/s | 0 | 2.9 / 3.9 / 4.5 | 3.0 / 4.0 / 4.6 | fine |
| 2000 users × 1/0.6 s, 60 s, **before** | 3230 msg/s | **81 421 `unmatched topic`**, 643 timeouts; 128 578 pushes missing | 2.9 / 5.0 / 172 | 2.9 / 27 / 256 | channels crashing |
| same, after fix 1 (pool 16 + overload retry) | 3212 msg/s | 53 193 timeouts, 230 rate_limited; 30 828 pushes missing | 2409 / 5482 / 8256 | 6436 / 14807 / 17207 | no crashes, but saturated (server beam about 10 cores) |
| 2000 users × 1/s, 50 s, after fix 1 | 1897 msg/s | 0 | 2.5 / 3.5 / 58.7 | 2.5 / 3.6 / 61.2 | |
| 2000 users × 1/s, 50 s, after fix 2 | about 1900 msg/s | 0 | 2.3 / 3.0 / 17.7 | 2.3 / 3.0 / 18.0 | |
| 2000 users × 1/0.6 s, 60 s, **after fixes 1 + 2** | 3238 msg/s | **0**; 0 pushes missing | 3.6 / 8.8 / 44.9 | 3.6 / 9.0 / 47.2 | |

## Bottlenecks found and fixed
1. **The Xandra pool fails instead of queueing.**
   - Problem: 4 connections × 100 in-flight requests. Past that, Xandra returns
     `:too_many_concurrent_requests` at once, `run!` raised, and the user's channel crashed.
     The client then got `unmatched topic` for every push until it rejoined.
   - Fix: Cassandra `pool_size` 4 → 16 (`CASSANDRA_POOL_SIZE` overrides it), and `run!` retries
     that error with a short jittered backoff (2–100 ms, 5 tries) before raising.
2. **Two trips through the Xandra cluster process per query.**
   - Problem: `Xandra.Cluster.prepare` and then `Xandra.Cluster.execute` each checked out a
     connection through the cluster process and ShardManager, the hottest serial processes on
     the node (sampled reductions).
   - Fix: one `Xandra.Cluster.run/2` per query, with prepare (from the connection's cache) and
     execute on that connection. This halved their reductions, and in the 3200 msg/s run took
     the server from saturated to p99 47 ms.

## Checked and not a bottleneck
- **Postgres per send** (`Accounts.get_user` for the recipient): the DBConnection pool's
  reductions were about 1/50 of Xandra's at 2000 msg/s, with no queueing, so we left it.
- **RateLimiter** (ETS `update_counter`, no process hop) and **PubSub** (local registry): neither
  shows up among the hot processes.
- **Presence GenServer:** called only on join and leave.

## Remaining limits
- Per-message cost is dominated by the channel processes themselves (JSON, Cassandra
  encode/decode) and by Cassandra's LWTs (`IF NOT EXISTS` on send for idempotency, `IF status =`
  on ack). Cassandra used about 2–4 cores at peak.
- The dev compose publishes Cassandra through `docker-proxy` (about 0.5–1.2 cores at peak).
  Host networking or a native port would remove that hop (infra, not done).
- Max latency stays at 25–300 ms under any load (GC/LWT tail).
- Reproduce: `PORT=4100 LOG_LEVEL=warning mix phx.server`, then
  `mix risime.loadtest --users 200 --duration 180 --interval 1000`.
