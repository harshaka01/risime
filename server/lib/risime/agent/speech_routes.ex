defmodule RisiMe.Agent.SpeechRoutes do
  @moduledoc """
  The per-language speech model choice of the admin console's Speech models page (decision 089).

  For each of `si`, `ta`, `en` and `mixed` (code-switched speech such as Singlish: the chunks
  the speech service's detector is not sure about, `reason=low_confidence`) the choice is
  `"auto"` or an installed model alias. It is a runtime setting (`RisiMe.Admin.Settings`, key
  `speech_route_<lang>`: table > env `RISI_SPEECH_ROUTE_<LANG>` > `"auto"`, 5-s cache), so a
  change applies within 5 s with no deploy.

  * **Auto (default): today's production route, byte-identical.** `pick/2` returns `:auto`
    without any I/O and `RisiMe.Agent.Speech.Http.transcribe/2` sends exactly what it sent
    before (`model=auto`; the service detects the language and routes, WHISPER_ROUTE_SI/TA).
    Once the Voice agent has a per-model transcription Intelligence Index (decision 085), Auto
    becomes the Voice agent's pick by that Index and live corrections, switched only through
    the promotion rule (decision 084), with a note to Harsha's Risi chat on a switch.
  * **Manual beats Auto** until set back to Auto: while any language has a manual model,
    `pick/2` asks the service's `POST /v1/audio/detect` (same `language_hint`) for the routing
    decision first; when the chunk's language has a manual model, the chunk is transcribed by
    that model by name (with `language` forced for si/ta/en, unforced for mixed). Otherwise, or
    if detection fails, the chunk takes the Auto path unchanged.

  Nothing about the audio or the text is logged or stored here.
  """
  alias RisiMe.Admin.Settings
  alias RisiMe.Agent.LLM.Local

  @langs ~w(si ta en mixed)

  @doc "The languages with a choice: si, ta, en, mixed (Singlish and other code-switching)."
  def languages, do: @langs

  @doc "The settings key of `lang`."
  def setting_key(lang) when lang in @langs, do: "speech_route_" <> lang

  @doc "The choice for `lang`: `\"auto\"` or a model alias."
  def choice(lang) when lang in @langs do
    case Settings.get(setting_key(lang), "auto") do
      v when is_binary(v) and v != "" -> v
      _ -> "auto"
    end
  end

  @doc "Every language's choice."
  def choices, do: Map.new(@langs, &{&1, choice(&1)})

  @doc "True when any language has a manual model."
  def manual?, do: Enum.any?(@langs, &(choice(&1) != "auto"))

  @doc """
  Sets `lang`'s choice (`"auto"` clears the row) by admin `by` (audit-logged). A model must be
  one of `installed` (the aliases the speech service serves now).
  """
  def put(lang, value, by, installed \\ [])

  def put(lang, "auto", by, _) when lang in @langs, do: Settings.clear(setting_key(lang), by)

  def put(lang, model, by, installed) when lang in @langs and is_binary(model) do
    if model in installed,
      do: Settings.put(setting_key(lang), model, by),
      else: {:error, :not_installed}
  end

  def put(_, _, _, _), do: {:error, :invalid}

  @doc """
  A per-user override (Harsha 2026-10-14, the Voice agent's route decision from his own labelled
  notes, `RisiMe.Agent.SpeechCompare`): `"auto"` (none), a model alias, or `"none"` (show
  nothing: the note is "couldn't hear clearly"). Key `speech_route_<lang>_user_<uuid>`.
  """
  def user_choice(user, lang) when is_binary(user) and lang in @langs do
    case Settings.get(user_key(user, lang), "auto") do
      v when is_binary(v) and v != "" -> v
      _ -> "auto"
    end
  end

  def user_choice(_, _), do: "auto"

  @doc "Sets (or, with `\"auto\"`, clears) `user`'s override for `lang` (audit-logged as `by`)."
  def put_user(user, lang, "auto", by), do: Settings.clear(user_key(user, lang), by)
  def put_user(user, lang, v, by) when is_binary(v), do: Settings.put(user_key(user, lang), v, by)

  defp user_key(user, lang), do: "speech_route_#{lang}_user_#{String.downcase(user)}"

  @doc """
  The route for one chunk: `:auto` (today's path) or `{:manual, model, language | nil}`.
  No I/O at all while every language is on Auto (and the sender has no override).
  `opts[:sender]`'s per-user overrides win over the global choice.
  """
  def pick(audio, opts) when is_binary(audio) do
    user = opts[:sender]

    choices =
      Map.new(choices(), fn {lang, v} ->
        case user_choice(user, lang) do
          "auto" -> {lang, v}
          u -> {lang, u}
        end
      end)

    if Enum.all?(choices, fn {_, v} -> v == "auto" end) do
      :auto
    else
      with {:ok, key} <- detect_key(audio, opts),
           model when model != "auto" <- Map.fetch!(choices, key) do
        {:manual, model, if(key == "mixed", do: nil, else: key)}
      else
        _ -> :auto
      end
    end
  end

  # The service's routing decision → si | ta | en | mixed (no speech or an error: :skip).
  defp detect_key(audio, opts) do
    fields =
      [file: {audio, filename: "chunk.ogg", content_type: "audio/ogg"}] ++
        case RisiMe.Agent.Speech.hint_field(opts) do
          nil -> []
          hint -> [language_hint: hint]
        end

    with {:ok, base} <- base_url(),
         {:ok, %{status: 200, body: %{} = b}} <-
           Req.post(req(base <> "/v1/audio/detect", 10_000), form_multipart: fields) do
      case b do
        %{"reason" => "no_speech"} -> :skip
        %{"reason" => "low_confidence"} -> {:ok, "mixed"}
        %{"language" => l} when l in ["si", "ta", "en"] -> {:ok, l}
        _ -> {:ok, "mixed"}
      end
    else
      _ -> :skip
    end
  end

  @doc """
  The models the speech service serves now: `{:ok, %{models: [%{id, language, role, loaded}],
  routes: %{"si" => alias, ...}}}` (`routes` = today's Auto route per language; `mixed` is the
  default model) or `{:error, :unavailable}`.
  """
  def installed do
    with {:ok, base} <- base_url(),
         {:ok, %{status: 200, body: %{"data" => data}}} when is_list(data) <-
           Req.get(req(base <> "/v1/models", 5_000)) do
      auto = Enum.find(data, %{}, &(&1["id"] == "auto"))
      models = for %{"id" => id} = m <- data, id != "auto", do: model(m)

      default =
        Enum.find_value(models, "faster-whisper-large-v3", &(&1.role == "default" && &1.id))

      routes = Map.merge(%{"en" => default}, Map.get(auto, "routes") || %{})
      {:ok, %{models: models, routes: Map.put(routes, "mixed", default)}}
    else
      _ -> {:error, :unavailable}
    end
  end

  defp model(m) do
    %{
      id: to_string(m["id"]),
      language: m["language"],
      role: m["role"],
      loaded: m["loaded"] == true
    }
  end

  # Part 12: the families Harsha expects. A family is installed when the speech service serves
  # a model whose alias matches; "phone" is the phone's own recogniser (developer flag
  # phone_stt), never a server model.
  @catalogue [
    {"Qwen-Sinhala", &__MODULE__.qwen_sinhala?/1},
    {"omniASR 1B", &__MODULE__.omni_1b?/1},
    {"omniASR 3B", &__MODULE__.omni_3b?/1},
    {"omniASR 7B", &__MODULE__.omni_7b?/1},
    {"Whisper", &__MODULE__.whisper?/1},
    {"Tamil models", &__MODULE__.tamil?/1}
  ]

  @doc """
  The expected model families with the served aliases that belong to each:
  `[%{family, installed: [alias]}]`, plus Phone (on-device), which is never a server model.
  """
  def catalogue(models) do
    ids = Enum.map(models, & &1.id)

    for({family, match} <- @catalogue, do: %{family: family, installed: Enum.filter(ids, match)}) ++
      [%{family: "Phone (on-device)", installed: [], phone: true}]
  end

  @doc false
  def qwen_sinhala?(id), do: id =~ ~r/qwen3?-asr/i and id =~ ~r/sinhala|-si\b/i
  @doc false
  def omni_1b?(id), do: omni?(id) and id =~ ~r/(^|[-_])1b([-_.]|$)/i
  @doc false
  def omni_3b?(id), do: omni?(id) and id =~ ~r/(^|[-_])3b([-_.]|$)/i
  @doc false
  def omni_7b?(id), do: omni?(id) and id =~ ~r/(^|[-_])7b([-_.]|$)/i
  @doc false
  def whisper?(id), do: id =~ ~r/whisper/i and not tamil?(id)
  @doc false
  def tamil?(id), do: id =~ ~r/tamil/i

  defp omni?(id), do: id =~ ~r/omni/i

  @doc "The models offered for `lang`: the ones for that language, then the multilingual ones."
  def candidates(models, lang) when lang in @langs do
    if lang == "mixed",
      do: models,
      else: Enum.filter(models, &(&1.language in [lang, nil]))
  end

  defp req(url, timeout) do
    Req.new(
      [
        url: url,
        receive_timeout: timeout,
        connect_options: [timeout: 2_000],
        retry: false,
        redirect: false,
        headers: auth_headers()
      ] ++ (config()[:req_options] || [])
    )
  end

  defp base_url, do: Local.check_url(config()[:url] || "http://127.0.0.1:8200")

  defp config, do: Application.get_env(:risime, :risi_speech_http, [])

  defp auth_headers do
    case config()[:api_key] do
      k when is_binary(k) and k != "" -> [{"authorization", "Bearer " <> k}]
      _ -> []
    end
  end
end
