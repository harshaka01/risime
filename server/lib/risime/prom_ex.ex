defmodule RisiMe.PromEx do
  @moduledoc """
  Prometheus metrics (decision 075). Served by `RisiMeWeb.OpsEndpoint` on `127.0.0.1:METRICS_PORT`
  only (never by the main endpoint); Grafana dashboards are not uploaded from the app.
  """
  use PromEx, otp_app: :risime

  @impl true
  def plugins do
    [
      PromEx.Plugins.Application,
      PromEx.Plugins.Beam,
      {PromEx.Plugins.Phoenix, router: RisiMeWeb.Router, endpoint: RisiMeWeb.Endpoint},
      {PromEx.Plugins.Ecto, repos: [RisiMe.Repo]},
      {PromEx.Plugins.Oban, oban_supervisors: [Oban]},
      RisiMe.PromEx.Risi
    ]
  end

  @impl true
  def dashboard_assigns, do: []

  @impl true
  def dashboards, do: []
end
