# mix run priv/repo/seeds.exs (idempotent). In a release: bin/risi_registry eval "RisiRegistry.Release.setup()".
{ask, allow} = RisiRegistry.Seeds.run()
IO.puts("seeded #{ask.agent_id} (#{ask.tier}, #{ask.status}); allow-list seed: #{inspect(allow)}")
