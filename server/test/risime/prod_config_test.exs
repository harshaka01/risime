defmodule RisiMe.ProdConfigTest do
  @moduledoc "Prod config and runtime.exs evaluated as a release would (decision 024)."
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  @config Path.expand("../../config/config.exs", __DIR__)
  @runtime Path.expand("../../config/runtime.exs", __DIR__)

  @vars ~w(DATABASE_URL POSTGRES_PASSWORD POSTGRES_HOST POSTGRES_PORT POSTGRES_DB POSTGRES_USER
           SECRET_KEY_BASE PHX_HOST PHX_PATH PHX_ORIGINS PHX_BIND PORT CASSANDRA_NODES
           CASSANDRA_KEYSPACE)

  setup do
    saved = Map.new(@vars, &{&1, System.get_env(&1)})
    for v <- @vars, do: System.delete_env(v)

    on_exit(fn ->
      for {k, v} <- saved, do: if(v, do: System.put_env(k, v), else: System.delete_env(k))
    end)

    System.put_env("SECRET_KEY_BASE", String.duplicate("s", 64))
    System.put_env("POSTGRES_PASSWORD", "fake-password")
    :ok
  end

  defp runtime, do: Config.Reader.read!(@runtime, env: :prod, target: :host)[:risime]

  test "compile-time prod config: no dev routes, no debug errors, the disabled mailer" do
    risime = Config.Reader.read!(@config, env: :prod, target: :host)[:risime]
    assert risime[:dev_routes] == false
    assert risime[RisiMeWeb.Endpoint][:debug_errors] == false
    assert risime[RisiMeWeb.Endpoint][:code_reloader] == false
    assert risime[RisiMe.Mailer][:adapter] == RisiMe.Mailer.Disabled
    refute Keyword.has_key?(risime[RisiMeWeb.Endpoint], :force_ssl)
  end

  test "pilot defaults: loopback bind, port 4000, risime.risicloud.ai at /, POSTGRES_* repo" do
    System.put_env("POSTGRES_DB", "risime_dev")
    System.put_env("CASSANDRA_KEYSPACE", "risime_dev")
    r = runtime()
    e = r[RisiMeWeb.Endpoint]

    assert e[:http][:ip] == {127, 0, 0, 1}
    assert e[:http][:port] == 4000
    assert e[:url] == [host: "risime.risicloud.ai", port: 443, scheme: "https", path: "/"]
    assert e[:check_origin] == ["https://risime.risicloud.ai"]

    repo = r[RisiMe.Repo]
    assert repo[:hostname] == "127.0.0.1" and repo[:port] == 5432
    assert repo[:database] == "risime_dev" and repo[:username] == "risime"
    assert r[:cassandra][:keyspace] == "risime_dev"
    assert r[:cassandra][:nodes] == ["127.0.0.1:9042"]
  end

  test "PHX_ORIGINS, PHX_HOST, PORT and DATABASE_URL" do
    System.put_env("PHX_HOST", "example.test")
    System.put_env("PHX_ORIGINS", "https://a.test, https://b.test")
    System.put_env("PORT", "4100")
    System.put_env("DATABASE_URL", "ecto://u:p@127.0.0.1/db")
    r = runtime()
    assert r[RisiMeWeb.Endpoint][:check_origin] == ["https://a.test", "https://b.test"]
    assert r[RisiMeWeb.Endpoint][:url][:host] == "example.test"
    assert r[RisiMeWeb.Endpoint][:http][:port] == 4100
    assert r[RisiMe.Repo][:url] == "ecto://u:p@127.0.0.1/db"
  end

  test "PHX_BIND must be loopback; never 0.0.0.0 or ::" do
    for bad <- ["0.0.0.0", "::", "10.20.20.15", "nonsense"] do
      System.put_env("PHX_BIND", bad)
      assert_raise RuntimeError, ~r/loopback/, fn -> runtime() end
    end

    System.put_env("PHX_BIND", "::1")
    assert runtime()[RisiMeWeb.Endpoint][:http][:ip] == {0, 0, 0, 0, 0, 0, 0, 1}
  end

  test "SECRET_KEY_BASE and a database are required" do
    System.delete_env("SECRET_KEY_BASE")
    assert_raise RuntimeError, ~r/SECRET_KEY_BASE/, fn -> runtime() end
    System.put_env("SECRET_KEY_BASE", String.duplicate("s", 64))
    System.delete_env("POSTGRES_PASSWORD")
    assert_raise RuntimeError, ~r/POSTGRES_PASSWORD/, fn -> runtime() end
  end

  test "the release disables Erlang distribution (no epmd)" do
    env = File.read!(Path.expand("../../rel/env.sh.eex", __DIR__))
    assert env =~ ~s(RELEASE_DISTRIBUTION="${RELEASE_DISTRIBUTION:-none}")
  end

  test "the disabled mailer sends nothing and logs no recipient, subject or body" do
    email =
      Swoosh.Email.new(
        to: "someone@example.com",
        from: "x@example.com",
        subject: "Your RisiMe code: 123456",
        text_body: "code 123456"
      )

    log =
      capture_log([level: :info], fn ->
        assert {:ok, _} = RisiMe.Mailer.Disabled.deliver(email, [])
      end)

    refute log =~ "123456"
    refute log =~ "someone@example.com"
  end
end
