defmodule RisiMe.Agent.ActionGuard do
  @moduledoc """
  The consequential-action guard (principle 4 of `docs/ARCHITECTURE-PRINCIPLES.md`, decision 085,
  audit G9). **An agent never executes a consequential action**: money, pay, HR, deletion or
  anything legal. The named human approves, and only then may a human, or a tool the human
  triggered, act.

  **The one choke point.** Every Risi tool runs through `run/3` (the turn's §25 tool loop, the
  deep agents' `RisiMe.Agents.ToolRegistry.call/3` and the calendar tools' internal reads), and
  every write runs through `RisiMe.Agent.Writes`, which asks `must_ask?/2` before choosing
  "Allowed" and `exec_allowed?/3` before a write's `exec`. `test/risime/agent/action_guard_test.exs`
  scans `lib/` so no other code path can call a tool's `run` or `exec`.

  **Classification** (`classify/2`), deterministic first, conservative second:

    1. the tool **kind**: an explicitly allow-listed write (`@ordinary_writes`, today's reviewed
       write tools) is ordinary; a declared `consequential: <category>` is consequential; a tool
       **name** with a consequential word (`pay`, `salary`, `hire`, `delete`, `contract`, …) is
       consequential; a read (`write: false`) is ordinary;
    2. the **args** text, for writes that speak for the asker (`schedule_message`) and for every
       write the allow list doesn't know: money, pay, HR, deletion and legal words (English,
       Sinhala, Tamil) make it consequential;
    3. **unsure is consequential**: a write that is neither allow-listed nor declared
       `consequential: false` is `{:consequential, :unknown, :undeclared}`.

  **What happens to a consequential call.**

    * A reviewed write whose `run` only builds a proposal (`@ordinary_writes`, flagged by its
      args): the proposal is kept but **always asked** (never "Allowed", §26.3): the existing
      `confirm` card is the approval request, addressed (`for`) to the asker, the named human.
      Its `exec` runs only after that human's confirm (`via: "confirm"`).
    * Anything else: `run` is **never called**. The step is `denied` with a reason the model
      relays ("needs approval"), and an approval request is recorded for the named human
      (`ctx.approver`, else the asker). A dedicated approval card for another approver is
      `contract/proposals/2026-10-14-action-guard.md`.

  Every decision is a `risi_action_guard` row (ids, tool, category, basis, decision and the args'
  SHA-256; **never raw text**) and a log line with the same fields.

  **Flag** `RISI_ACTION_GUARD` (`RisiMe.Agents.Flags.raw/1`: table > env > default): `on` by
  default; only `off` turns it off (anything else counts as on: it only blocks).
  """
  use Ecto.Schema

  require Logger

  alias RisiMe.Agent.TurnSteps
  alias RisiMe.Repo

  @primary_key {:id, :binary_id, autogenerate: true}
  schema "risi_action_guard" do
    field :user_id, :binary_id
    field :approver_id, :binary_id
    field :conversation_id, :string
    field :tool, :string
    field :category, :string
    field :basis, :string
    field :decision, :string
    field :write_id, :binary_id
    field :args_sha256, :string
    field :inserted_at, :utc_datetime_usec
  end

  @categories ~w(money pay hr deletion legal unknown)a

  # Today's write tools, reviewed one by one (2026-10-14): each creates or changes only the
  # asker's own reminders, alarms, calendar entries or scheduled messages, or sends a PDF of
  # Risi's own message, always from a card (or the asker's "Allowed" for self-only writes).
  # `cancel_scheduled` cancels the asker's own unsent message: nothing delivered is deleted.
  # Their `run` only builds a `{:propose, card}`; `exec` runs after the confirm.
  @ordinary_writes ~w(set_reminder calendar_add risi_calendar_add set_alarm schedule_message
                      cancel_scheduled export_pdf)

  # Writes whose args are words the asker's phone sends to other people as the asker: the text
  # is checked too (a "you're hired" or "I accept the contract" is an HR or legal act).
  @args_checked ~w(schedule_message)

  # Tool-name words, per category (split on `_`, `.`, `-` and spaces).
  @name_words [
    pay: ~w(salary salaries payroll wage wages bonus payslip compensation),
    money:
      ~w(pay payment payments payout transfer refund invoice invoices purchase buy charge remit
         withdraw deposit loan billing bill wallet bank money),
    hr:
      ~w(hire hiring fire firing terminate termination dismiss onboard offboard promote promotion
         demote leave appraisal disciplinary resign recruit),
    deletion: ~w(delete erase purge wipe destroy remove drop truncate unsend retract shred),
    legal:
      ~w(contract contracts sign signature agreement nda legal lawsuit settle settlement consent
         waiver)
  ]

  # Args text (lowercased). Conservative: a false positive only means "ask a human".
  @text_patterns [
    pay: [
      ~r/\b(salar(y|ies)|payroll|wages?|bonus(es)?|payslips?|increments?|pay ?rise)\b/u,
      ~r/(වැටුප|සම්බළ|சம்பள|ஊதிய)/u
    ],
    money: [
      ~r/\b(pay|pays|paid|paying|payments?|payouts?|transfer(s|red|ring)?|refunds?|invoices?|remit\w*|wire (the )?money|deposit\w*|withdraw\w*|loans?|purchase\w*|reimburse\w*)\b/u,
      ~r/(\brs\.?|\blkr|\busd|\$|€|£)\s?\d/u,
      ~r/(ගෙව|මුදල්|பணம்|செலுத்த)/u
    ],
    hr: [
      ~r/\b(hire[ds]?|hiring|fired|firing|you'?re fired|terminat\w*|dismiss\w*|resign\w*|promot\w*|demot\w*|appraisals?|disciplinary|warning letter|offer letter|(annual|sick|casual|maternity|paternity|no.?pay) leave|leave (request|application|approv\w*)|approv\w* (his |her |their |your |the )?leave)\b/u,
      ~r/(නිවාඩු|රැකියාවෙන් ඉවත්|விடுப்பு|பணிநீக்க)/u
    ],
    deletion: [
      ~r/\b(delet\w*|eras\w*|purg\w*|wip(e|ed|ing)|destroy\w*|remove all|shred\w*)\b/u,
      ~r/(මකන්න|මකා|நீக்க|அழி)/u
    ],
    legal: [
      ~r/\b(contracts?|agreements?|sign(ed|ing)? (the|this|our|a|an)\b|signatures?|nda|legal\w*|lawsuits?|litigation|liabilit\w*|indemn\w*|settlements?|legally binding|terms and conditions)\b/u,
      ~r/(ගිවිසුම|නීති|ஒப்பந்த|சட்ட)/u
    ]
  ]

  @doc "The categories a consequential call can have."
  def categories, do: @categories

  @doc "The reviewed write tools that are ordinary by kind (the allow list)."
  def ordinary_writes, do: @ordinary_writes

  @doc "True unless `RISI_ACTION_GUARD` is `off` (only blocks: on is the safe default)."
  def on? do
    case RisiMe.Agents.Flags.raw("RISI_ACTION_GUARD") do
      v when is_binary(v) -> String.downcase(String.trim(v)) != "off"
      _ -> true
    end
  rescue
    _ -> true
  end

  ## Classification

  @doc """
  `:ordinary` or `{:consequential, category, basis}` for `tool` (a tool map, see
  `RisiMe.Agent.Tools`) called with `args`. `basis`: `:declared` (the tool says so), `:kind`
  (its name), `:args` (its args' text) or `:undeclared` (a write nobody reviewed).
  """
  def classify(%{name: name} = tool, args) do
    write? = Map.get(tool, :write, true)
    declared = Map.get(tool, :consequential, :undeclared)

    cond do
      declared in @categories -> {:consequential, declared, :declared}
      name in @ordinary_writes and name in @args_checked -> by_args(args, :ordinary)
      name in @ordinary_writes -> :ordinary
      cat = name_category(name) -> {:consequential, cat, :kind}
      write? == false -> :ordinary
      declared == false -> by_args(args, :ordinary)
      true -> by_args(args, {:consequential, :unknown, :undeclared})
    end
  end

  def classify(_tool, _args), do: {:consequential, :unknown, :undeclared}

  defp by_args(args, otherwise) do
    case text_category(args) do
      nil -> otherwise
      cat -> {:consequential, cat, :args}
    end
  end

  @doc "The consequential category a tool name points to, or nil."
  def name_category(name) when is_binary(name) do
    words = name |> String.downcase() |> String.split(~r/[_.\-\s]+/u, trim: true)

    Enum.find_value(@name_words, fn {cat, list} ->
      if Enum.any?(words, &(&1 in list)), do: cat
    end)
  end

  def name_category(_), do: nil

  @doc "The consequential category the args' text points to (every string, nested), or nil."
  def text_category(args) do
    text = args |> strings() |> Enum.join("\n") |> String.downcase()

    if text == "",
      do: nil,
      else:
        Enum.find_value(@text_patterns, fn {cat, regexes} ->
          if Enum.any?(regexes, &Regex.match?(&1, text)), do: cat
        end)
  end

  defp strings(s) when is_binary(s), do: [s]
  defp strings(%{} = m), do: m |> Map.values() |> Enum.flat_map(&strings/1)
  defp strings(l) when is_list(l), do: Enum.flat_map(l, &strings/1)
  defp strings(_), do: []

  ## The choke point

  @doc """
  Runs `tool` with `args` for `ctx`: **the only caller of a tool's `run`.** Ordinary (or the
  guard off): exactly `tool.run.(args, ctx)`. Consequential: see the module doc; the step gets
  `{:error, "denied", reason}` unless a reviewed write's proposal goes to its confirm card.
  """
  def run(tool, args, ctx) do
    if on?(),
      do: guarded(tool, args, ctx, classify(tool, args)),
      else: tool.run.(args, ctx)
  end

  defp guarded(tool, args, ctx, :ordinary), do: tool.run.(args, ctx)

  defp guarded(tool, args, ctx, {:consequential, cat, basis}) do
    if tool.name in @ordinary_writes do
      # A reviewed write: `run` only builds the card; the card must be asked (`must_ask?/2`).
      case tool.run.(args, ctx) do
        {:propose, card} ->
          record(ctx, tool.name, args, cat, basis, "confirm_required")
          {:propose, Map.put(card, :guard, cat)}

        other ->
          other
      end
    else
      record(ctx, tool.name, args, cat, basis, "approval_requested")

      {:error, "denied",
       "needs_approval: this is a #{cat} action. Risi never does these itself; tell the asker " <>
         "it needs approval by the responsible person, who must do it themselves"}
    end
  end

  @doc """
  True when a write proposal must be asked (its `confirm` card, never "Allowed"): the guard
  flagged it in `run/3` or its card's args are consequential.
  """
  def must_ask?(tool, card) do
    on?() and
      (Map.get(card, :guard) != nil or
         match?({:consequential, _, _}, classify(tool, Map.get(card, :args))))
  end

  @doc """
  The last check before a write's `exec` (`RisiMe.Agent.Writes`): an ordinary write runs; a
  consequential one runs only after its human's confirm (`via: "confirm"`), recorded as
  `approved`; otherwise it is `blocked` (never run).
  """
  def exec_allowed?(_w, nil, _args), do: true

  def exec_allowed?(w, tool, args) do
    with true <- on?(),
         {:consequential, cat, basis} <- classify(tool, args) do
      ctx = %{asker: w.user_id, conv: w.card_conversation_id, write_id: w.write_id}

      if w.via == "confirm" do
        record(ctx, tool.name, args, cat, basis, "approved")
        true
      else
        record(ctx, tool.name, args, cat, basis, "blocked")
        false
      end
    else
      _ -> true
    end
  end

  ## The record (no raw text)

  defp record(ctx, tool, args, cat, basis, decision) do
    asker = Map.get(ctx, :asker)
    approver = Map.get(ctx, :approver) || asker
    hash = hash(args)

    Logger.info(
      "risi action guard: tool=#{tool} category=#{cat} basis=#{basis} decision=#{decision} " <>
        "args_sha256=#{String.slice(hash, 0, 12)}"
    )

    Repo.insert!(%__MODULE__{
      user_id: uuid(asker),
      approver_id: uuid(approver),
      conversation_id: Map.get(ctx, :conv),
      tool: tool,
      category: to_string(cat),
      basis: to_string(basis),
      decision: decision,
      write_id: uuid(Map.get(ctx, :write_id)),
      args_sha256: hash,
      inserted_at: DateTime.utc_now()
    })

    :ok
  rescue
    e -> Logger.warning("risi action guard record failed: #{inspect(e.__struct__)}")
  end

  defp hash(args) do
    TurnSteps.args_hash(args)
  rescue
    _ -> TurnSteps.args_hash(%{})
  end

  defp uuid(v) when is_binary(v) do
    case Ecto.UUID.cast(v) do
      {:ok, id} -> id
      :error -> nil
    end
  end

  defp uuid(_), do: nil

  @doc "The recorded decisions of `user` (newest first; tests and the admin view)."
  def decisions(user) do
    import Ecto.Query
    Repo.all(from d in __MODULE__, where: d.user_id == ^user, order_by: [desc: d.inserted_at])
  end
end
