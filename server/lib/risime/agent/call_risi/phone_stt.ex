defmodule RisiMe.Agent.CallRisi.PhoneStt do
  @moduledoc """
  The caller's speech recognised **on the phone** during a Call Risi (contract v1.53 §41.10,
  Harsha 2026-10-16 04:52 SLT, P0 hearing). Pure helpers for the session:

    * `capable?/1`, `capable?/2`: the caller's device advertises `call_risi_stt` (with `call_risi`).
      Without it the call is byte-identical to v1.52 (the server's own speech-to-text).
    * `mode_cmd/1`: the media worker's Port command `{"cmd": "stt_mode", "mode": "phone" | "server"}`.
      The worker publishes `{"t": "mode", "mode": …}` reliably on topic `risi.stt` to the caller
      only and acks with `{"ev": "stt_mode", "mode": …}` (an older worker never acks, so the session
      stays on its own speech-to-text). While in phone mode the worker also sends
      `{"t": "speaking", "on": …}` when Risi's own speech starts and ends.
    * `judge/2`: what a `final` from the phone gets: `{:ok, text}`, `{:unclear, "low_conf"}` or
      `{:drop, "echo"}` (`during_risi: true` without clear barge-in energy on the audio).

  **The phone's text is the caller's words**: like the server's transcript it goes only into the
  call's in-memory turn and its `risi_call_transcript` (§41.5). Nothing here logs it; the learning
  log records `stt: "phone"` and the latency only.
  """

  @capability "call_risi_stt"
  @topic "risi.stt"
  @min_conf 0.4
  @miss_ms 3_000
  @max_misses 3
  # A `during_risi` final counts as a real barge-in when the worker heard one this recently.
  @barge_window_ms 8_000
  # A final that arrives while the caller is still speaking waits this long for the utterance.
  @pair_wait_ms 1_500
  # An utterance with less voiced speech than this is not "clear speech" for the fallback.
  @clear_speech_ms 400
  @max_text 1_000

  def capability, do: @capability
  def topic, do: @topic
  def min_conf, do: @min_conf
  def miss_ms, do: Application.get_env(:risime, :risi_call_stt_miss_ms, @miss_ms)
  def max_misses, do: @max_misses
  def barge_window_ms, do: @barge_window_ms
  def pair_wait_ms, do: Application.get_env(:risime, :risi_call_stt_pair_ms, @pair_wait_ms)
  def clear_speech_ms, do: @clear_speech_ms

  @doc "The device's capabilities allow phone recognition: `call_risi_stt` with `call_risi`."
  def capable?(caps) when is_list(caps), do: @capability in caps and "call_risi" in caps
  def capable?(_caps), do: false

  @doc "The same check for a stored device, by user and device id (any failure: false)."
  def capable?(user_id, device_id) when is_binary(user_id) and is_binary(device_id) do
    case RisiMe.Repo.get_by(RisiMe.Devices.Device, user_id: user_id, device_id: device_id) do
      %{capabilities: caps} when is_list(caps) -> capable?(caps)
      _ -> false
    end
  rescue
    _ -> false
  catch
    _, _ -> false
  end

  def capable?(_user_id, _device_id), do: false

  @doc "The worker command that switches the call's recognition (`\"phone\"` or `\"server\"`)."
  def mode_cmd(mode) when mode in ["phone", "server"], do: %{"cmd" => "stt_mode", "mode" => mode}

  @doc """
  A worker `{"ev": "stt", "t": "final", ...}` event, normalised: `{:ok, %{seq, text, conf,
  during_risi?}}` or `:error` (malformed: dropped).
  """
  def final(%{"t" => "final", "text" => text} = ev) when is_binary(text) do
    text = text |> String.slice(0, @max_text) |> String.trim()
    conf = ev["conf"]

    cond do
      text == "" ->
        :error

      not (is_nil(conf) or (is_number(conf) and conf >= 0 and conf <= 1)) ->
        :error

      true ->
        {:ok, %{seq: ev["seq"], text: text, conf: conf, during_risi?: ev["during_risi"] == true}}
    end
  end

  def final(_ev), do: :error

  @doc """
  The §41.10 rule for one final: `conf` < 0.4 → unclear; `during_risi` without clear barge-in
  energy (`barge?`) → dropped (Risi's own voice, recognised from the echo); otherwise the words.
  """
  def judge(%{during_risi?: true}, false), do: {:drop, "echo"}
  def judge(%{conf: c}, _barge?) when is_number(c) and c < @min_conf, do: {:unclear, "low_conf"}
  def judge(%{text: text}, _barge?), do: {:ok, text}
end
