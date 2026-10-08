defmodule RisiMe.AuthLog do
  @moduledoc """
  One stable plain-text line per authentication failure, for fail2ban (decision 024):

      2026-10-06T08:15:30Z risime auth_failure ip=203.0.113.9 kind=invalid_code path=/api/v1/auth/verify

  Written to `RISIME_AUTH_LOG` (default `~/risime-logs/auth.log`) and to the normal log.
  Never contains a phone, email, token or code: only the time, IP, kind and path, all built
  here from fixed values.
  """
  require Logger

  @kinds ~w(invalid_code too_many_attempts rate_limited invalid_token not_allowlisted
            identity_conflict socket_refused phone_code_invalid signup_refused
            signup_rate_limited)a

  def kinds, do: @kinds

  @spec failure(String.t(), atom, String.t()) :: :ok
  def failure(ip, kind, path) when kind in @kinds do
    line = line(ip, kind, path)
    Logger.info(line)
    write(line)
  end

  @doc false
  def line(ip, kind, path) do
    time = DateTime.utc_now() |> DateTime.truncate(:second) |> DateTime.to_iso8601()
    "#{time} risime auth_failure ip=#{safe(ip)} kind=#{kind} path=#{safe(path)}"
  end

  # Only characters that can appear in an IP or a route path; nothing user-controlled
  # (spaces, newlines, query strings) can reach the file.
  defp safe(value) do
    value |> to_string() |> String.replace(~r/[^A-Za-z0-9.:\/_-]/, "") |> String.slice(0, 100)
  end

  defp write(line) do
    case path() do
      nil ->
        :ok

      path ->
        with :ok <- File.mkdir_p(Path.dirname(path)),
             :ok <- File.write(path, line <> "\n", [:append]) do
          :ok
        else
          {:error, reason} ->
            Logger.error("auth log #{path} not writable: #{inspect(reason)}")
            :ok
        end
    end
  end

  def path do
    case Application.get_env(:risime, :auth_log_path, :default) do
      :default -> Path.expand("~/risime-logs/auth.log")
      other -> other
    end
  end
end
