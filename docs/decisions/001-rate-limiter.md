# 001 — Rate limiting: small ETS limiter, not Hammer

**Context.** Release 0.1 needs two limits: 3 OTP requests per phone per 15 minutes, and
20 messages per user per 10 seconds. There is one server node.

**Decision.** `RisiMe.RateLimiter` (server/lib/risime/rate_limiter.ex), about 50 lines:
- one public ETS table, written with atomic `:ets.update_counter/4`, so there is no process
  bottleneck on the hot path;
- a sliding-window counter: the current fixed window, plus the previous window weighted by
  how much of it still overlaps. This avoids the 2x burst at fixed-window edges;
- a sweeper that runs every minute and deletes expired windows.

**Consequences.**
- Counts are per node and lost on restart. That is fine for one node.
- When we run more than one node, move the limiter to Hammer with a Redis backend, or to
  Postgres. The `hit/4` API stays the same.
- No new dependency.
