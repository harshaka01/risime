defmodule RisiMe.LedgerHelpers do
  @moduledoc """
  v1.27 S21+ test helpers (§27.2–§27.6): the ledger switch, Risi's fixed clock, a scripted
  `discussion_summarise`, and a quiet discussion of counted messages.
  """
  import RisiMe.RisiHelpers

  alias RisiMe.Workers.Risi, as: Job

  @doc "`RISI_LEDGER=on` (and Risi's clock restorable) for the test."
  def ledger_on! do
    restore_on_exit([:risi_ledger, :risi_now, :risi_commercial_provider_name])
    Application.put_env(:risime, :risi_ledger, true)
    :ok
  end

  @doc "Sets Risi's clock (`RisiMe.Agent.Clock.now/0`)."
  def clock!(%DateTime{} = at), do: Application.put_env(:risime, :risi_now, at)

  @doc """
  Buffers `n` counted messages alternating between `users`, 30 s apart from `start`; returns
  `{ids, last_at}`. `texts` (optional) gives the lines.
  """
  def talk!(conv, users, start, n \\ 6, texts \\ nil) do
    ids =
      for i <- 0..(n - 1) do
        u = Enum.at(users, rem(i, length(users)))
        text = if texts, do: Enum.at(texts, i), else: "line #{i} from #{u.display_name}"
        say!(conv, u, text, DateTime.add(start, i * 30, :second))
      end

    {ids, DateTime.add(start, (n - 1) * 30, :second)}
  end

  @doc "An item of the fake model's output."
  def item(body, owner, text, opts \\ []) do
    %{
      "text" => text,
      "owner" => ref_of(body, owner) || owner,
      "counterparts" => Enum.map(Keyword.get(opts, :to, []), &(ref_of(body, &1) || &1)),
      "due_local" => Keyword.get(opts, :due),
      "due_text" => Keyword.get(opts, :due_text),
      "source" => Keyword.get(opts, :source, []),
      "confidence" => Keyword.get(opts, :confidence, 0.9)
    }
  end

  @doc """
  Installs a fake model whose `discussion_summary` output is `fun.(body)` (items etc.); other
  tasks answer an empty commitment list.
  """
  def discussion_llm!(fun) do
    fake_llm!(fn
      "discussion_summary", body ->
        Map.merge(
          %{
            "key_points" => ["The quote needs the new transport cost."],
            "summary" => "Quote talk."
          },
          fun.(body)
        )

      _, _ ->
        %{"commitments" => []}
    end)
  end

  @doc "Runs the conversation's quiet check now (as the `discussion_quiet` job)."
  def quiet!(conv) do
    Oban.Testing.perform_job(Job, %{"kind" => "discussion_quiet", "conv" => conv},
      repo: RisiMe.Repo
    )
  end
end
