defmodule RisiMe.Agent.Translate do
  @moduledoc """
  The Private translate request (decision 092; proposal 2026-10-14-private-transcribe §37.14.4):
  the checks and the no-trace wrapper around **the one server translate function**,
  `RisiMe.Agent.VoiceTranslate.translate/5` (the Official translate of proposal
  2026-10-14-voice-translate uses the same function), called with `private: true`:

    * risi-l1 only (`local_only`: never another provider, whatever `RISI_FALLBACK` says);
    * the learning log gets one `private_translate` row with the language codes only (`meta`),
      latency, model, status; `output: nil`, `prompt_sha256: nil`, `source_message_ids: []`,
      `conversation_id`/`chat_id` = `"private"`;
    * nothing here logs, stores or enqueues the text (no Oban job, no table row, no Logger line);
    * the request runs inside `sensitive/1` (no crash dump, trace or process info shows its data).
  """

  alias RisiMe.Agent.VoiceTranslate

  @langs ~w(en si ta)
  @max_chars 4000
  @max_bytes 12_000

  @doc "The target languages."
  def langs, do: @langs

  @doc """
  Checks a request: `{:ok, text, target, source}` (the text trimmed; `source` nil when not given)
  or `:error`. Never raises and never echoes the text.
  """
  def check(text, target, source \\ nil)

  def check(text, target, source) when is_binary(text) do
    t = String.trim(text)

    if t != "" and String.length(t) <= @max_chars and byte_size(t) <= @max_bytes and
         target in @langs and (is_nil(source) or source in @langs),
       do: {:ok, t, target, source},
       else: :error
  end

  def check(_text, _target, _source), do: :error

  @doc """
  Translates a Private `text` into `target` (`"en" | "si" | "ta"`), `source` optional:
  `{:ok, %{text, source, target}}`, `{:error, :bad_request}` or `{:error, :model_unavailable}`.
  The same language comes back as is (no model call, nothing logged).
  """
  def text(text, target, source \\ nil) do
    case check(text, target, source) do
      {:ok, t, target, source} ->
        case VoiceTranslate.translate(t, source, target, "private", private: true) do
          {:ok, out, _model} -> {:ok, %{text: out, source: source, target: target}}
          _ -> {:error, :model_unavailable}
        end

      :error ->
        {:error, :bad_request}
    end
  end

  @doc """
  Runs `fun` with the calling process marked `sensitive` (no crash dump, trace or
  `process_info` shows its data), then garbage-collects and restores the old flag. The Private
  translate request wraps its whole handling, including sending the reply, in this.
  """
  def sensitive(fun) do
    old = Process.flag(:sensitive, true)

    try do
      fun.()
    after
      :erlang.garbage_collect()
      Process.flag(:sensitive, old)
    end
  end
end
