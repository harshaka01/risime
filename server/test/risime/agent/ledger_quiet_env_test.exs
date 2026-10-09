defmodule RisiMe.Agent.LedgerQuietEnvTest do
  @moduledoc """
  §27.2: the quiet rule's thresholds come from the environment (`config/runtime.exs`):
  RISI_QUIET_S, RISI_QUIET_MIN_MSGS, RISI_QUIET_MIN_PEOPLE, RISI_QUIET_SPACING_S,
  RISI_QUIET_PER_DAY, with the §27 defaults (600 s, 6, 2, 30 min, 8 a day) when unset or bad.
  """
  use ExUnit.Case, async: false

  alias RisiMe.Agent.Ledger

  @vars ~w(RISI_QUIET_S RISI_QUIET_MIN_MSGS RISI_QUIET_MIN_PEOPLE RISI_QUIET_SPACING_S
           RISI_QUIET_PER_DAY)
  @keys ~w(risi_quiet_s risi_quiet_min_msgs risi_quiet_min_people risi_quiet_spacing_s
           risi_quiet_per_day)a

  setup do
    old_env = for v <- @vars, do: {v, System.get_env(v)}
    old_app = for k <- @keys, do: {k, Application.fetch_env(:risime, k)}

    on_exit(fn ->
      for {v, x} <- old_env, do: if(x, do: System.put_env(v, x), else: System.delete_env(v))

      for {k, x} <- old_app do
        case x do
          {:ok, val} -> Application.put_env(:risime, k, val)
          :error -> Application.delete_env(:risime, k)
        end
      end
    end)

    for v <- @vars, do: System.delete_env(v)
    for k <- @keys, do: Application.delete_env(:risime, k)
    :ok
  end

  defp runtime,
    do:
      Path.expand("../../../config/runtime.exs", __DIR__)
      |> Config.Reader.read!(env: :test)
      |> Keyword.get(:risime, [])

  test "unset: the §27 defaults" do
    cfg = runtime()
    for k <- @keys, do: refute(Keyword.has_key?(cfg, k))

    assert {Ledger.quiet_s(), Ledger.min_msgs(), Ledger.min_people(), Ledger.spacing_s(),
            Ledger.per_day()} == {600, 6, 2, 1800, 8}
  end

  test "the env values are read and honoured" do
    for {v, n} <- Enum.zip(@vars, ["5", "2", "1", "60", "3"]), do: System.put_env(v, n)
    cfg = runtime()

    assert Keyword.take(cfg, @keys) |> Enum.sort() ==
             Enum.sort(
               risi_quiet_s: 5,
               risi_quiet_min_msgs: 2,
               risi_quiet_min_people: 1,
               risi_quiet_spacing_s: 60,
               risi_quiet_per_day: 3
             )

    for {k, v} <- Keyword.take(cfg, @keys), do: Application.put_env(:risime, k, v)

    assert {Ledger.quiet_s(), Ledger.min_msgs(), Ledger.min_people(), Ledger.spacing_s(),
            Ledger.per_day()} == {5, 2, 1, 60, 3}
  end

  test "a value that isn't a positive integer is ignored" do
    System.put_env("RISI_QUIET_S", "soon")
    System.put_env("RISI_QUIET_MIN_MSGS", "0")
    cfg = runtime()
    refute Keyword.has_key?(cfg, :risi_quiet_s)
    refute Keyword.has_key?(cfg, :risi_quiet_min_msgs)
  end
end
