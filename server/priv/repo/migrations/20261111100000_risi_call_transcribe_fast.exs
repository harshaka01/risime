defmodule RisiMe.Repo.Migrations.RisiCallTranscribeFast do
  use Ecto.Migration

  # P0-1b 2026-10-15 (proposal 2026-10-15-live-call-latency, flag RISI_LIVE_FAST): a session
  # decided at its request to use the live fast path (VAD-cut 1-4 s chunks, one line per chunk,
  # the fast English model, live GPU priority). Additive; false for every existing row.
  def change do
    alter table(:risi_call_transcribe_sessions) do
      add :fast, :boolean, null: false, default: false
    end
  end
end
