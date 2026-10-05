defmodule RisiMe.JSONLogFormatter do
  @moduledoc """
  An Erlang `:logger` formatter that writes one JSON object per line:
  `{"time", "level", "msg", ...allowlisted metadata}`.

  Turned on by `config :risime, :log_format, :json` (`LOG_FORMAT=json`, the default in prod;
  see docs/decisions/008-observability.md). Only allowlisted metadata keys are written, so
  arbitrary metadata can't leak secrets. Callers are still responsible for never putting
  message bodies, OTP codes or tokens in log messages.
  """

  @metadata ~w(request_id module function line mfa user_id event_id pid domain)a

  @doc "Installs this formatter on the default handler."
  def install do
    :logger.update_handler_config(:default, :formatter, {__MODULE__, %{}})
  end

  @doc false
  def check_config(config) when is_map(config), do: :ok
  def check_config(_), do: {:error, :invalid_config}

  @doc false
  def format(%{level: level, msg: msg, meta: meta}, _config) do
    base = %{
      "time" => time(meta),
      "level" => Atom.to_string(level),
      "msg" => message(msg, meta)
    }

    fields =
      for key <- @metadata, Map.has_key?(meta, key), into: base do
        {Atom.to_string(key), value(key, meta[key])}
      end

    [Jason.encode_to_iodata!(fields), ?\n]
  rescue
    e -> ["{\"level\":\"error\",\"msg\":", Jason.encode!(Exception.message(e)), "}\n"]
  end

  defp time(%{time: t}) do
    t |> DateTime.from_unix!(:microsecond) |> DateTime.to_iso8601()
  end

  defp time(_), do: DateTime.utc_now() |> DateTime.to_iso8601()

  defp message({:string, s}, _meta), do: IO.chardata_to_string(s)

  defp message({:report, report}, meta) do
    case meta do
      %{report_cb: cb} when is_function(cb, 1) ->
        {fmt, args} = cb.(report)
        fmt |> :io_lib.format(args) |> IO.chardata_to_string()

      %{report_cb: cb} when is_function(cb, 2) ->
        report |> cb.(%{}) |> IO.chardata_to_string()

      _ ->
        inspect(report)
    end
  end

  defp message({fmt, args}, _meta), do: fmt |> :io_lib.format(args) |> IO.chardata_to_string()

  defp value(:mfa, {m, f, a}), do: "#{inspect(m)}.#{f}/#{a}"
  defp value(:pid, pid) when is_pid(pid), do: inspect(pid)
  defp value(:domain, domain) when is_list(domain), do: Enum.map(domain, &to_string/1)
  defp value(_key, v) when is_binary(v) or is_number(v) or is_boolean(v) or is_nil(v), do: v
  defp value(_key, v) when is_atom(v), do: inspect(v)
  defp value(_key, v), do: inspect(v)
end
