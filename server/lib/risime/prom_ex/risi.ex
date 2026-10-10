defmodule RisiMe.PromEx.Risi do
  @moduledoc "RisiMe's own metrics: Risi turns, JWKS fetches, open sockets (decision 075)."
  use PromEx.Plugin

  @impl true
  def event_metrics(_opts) do
    [
      Event.build(:risime_risi_event_metrics, [
        counter("risime_risi_turn_total",
          event_name: [:risime, :risi, :turn, :stop],
          measurement: :duration,
          description: "Finished Risi turns by outcome (ok, snooze, error).",
          tags: [:outcome]
        ),
        distribution("risime_risi_turn_duration_milliseconds",
          event_name: [:risime, :risi, :turn, :stop],
          measurement: :duration,
          unit: {:native, :millisecond},
          description: "Duration of a Risi turn.",
          tags: [:outcome],
          reporter_options: [buckets: [500, 1_000, 2_500, 5_000, 10_000, 30_000, 60_000, 120_000]]
        ),
        counter("risime_jwks_fetch_total",
          event_name: [:risime, :jwks, :fetch],
          description: "JWKS fetches by result (ok, timeout, error).",
          tags: [:result]
        ),
        last_value("risime_sockets_open",
          event_name: [:risime, :socket, :open],
          measurement: :count,
          description: "Open user sockets on this node."
        )
      ])
    ]
  end
end
