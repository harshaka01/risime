defmodule RisiMe.Agent.CallTranscribe.Session do
  @moduledoc """
  One transcription session of a call (contract v1.39 §36.2), the consent record of §36.8
  (`risi_call_transcribe_sessions`): ids, the participants with their consents and bound
  devices, the state, the reason, timestamps and counts. **No text, no audio.** Kept 90 days.

  `participants` is stored as `[%{"user_id", "consent", "at", "device_id"}]`; the wire shape
  (`to_json/1`) leaves the device out.
  """
  use Ecto.Schema

  alias RisiMe.Agent.Clock

  @primary_key {:session_id, :binary_id, autogenerate: false}
  schema "risi_call_transcribe_sessions" do
    field :call_id, :binary_id
    field :conversation_id, :string
    field :chat_id, :string
    field :state, :string
    field :reason, :string
    field :requested_by, :binary_id
    field :requested_at, :utc_datetime_usec
    field :expires_at, :utc_datetime_usec
    field :started_at, :utc_datetime_usec
    field :ended_at, :utc_datetime_usec
    field :by, :binary_id
    field :discard, :boolean, default: false
    field :compare, :boolean, default: false
    # Proposal 2026-10-15-live-call-latency (RISI_LIVE_FAST): decided at the request.
    field :fast, :boolean, default: false
    field :participants, {:array, :map}
    field :chunks, :integer, default: 0
    field :speech_ms, :integer, default: 0
    field :posts, :integer, default: 0
    timestamps(type: :utc_datetime_usec)
  end

  @final ~w(declined expired stopped ended)
  # v1.45 §40.9: `paused` (a joiner hasn't allowed yet) is live, not final.
  @live ~w(requested on paused)

  @chunk %{"min_ms" => 5000, "max_ms" => 20_000, "max_bytes" => 262_144}
  # Proposal 2026-10-15-live-call-latency: a fast session asks for VAD-cut chunks of 1-4 s with
  # 0.5 s of overlap (`mode` and `overlap_ms` are new and optional; an older app ignores them and
  # keeps its 10-15 s chunks, which a fast session accepts too).
  @chunk_fast %{
    "min_ms" => 1000,
    "max_ms" => 4000,
    "max_bytes" => 262_144,
    "mode" => "vad",
    "overlap_ms" => 500
  }

  @doc "The final states (§36.2)."
  def final_states, do: @final

  @doc "True for `declined`, `expired`, `stopped`, `ended`."
  def final?(%__MODULE__{state: s}), do: s in @final

  @doc "True for `requested`, `on` and (v1.45) `paused`."
  def live?(%__MODULE__{state: s}), do: s in @live

  @doc "The §36.4 chunk limits (a fast session's: proposal 2026-10-15-live-call-latency)."
  def chunk, do: @chunk
  def chunk(%__MODULE__{fast: true}), do: @chunk_fast
  def chunk(_), do: @chunk

  @doc "A participant's entry (or nil)."
  def participant(%__MODULE__{participants: ps}, user),
    do: Enum.find(ps, &(&1["user_id"] == user))

  @doc "The participants' user ids."
  def user_ids(%__MODULE__{participants: ps}), do: Enum.map(ps, & &1["user_id"])

  @doc "The device bound to `user` (§36.2), or nil."
  def bound_device(s, user), do: (participant(s, user) || %{})["device_id"]

  @doc "The wire `Session` (§36.2; `call_transcribe_reply.json`)."
  def to_json(%__MODULE__{} = s, now \\ DateTime.utc_now()) do
    %{
      "session_id" => s.session_id,
      "call_id" => s.call_id,
      "conversation_id" => s.conversation_id,
      "chat_id" => s.chat_id,
      "state" => s.state,
      "requested_by" => s.requested_by,
      "requested_at" => Clock.ts(s.requested_at),
      "expires_at" => if(s.state in ["requested", "paused"], do: Clock.ts(s.expires_at)),
      "participants" =>
        Enum.map(s.participants, fn p ->
          %{"user_id" => p["user_id"], "consent" => p["consent"], "at" => p["at"]}
        end),
      "compare" => s.compare,
      "started_at" => Clock.ts(s.started_at),
      "ended_at" => Clock.ts(s.ended_at),
      "by" => s.by,
      "reason" => s.reason,
      "discard" => s.discard,
      "chunk" => chunk(s),
      "server_ts" => Clock.ts(now)
    }
  end
end
