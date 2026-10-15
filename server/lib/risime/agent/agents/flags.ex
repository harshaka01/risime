defmodule RisiMe.Agents.Flags do
  @moduledoc """
  Runtime flags of the deep agents (decision 084). **Flipping one needs no deploy.**

  * Per agent: `RISI_AGENT_<NAME>` = `off` | `shadow` | `canary` | `on`.
  * Master: `RISI_AGENTS` = `on` (default: the per-agent flags apply) | `legacy` (every agent
    `off` at once: today's path only, no shadow work).

  A flag resolves as: the row in Postgres `risi_agent_flags` **>** the OS environment (e.g.
  `infra/pilot/pilot.env`) **>** the built-in default (`@defaults`). The table is cached per
  node for 5 s (ETS `:risime_agent_flags`, owned by `RisiMe.Agents.Supervisor`; without it every
  read goes to the table), so a change reaches every node within 5 s and survives restarts;
  `put/2` and `clear/1` refresh the calling node at once. An unknown value counts as
  `off`; a database error reads as env/default (never a crash).

      scripts/risi-rpc 'RisiMe.Agents.Flags.put("RISI_AGENTS", "legacy")'

  Tests may set `config :risime, :risi_agent_flags, %{"RISI_AGENT_ROUTER" => "on"}`, which wins
  over everything (and skips the table).

  **Feature switches with a canary** (PART 9, Harsha 2026-10-13): `RISI_VOICE_TRANSCRIBE`,
  `RISI_ACTION_ITEMS` and (decision 090) `RISI_CALL_RISI` take `off` | `canary` | `on` (`feature/1`, `feature_for/2`). `canary` = on
  only for `RISI_DEV_USERS` (Harsha first). They resolve as: the table row **>** the OS
  environment when it says `canary` **>** the application env set by `config/runtime.exs` from
  the same variable (`true` = on; tests set it directly). Defaults stay `off`.

      scripts/risi-rpc 'RisiMe.Agents.Flags.put("RISI_VOICE_TRANSCRIBE", "canary")'
  """
  import Ecto.Query

  alias RisiMe.Repo

  @modes ~w(off shadow canary on)
  @feature_modes ~w(off canary on)
  @features %{
    "RISI_VOICE_TRANSCRIBE" => :risi_voice_transcribe,
    "RISI_ACTION_ITEMS" => :risi_action_items,
    # Decision 090: "Call Risi" (a voice call Risi answers); canary = Harsha first.
    "RISI_CALL_RISI" => :risi_call_risi,
    # Proposal 2026-10-14-spoken-risi (§37.11): a spoken "Risi, …" in an Official voice note.
    "RISI_SPOKEN_MENTION" => :risi_spoken_mention,
    # Incident 2026-10-14 (Sinhala slow), proposals 2026-10-14-transcribe-progress and
    # -phone-transcript-fallback: off until root applies them (v1.48).
    "RISI_VOICE_PROGRESS" => :risi_voice_progress,
    "RISI_VOICE_PHONE_FALLBACK" => :risi_voice_phone_fallback,
    # Harsha 2026-10-14: the Sinhala compare mode on dev users' own notes (SpeechCompare).
    "RISI_SPEECH_COMPARE_DEV" => :risi_speech_compare_dev,
    # Proposal 2026-10-14-send-for-me (§42.1): "Send a message for me"; canary = Harsha first.
    "RISI_SEND_FOR_ME" => :risi_send_for_me,
    # Proposal 2026-10-14-permission-fixit (§43.1): "I need your calendar…" [Allow calendar].
    "RISI_PERMISSION_FIXIT" => :risi_permission_fixit,
    # Proposal 2026-10-14-calendar-v2 (§44.1): the honest footer, phone-side titles, merged rows.
    "RISI_CALENDAR_V2" => :risi_calendar_v2,
    # P1 2026-10-14 (Tamil notes in Sinhala script): the Voice agent's language decision
    # (RisiMe.Agent.SpeechLang); on for everyone, off = today's route.
    "RISI_VOICE_LANG_ID" => :risi_voice_lang_id,
    # Proposal 2026-10-14-voice-translate (v1.50 §37.13): "Wrong language?" and Translate.
    "RISI_VOICE_TRANSLATE" => :risi_voice_translate,
    # Decision 092 (proposal 2026-10-14-private-transcribe): transcription and translation in
    # Private, each only after the reader's explicit yes; canary = RISI_DEV_USERS via /me.
    "RISI_PRIVATE_TRANSCRIBE" => :risi_private_transcribe,
    # P0-1b 2026-10-15 (proposal 2026-10-15-live-call-latency): live Official-call lines in about
    # 2 s (VAD-cut 2-4 s chunks, the fast English model, live GPU priority); canary = a call
    # with a RISI_DEV_USERS participant.
    "RISI_LIVE_FAST" => :risi_live_fast,
    # Contract §41.9 (Harsha 2026-10-15): on a call, a clear "yes" confirms the caller's own
    # pending calendar/reminder card like tapping Add; canary = RISI_DEV_USERS.
    "RISI_CALL_VOICE_CONFIRM" => :risi_call_voice_confirm
  }
  # Features that also take `shadow` (detect and log for everyone, act for nobody), read straight
  # from the OS environment, with their own default (Harsha 2026-10-14: canary).
  @feature_extra_modes %{"RISI_SPOKEN_MENTION" => ~w(shadow)}
  @feature_defaults %{
    "RISI_SPOKEN_MENTION" => "canary",
    "RISI_VOICE_PROGRESS" => "off",
    "RISI_VOICE_PHONE_FALLBACK" => "off",
    "RISI_SPEECH_COMPARE_DEV" => "canary",
    "RISI_SEND_FOR_ME" => "canary",
    "RISI_PERMISSION_FIXIT" => "canary",
    "RISI_CALENDAR_V2" => "canary",
    "RISI_VOICE_LANG_ID" => "on",
    "RISI_VOICE_TRANSLATE" => "canary",
    "RISI_PRIVATE_TRANSCRIBE" => "off",
    "RISI_LIVE_FAST" => "off",
    "RISI_CALL_VOICE_CONFIRM" => "canary"
  }
  @masters ~w(on legacy)
  @ttl_ms 5_000
  @ets :risime_agent_flags

  # Today's path everywhere; the router only observes (rules, no model call).
  @defaults %{
    "RISI_AGENTS" => "on",
    "RISI_AGENT_ROUTER" => "shadow",
    # Decision 088: repairs only for RISI_DEV_USERS' conversations, shadow (detect, record,
    # alert) elsewhere; today's path did nothing at all.
    "RISI_AGENT_HEALTH" => "canary",
    # Incident 2026-10-14 (decision 088 amendment): the Health agent's switches, `off | canary | on`
    # (`switch/1`). Official repairs everywhere (Harsha: "canary first, then all"); the Private
    # nudge (proposal 2026-10-14-health-nudge) for RISI_DEV_USERS' chats first; the plain-language
    # report (names and actions, no ids) for everyone.
    "RISI_HEALTH_OFFICIAL_REPAIR" => "on",
    "RISI_HEALTH_NUDGE" => "canary",
    "RISI_HEALTH_PLAIN" => "on"
  }
  @switches ~w(RISI_HEALTH_OFFICIAL_REPAIR RISI_HEALTH_NUDGE RISI_HEALTH_PLAIN)

  @doc "The valid per-agent modes."
  def modes, do: @modes

  @doc "The master switch: `:on` or `:legacy`."
  def master do
    case raw("RISI_AGENTS") do
      "legacy" -> :legacy
      _ -> :on
    end
  end

  @doc "True when `RISI_AGENTS=legacy` (every agent off)."
  def legacy?, do: master() == :legacy

  @doc """
  The mode of agent `name` (`"router"`): `:off | :shadow | :canary | :on`. Always `:off` while the
  master is `legacy`.
  """
  def mode(name) when is_binary(name) do
    if legacy?() do
      :off
    else
      case raw(RisiMe.Agents.Agent.flag_name(name)) do
        v when v in @modes -> String.to_existing_atom(v)
        _ -> :off
      end
    end
  end

  @doc """
  The mode that applies to `user` (canary: `:on` for `RISI_DEV_USERS`, `:shadow` for everyone
  else).
  """
  def mode_for(name, user) do
    case mode(name) do
      :canary -> if dev_user?(user), do: :on, else: :shadow
      m -> m
    end
  end

  @doc "The feature switches that take `off | canary | on`."
  def features, do: Map.keys(@features)

  @doc """
  The mode of a feature switch (`"RISI_VOICE_TRANSCRIBE"`, `"RISI_ACTION_ITEMS"`):
  `:off | :canary | :on`, read at runtime (table > env `canary` > application env).
  """
  def feature(name) when is_map_key(@features, name) do
    v =
      case Application.get_env(:risime, :risi_agent_flags) do
        %{} = forced when is_map_key(forced, name) -> forced[name]
        _ -> Map.get(table(), name) || feature_env(name)
      end

    cond do
      v in feature_modes(name) -> String.to_existing_atom(v)
      v == nil and is_map_key(@feature_defaults, name) -> app_feature(@features[name], name)
      v == nil -> app_feature(@features[name])
      true -> :off
    end
  end

  @doc "The values feature `name` takes (`off | canary | on`, plus `shadow` for some)."
  def feature_modes(name), do: @feature_modes ++ Map.get(@feature_extra_modes, name, [])

  # The env is read directly for the features with their own default (any valid value), else only
  # its `canary` (the others' `on`/`off` come from the application env set by runtime.exs).
  defp feature_env(name) do
    case env(name) do
      nil -> nil
      v -> if is_map_key(@feature_defaults, name) or v == "canary", do: v
    end
  end

  @doc "True when feature `name` is on for any of `users` (`canary`: a dev user among them)."
  def feature_for(name, users) when is_list(users) do
    case feature(name) do
      :on -> true
      :canary -> Enum.any?(users, &dev_user?/1)
      # `shadow` acts for nobody.
      _ -> false
    end
  end

  def feature_for(name, user), do: feature_for(name, [user])

  # A feature with its own default: the application env when set (tests), else the default.
  defp app_feature(key, name) do
    case Application.get_env(:risime, key) do
      nil -> String.to_existing_atom(@feature_defaults[name])
      v when v in [true, :on, "on"] -> :on
      v when v in [:canary, "canary"] -> :canary
      v when v in [:shadow, "shadow"] -> :shadow
      _ -> :off
    end
  end

  defp app_feature(key) do
    case Application.get_env(:risime, key, false) do
      v when v in [true, :on, "on"] -> :on
      v when v in [:canary, "canary"] -> :canary
      _ -> :off
    end
  end

  @doc """
  A Health switch (`RISI_HEALTH_OFFICIAL_REPAIR`, `RISI_HEALTH_NUDGE`, `RISI_HEALTH_PLAIN`):
  `:off | :canary | :on`, resolved like an agent flag (table > env > default); an unknown value
  is `:off`.
  """
  def switch(name) when name in @switches do
    case raw(name) do
      v when v in @feature_modes -> String.to_existing_atom(v)
      _ -> :off
    end
  end

  @doc "The Health switches."
  def switches, do: @switches

  @doc "True when `user` is in `RISI_DEV_USERS` (the canary group)."
  def dev_user?(user) when is_binary(user),
    do: String.downcase(user) in Application.get_env(:risime, :risi_dev_users, [])

  def dev_user?(_), do: false

  @doc "The raw value of a flag (table > env > default), or nil."
  def raw(name) do
    case Application.get_env(:risime, :risi_agent_flags) do
      %{} = forced when is_map_key(forced, name) ->
        forced[name]

      _ ->
        Map.get(table(), name) || env(name) || Map.get(@defaults, name)
    end
  end

  @doc """
  Sets a flag in `risi_agent_flags` (live within 5 s on every node; at once here). Validates the
  value: `RISI_AGENTS` takes `on|legacy`, `RISI_AGENT_<NAME>` takes `off|shadow|canary|on`.
  """
  def put(name, value, by \\ "rpc") when is_binary(name) and is_binary(value) do
    with :ok <- validate(name, value) do
      now = DateTime.utc_now()

      Repo.insert_all(
        "risi_agent_flags",
        [%{name: name, value: value, updated_by: by, updated_at: now}],
        on_conflict: [set: [value: value, updated_by: by, updated_at: now]],
        conflict_target: [:name]
      )

      refresh()
      :ok
    end
  end

  @doc "Removes a flag's row (back to env/default)."
  def clear(name) do
    Repo.delete_all(from f in "risi_agent_flags", where: f.name == ^name)
    refresh()
    :ok
  end

  @doc "Every agent flag with its value and where it came from (for the admin view)."
  def all do
    names =
      [
        "RISI_AGENTS",
        "RISI_ROUTER_MODEL" | Enum.map(RisiMe.Agents.names(), &RisiMe.Agents.Agent.flag_name/1)
      ] ++ Enum.sort(Map.keys(@features)) ++ @switches

    t = table()

    for n <- names do
      {value, from} =
        cond do
          Map.has_key?(t, n) ->
            {t[n], "table"}

          is_map_key(@features, n) ->
            {Atom.to_string(feature(n)), if(env(n), do: "env", else: "default")}

          env(n) ->
            {env(n), "env"}

          true ->
            {Map.get(@defaults, n, "off"), "default"}
        end

      %{name: n, value: value, from: from}
    end
  end

  @doc "Re-reads the table now (this node)."
  def refresh do
    if cache?(), do: :ets.delete(@ets, :table)
    table()
    :ok
  rescue
    ArgumentError -> :ok
  end

  @doc "Creates the cache table (called by `RisiMe.Agents.Supervisor`, which owns it)."
  def init_cache do
    if :ets.whereis(@ets) == :undefined,
      do: :ets.new(@ets, [:named_table, :public, :set, read_concurrency: true])

    :ok
  end

  defp cache?, do: :ets.whereis(@ets) != :undefined

  defp validate("RISI_AGENTS", v) when v in @masters, do: :ok

  defp validate("RISI_AGENT_" <> _, v) when v in @modes, do: :ok

  defp validate(name, v) when is_map_key(@features, name) do
    if v in feature_modes(name), do: :ok, else: {:error, :invalid_flag}
  end

  defp validate(name, v) when name in @switches and v in @feature_modes, do: :ok

  # The router's model alias (`off` = rules only), e.g. `risi-l1`, later `risi-l2`.
  defp validate("RISI_ROUTER_MODEL", v),
    do:
      if(Regex.match?(~r/^[a-z0-9][a-z0-9._-]{0,39}$/, v), do: :ok, else: {:error, :invalid_flag})

  defp validate(_, _), do: {:error, :invalid_flag}

  defp env(name) do
    case System.get_env(name) do
      nil -> nil
      "" -> nil
      v -> v |> String.trim() |> String.downcase()
    end
  end

  defp table do
    now = System.monotonic_time(:millisecond)

    if cache?() do
      case :ets.lookup(@ets, :table) do
        [{:table, at, map}] when now - at < @ttl_ms ->
          map

        _ ->
          map = load()
          :ets.insert(@ets, {:table, now, map})
          map
      end
    else
      load()
    end
  rescue
    ArgumentError -> load()
  end

  defp load do
    Repo.all(from f in "risi_agent_flags", select: {f.name, f.value})
    |> Map.new(fn {k, v} -> {k, String.downcase(v)} end)
  rescue
    _ -> %{}
  catch
    :exit, _ -> %{}
  end
end
