defmodule RisiRegistry.SeedsTest do
  use RisiRegistry.DataCase, async: false
  alias RisiRegistry.{AllowList, Registry, Seeds}

  setup do
    old = Application.get_env(:risi_registry, :allow_list_seed)
    on_exit(fn -> Application.put_env(:risi_registry, :allow_list_seed, old) end)
    :ok
  end

  test "RisiWork Ask is the first Official entry, as data only" do
    Application.put_env(:risi_registry, :allow_list_seed, nil)
    {ask, :no_seed} = Seeds.run()

    assert ask.agent_id == "risicloud.ai/risiwork-ask"
    assert ask.tier == "official"
    assert ask.status == "proposed"
    assert ask.verification == "unverified"
    assert ask.data_classes == ["own_figures"]
    assert ask.consequential == false
    assert [%{"label" => "Reads your company's bills, spending and reports"}] = ask.scopes

    # Data only: not discoverable, not callable.
    assert Registry.discover() == []
    assert {:error, {:not_active, "proposed"}} = Registry.check(ask.agent_id)

    # Idempotent.
    {again, _} = Seeds.run()
    assert again.id == ask.id
  end

  test "the allow-list starts with the one entry from config, case-insensitive" do
    Application.put_env(:risi_registry, :allow_list_seed, "Person@Example.TEST")
    assert AllowList.count() == 0
    Seeds.run()
    Seeds.run()
    assert AllowList.count() == 1
    assert AllowList.allowed?("person@example.test")
    refute AllowList.allowed?("someone@example.test")
    refute AllowList.allowed?(nil)
  end
end
