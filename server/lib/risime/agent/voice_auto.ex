defmodule RisiMe.Agent.VoiceAuto do
  @moduledoc """
  What **Auto** means on the admin console's Speech models and Voices pages (decision 089, Part 12
  item 3). Auto is the default on both pages; a manual choice beats it until set back to Auto.

  **Target (owned by the Voice agent, decisions 084/085):** per language, the Voice agent ranks
  each installed model (speech-to-text) or voice (text-to-speech) by its Intelligence Index plus
  live feedback, switches the Auto route **only through the promotion rule** (held-out golden
  set ≥ current, latency within budget, auto-rollback over the first 200 runs / 24 h), and posts
  a short note to Harsha's Risi chat when it switches.

  **Today:** no per-model Index rows exist, so Auto = today's production route exactly (parity
  tests in `speech_routes_test.exs` and `tts_test.exs`). The Voice agent will write its daily
  per-model rows to `risi_agent_scores` with `agent = "voice"` and `source = "index_stt"` /
  `"index_tts"`; `status/1` reports whether any exist. Even with rows present Auto keeps today's
  route until the switcher (promotion + Risi-chat note) ships: this module never changes a
  route by itself.
  """
  import Ecto.Query

  alias RisiMe.Repo

  @sources %{stt: "index_stt", tts: "index_tts"}

  @doc """
  `%{index?: boolean, basis: :todays_route, text: String.t()}` for `:stt` or `:tts`.
  `basis` is always `:todays_route` until the Voice agent's switcher exists.
  """
  def status(kind) when is_map_key(@sources, kind) do
    index? = index?(kind)

    text =
      if index?,
        do:
          "Index data exists, but switching by the promotion rule is not built yet: Auto still uses today's route.",
        else:
          "No Intelligence Index for individual models yet: Auto uses today's route exactly. " <>
            "Once the Voice agent scores each model, Auto will follow the Index and live feedback, " <>
            "switch only through the promotion rule, and post a note in Harsha's Risi chat."

    %{index?: index?, basis: :todays_route, text: text}
  end

  defp index?(kind) do
    source = Map.fetch!(@sources, kind)

    Repo.exists?(from s in "risi_agent_scores", where: s.agent == "voice" and s.source == ^source)
  rescue
    _ -> false
  catch
    :exit, _ -> false
  end
end
