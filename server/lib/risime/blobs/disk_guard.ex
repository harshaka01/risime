defmodule RisiMe.Blobs.DiskGuard do
  @moduledoc """
  The server-wide storage guard for blob uploads (contract v1.11 §14.5, decision 042). Server
  configuration, not protocol (`config :risime, :blob_guard`):

    * `media` → `507 storage_full` when free space minus in-flight upload bytes falls under
      **max(50 GiB, 10 % of the filesystem)** (`:media_min_free`, `:media_min_free_ratio`), or
      when the live `media` bytes of all users exceed **200 GiB** (`:media_max`, `BLOB_MEDIA_MAX`;
      the sum is cached for 60 s); v1.15 §17.9: `history` uploads are counted with `media`;
    * `mls`, `icon` and `avatar` (v1.17 §18.3) keep working down to **20 GiB** free
      (`:min_free`);
    * a warning log line (every poll) and a `/health` detail (`checks.blob_storage: "low"`)
      under **100 GiB** free (`:warn_free`).

  `df -Pk BLOB_DIR` runs every 30 s; the result is cached in `:persistent_term`. Until the first
  measurement (or if `df` fails) the free-space part of the guard doesn't refuse. Tests set
  `config :risime, :blob_disk, {free_bytes, total_bytes}` to override the measurement.
  """
  use GenServer

  import Ecto.Query

  require Logger

  alias RisiMe.Blobs.Slots
  alias RisiMe.Repo

  @gib 1024 * 1024 * 1024
  @poll_ms 30_000
  @defaults [
    media_min_free: 50 * @gib,
    media_min_free_ratio: 0.10,
    min_free: 20 * @gib,
    warn_free: 100 * @gib,
    media_max: 200 * @gib,
    media_total_cache_ms: 60_000
  ]
  @disk_key {__MODULE__, :disk}
  @media_key {__MODULE__, :media_total}

  def start_link(_opts), do: GenServer.start_link(__MODULE__, :ok, name: __MODULE__)

  @doc "The guard settings (defaults merged with `:blob_guard`)."
  def config, do: Keyword.merge(@defaults, Application.get_env(:risime, :blob_guard, []))

  @doc "`{free_bytes, total_bytes}` of the blob filesystem, or nil when not measured yet."
  def disk do
    Application.get_env(:risime, :blob_disk) || :persistent_term.get(@disk_key, nil)
  end

  @doc "`:ok` or `{:error, :storage_full}` for an upload of `size` bytes of `purpose`."
  def check(purpose, size) do
    c = config()

    free_ok =
      case disk() do
        nil ->
          true

        {free, total} ->
          left = free - Slots.inflight_bytes() - size

          min =
            if purpose in ["media", "history", "backup"],
              do: max(c[:media_min_free], trunc(total * c[:media_min_free_ratio])),
              else: c[:min_free]

          left >= min
      end

    cond do
      not free_ok ->
        {:error, :storage_full}

      purpose in ["media", "history", "backup"] and media_total(c) + size > c[:media_max] ->
        {:error, :storage_full}

      true ->
        :ok
    end
  end

  # The live `media` (and `history`) bytes of all users, cached for `:media_total_cache_ms`.
  defp media_total(c) do
    now = System.monotonic_time(:millisecond)
    ttl = c[:media_total_cache_ms]

    case :persistent_term.get(@media_key, nil) do
      {at, total} when now - at < ttl ->
        total

      _ ->
        total =
          Repo.one(
            from b in "blobs",
              where:
                b.purpose in ["media", "history", "backup"] and is_nil(b.deleted_at) and
                  (is_nil(b.expires_at) or b.expires_at > ^DateTime.utc_now()),
              select: coalesce(sum(b.size), 0)
          )
          |> then(fn
            %Decimal{} = d -> Decimal.to_integer(d)
            n -> n
          end)

        :persistent_term.put(@media_key, {now, total})
        total
    end
  end

  @doc "`/health` detail: \"ok\", \"low\" (under the warning level) or \"unknown\"."
  def health do
    case disk() do
      nil -> "unknown"
      {free, _} -> if free < config()[:warn_free], do: "low", else: "ok"
    end
  end

  ## Poller

  @impl true
  def init(:ok), do: {:ok, nil, {:continue, :poll}}

  @impl true
  def handle_continue(:poll, state), do: {:noreply, poll(state)}

  @impl true
  def handle_info(:poll, state), do: {:noreply, poll(state)}

  defp poll(state) do
    case measure(RisiMe.Blobs.blob_dir()) do
      {free, _total} = disk ->
        :persistent_term.put(@disk_key, disk)

        if free < config()[:warn_free],
          do: Logger.warning("blob storage low: #{div(free, @gib)} GiB free")

      nil ->
        :ok
    end

    Process.send_after(self(), :poll, @poll_ms)
    state
  end

  @doc false
  def measure(dir) do
    _ = File.mkdir_p(dir)

    with {out, 0} <- System.cmd("df", ["-Pk", dir], stderr_to_stdout: true),
         [_header, line | _] <- String.split(out, "\n", trim: true),
         [_fs, blocks, _used, avail | _] <- String.split(line),
         {blocks, ""} <- Integer.parse(blocks),
         {avail, ""} <- Integer.parse(avail) do
      {avail * 1024, blocks * 1024}
    else
      _ ->
        Logger.warning("blob storage: df failed for the blob directory")
        nil
    end
  rescue
    _ -> nil
  end
end
