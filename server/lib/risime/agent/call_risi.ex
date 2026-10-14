defmodule RisiMe.Agent.CallRisi do
  @moduledoc """
  "Call Risi" (decision 090, proposal `2026-10-14-call-risi`): a voice call in the caller's own Risi
  chat that Risi answers. Owned by the **Voice agent** (`RisiMe.Agent.CallRisi.Session` does the
  turn-taking); the media (LiveKit, echo cancellation, RNNoise, VAD, Kokoro) is infrastructure in the
  `risi-voice` worker and `risime-tts`.

  * **Switch:** `RISI_CALL_RISI` = `off` | `canary` | `on` (`RisiMe.Agents.Flags.feature/1`, live in
    5 s; `canary` = `RISI_DEV_USERS`, Harsha first). Default `off`: nothing changes for any call.
  * **Start:** `RisiMe.Calls.Rooms` calls `check/3` before it creates the room and `start/1` after.
  * At most `RISI_CALL_RISI_MAX` (default 2) calls at once, each at most `max_s/0` (the admin setting
    `call_risi_max_s`, default 30 minutes, 60–3600 s; contract v1.48 §41.6).
  * The transcript goes to the Risi chat at the end (`risi_call_transcript`); timings go to
    `risi_agent_runs` (agent `voice_call`) and the scoreboard; text and audio are never logged.
  """
  require Logger

  alias RisiMe.Agent.CallRisi.Session
  alias RisiMe.Agents.Flags

  @max_s 1_800
  @flag "RISI_CALL_RISI"

  defmodule Supervisor do
    @moduledoc "The registry and dynamic supervisor of Call Risi sessions (under `RisiMe.Agents.Supervisor`)."
    use Elixir.Supervisor

    def start_link(opts \\ []),
      do: Elixir.Supervisor.start_link(__MODULE__, opts, name: __MODULE__)

    @impl true
    def init(_opts) do
      Elixir.Supervisor.init(
        [
          {Registry, keys: :unique, name: RisiMe.Agent.CallRisi.Registry},
          {DynamicSupervisor, name: RisiMe.Agent.CallRisi.Sessions, strategy: :one_for_one}
        ],
        strategy: :one_for_all
      )
    end
  end

  @doc "The switch's name."
  def flag, do: @flag

  @doc "True when Call Risi applies to `user_id` (`on`, or `canary` and a dev user)."
  def enabled_for?(user_id) when is_binary(user_id) do
    not Flags.legacy?() and Flags.feature_for(@flag, user_id)
  rescue
    _ -> false
  end

  def enabled_for?(_), do: false

  @doc "The mode label for the runs table (`canary` | `on` | `off`)."
  def mode_label, do: Flags.feature(@flag) |> Atom.to_string()

  @max_s_key "call_risi_max_s"
  @max_s_min 60
  @max_s_max 3_600

  @doc "The admin setting of the call length limit (§41.6)."
  def max_s_key, do: @max_s_key

  @doc "The bounds of `max_s/0` in seconds: `{60, 3600}`."
  def max_s_bounds, do: {@max_s_min, @max_s_max}

  @doc """
  The maximum call length in seconds (contract v1.48 §41.6): the admin setting `call_risi_max_s`
  (`RisiMe.Admin.Settings`: table → env `RISI_CALL_RISI_MAX_S` → the default, live within 5 s), default
  1800 (`config :risime, :risi_call_risi_config, max_s:` may change the default). Always within 60–3600:
  a value outside is clamped, a value that isn't a number is ignored.
  """
  def max_s do
    default = Keyword.get(config(), :max_s, @max_s)

    with :error <- parse_s(RisiMe.Admin.Settings.get(@max_s_key)),
         :error <- parse_s(default) do
      @max_s
    else
      {:ok, n} -> n |> max(@max_s_min) |> min(@max_s_max)
    end
  rescue
    _ -> @max_s
  end

  @doc "Where `max_s/0` comes from: `:table`, `:env` or `:default`."
  def max_s_source do
    RisiMe.Admin.Settings.source(@max_s_key)
  rescue
    _ -> :default
  end

  @doc """
  Sets the call length limit from the admin console (audit-logged by `by`). `:ok`, or
  `{:error, :out_of_range}` / `{:error, :invalid}` (nothing saved).
  """
  def put_max_s(value, by) do
    case parse_s(value) do
      {:ok, n} when n >= @max_s_min and n <= @max_s_max ->
        RisiMe.Admin.Settings.put(@max_s_key, Integer.to_string(n), by)

      {:ok, _} ->
        {:error, :out_of_range}

      :error ->
        {:error, :invalid}
    end
  end

  @doc "Back to the environment/default (audit-logged)."
  def reset_max_s(by), do: RisiMe.Admin.Settings.clear(@max_s_key, by)

  defp parse_s(n) when is_integer(n), do: {:ok, n}

  defp parse_s(s) when is_binary(s) do
    case Integer.parse(String.trim(s)) do
      {n, ""} -> {:ok, n}
      _ -> :error
    end
  end

  defp parse_s(_), do: :error

  @doc "The configuration (`config :risime, :risi_call_risi_config`)."
  def config, do: Application.get_env(:risime, :risi_call_risi_config, [])

  @doc "The runs recorder (tests may replace it)."
  def runs, do: Application.get_env(:risime, :risi_call_runs, RisiMe.Agents.Runs)

  @doc """
  The Kokoro voice to speak `lang` with: the admin console's manual choice (`TTS.voice_for/1`) when it
  is a Kokoro voice, else the engine's default (Auto keeps today's voice).
  """
  def voice(lang) do
    case RisiMe.Agent.TTS.voice_for(lang) do
      {:manual, "kokoro:" <> _ = id} -> id
      _ -> RisiMe.Agent.TTS.Kokoro.default_voice()
    end
  rescue
    _ -> RisiMe.Agent.TTS.Kokoro.default_voice()
  end

  @doc "Running sessions."
  def running do
    DynamicSupervisor.count_children(RisiMe.Agent.CallRisi.Sessions).active
  catch
    :exit, _ -> 0
  end

  @doc """
  The checks before a room is made (proposal §41.2), in order: the switch → the caller's own Risi
  chat and audio → the device's `call_risi` → the limit → health. `:ok` or `{:error, code}`.
  """
  def check(user_id, conv, %{media: media, caps: caps}) do
    cond do
      not enabled_for?(user_id) -> {:error, :call_risi_off}
      not risi_chat_of?(conv, user_id) or media != "audio" -> {:error, :not_risi_chat}
      "call_risi" not in caps -> {:error, :invalid_device}
      running() >= Keyword.get(config(), :max, 2) -> {:error, :risi_busy}
      not healthy?() -> {:error, :risi_unavailable}
      true -> :ok
    end
  end

  defp risi_chat_of?(conv, user_id) do
    impl = Application.get_env(:risime, :risi_call_chat_check)

    if is_function(impl, 2),
      do: impl.(conv, user_id),
      else: RisiMe.Groups.Tabs.risi_chat?(conv) and RisiMe.Groups.active_member?(conv, user_id)
  end

  @doc "Speech, TTS, the worker files and Risi itself are up."
  def healthy? do
    case Application.get_env(:risime, :risi_call_health) do
      f when is_function(f, 0) ->
        f.()

      _ ->
        cfg = config()

        RisiMe.Agent.Out.ready?() and RisiMe.Agent.Speech.healthy?() and
          RisiMe.Agent.TTS.Kokoro.healthy?() and
          File.exists?(Keyword.get(cfg, :python, "")) and
          File.exists?(Keyword.get(cfg, :worker, ""))
    end
  end

  @doc "Risi's LiveKit identity (its MLS leaf identity)."
  def risi_identity, do: RisiMe.Risi.user_id() <> "/" <> RisiMe.Risi.device_id()

  @doc """
  Risi's participant token for `room` (minted inside the server, never through REST): subscribe,
  publish the microphone only, no data, not hidden (proposal §41.2).
  """
  def risi_claims(api_key, room, now) do
    claims = RisiMe.Calls.LiveKit.participant_claims(api_key, risi_identity(), room, "audio", now)
    # A 30-min call outlives the 600-s token only for reconnects; Risi doesn't reconnect in v1.
    put_in(claims, ["video", "canPublishSources"], ["microphone"])
  end

  @doc "Starts the session for a created room. `{:ok, pid}` or `{:error, reason}`."
  def start(%{} = args) do
    {:ok, lk} = RisiMe.Calls.LiveKit.config()
    now = System.os_time(:second)
    token = RisiMe.Calls.LiveKit.sign(risi_claims(lk.api_key, args.room, now), lk.api_secret)

    args =
      Map.merge(args, %{
        risi_token: token,
        worker_url: Keyword.get(config(), :livekit_url, "ws://127.0.0.1:7880")
      })

    DynamicSupervisor.start_child(RisiMe.Agent.CallRisi.Sessions, {Session, args})
  end

  @doc "Ends every running session (the switch went off)."
  def stop_all(reason \\ :switch_off) do
    for {_, pid, _, _} <- DynamicSupervisor.which_children(RisiMe.Agent.CallRisi.Sessions),
        is_pid(pid),
        do: GenServer.cast(pid, {:end, reason})

    :ok
  catch
    :exit, _ -> :ok
  end

  @doc """
  Posts the call's transcript (`risi_call_transcript`, proposal §41.5) in the Risi chat; nothing for a call
  without a user line. Runs in its own task (the session may stop at once).
  """
  def post_transcript(args, lines, started_at, reason) do
    if Enum.any?(lines, &(&1["who"] == "user")) do
      poster = Map.get(args, :post) || (&post/3)
      ended = DateTime.utc_now()
      {risi, body} = transcript(args, lines, started_at, ended, reason)
      Task.start(fn -> poster.(args.conv, body, risi) end)
    end

    :ok
  end

  @doc false
  def transcript(args, lines, started_at, ended, reason) do
    {lines, truncated?} =
      if length(lines) > 400, do: {Enum.take(lines, -400), true}, else: {lines, false}

    duration = max(DateTime.diff(ended, started_at), 0)

    risi =
      %{
        "kind" => "risi_call_transcript",
        "call_id" => args.call_id,
        "started_at" => iso(started_at),
        "ended_at" => iso(ended),
        "duration_s" => duration,
        "reason" => Atom.to_string(reason),
        "lines" => lines,
        "e2ee" => false,
        "notify" => [args.user_id]
      }
      |> then(fn r -> if truncated?, do: Map.put(r, "truncated", true), else: r end)

    body =
      [
        "Call with Risi · #{div(duration, 60)}:#{String.pad_leading("#{rem(duration, 60)}", 2, "0")}"
      ]
      |> Kernel.++(
        for l <- lines do
          if(l["who"] == "user", do: "You: ", else: "Risi: ") <> l["text"]
        end
      )
      |> Enum.join("\n")
      |> String.slice(0, 12_000)

    {risi, body}
  end

  defp post(conv, body, risi) do
    case RisiMe.Agent.Out.post(conv, body, risi) do
      {:ok, _} -> :ok
      {:error, e} -> Logger.warning("call risi: transcript not posted (#{inspect(e)})")
    end
  end

  defp iso(dt), do: dt |> DateTime.truncate(:millisecond) |> DateTime.to_iso8601()
end
