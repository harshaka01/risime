defmodule RisiRegistry.HTTPStub do
  @moduledoc """
  Test HTTP client: responses are registered per URL. No network. Tests using it run with
  `async: false` because the routes are global.
  """
  @behaviour RisiRegistry.HTTP
  use Agent

  def start_link(_), do: Agent.start_link(fn -> %{routes: %{}, calls: []} end, name: __MODULE__)

  def reset, do: Agent.update(__MODULE__, fn _ -> %{routes: %{}, calls: []} end)

  @doc "Registers `response` for `url`: `{status, body}`, a JSON-encodable map, or a fun."
  def put(url, response), do: Agent.update(__MODULE__, &put_in(&1, [:routes, url], response))

  def calls, do: Agent.get(__MODULE__, &Enum.reverse(&1.calls))
  def calls_to(url), do: Enum.count(calls(), fn {_m, u, _} -> u == url end)

  @impl true
  def get(url, opts), do: respond(:get, url, nil, opts)

  @impl true
  def post_form(url, form, opts), do: respond(:post, url, Map.new(form), opts)

  defp respond(method, url, form, opts) do
    route =
      Agent.get_and_update(__MODULE__, fn s ->
        {Map.get(s.routes, url), %{s | calls: [{method, url, form} | s.calls]}}
      end)

    result =
      case route do
        nil -> {:error, :nxdomain}
        fun when is_function(fun, 1) -> fun.(form)
        {status, body} when is_binary(body) -> {:ok, %{status: status, body: body}}
        %{} = map -> {:ok, %{status: 200, body: Jason.encode!(map)}}
        {:error, _} = err -> err
      end

    max = Keyword.get(opts, :max_bytes, 64 * 1024)

    case result do
      {:ok, %{body: body}} when byte_size(body) > max -> {:error, :too_large}
      other -> other
    end
  end
end
