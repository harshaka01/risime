defmodule RisiMe.Agent.Writes do
  @moduledoc """
  Write proposals and the confirm flow (contract v1.25 §25.4, v1.26 §26.3/§26.5; server S13).

  **A write is never run without its asker's confirm** (or, from S14, the asker's "Allowed"
  for a write that affects only them, §26.3). A turn step of a write tool returns `{:propose,
  card}`; `propose/3` then:

    1. stores a `risi_pending_writes` row: a fresh `write_id`, the asker, the conversation of
       the request and the one the card goes to, the tool, its skill, and its **args sealed with
       `RISI_DATA_KEY`** (wiped when the write is done, cancelled or void; the row is deleted
       when the card expires, 24 h after it was sent);
    2. posts the `confirm` card by the audience rule (§25.1): a personal confirm (calendar,
       alarm, a "remind me", a scheduled message) goes to the asker's Risi chat, one for the
       conversation (a group reminder) to that conversation. The turn's own `final` carries the
       pointer line, so the group never gets two;
    3. sends `risi_progress` `waiting_confirm`; the step's result to the model is
       `{"status": "waiting_confirm", "write_id": …}`.

  **`risi_action`** (`act/4`, from the action job: the sender is the attested user of the
  sending leaf, an active human member): `confirm_write`/`cancel_write` are honoured only from
  the user in the card's `for` (the asker), in the conversation the card was posted in, before
  `expires_at`; anything else is ignored (§25.4: a forged confirm is ignored). `confirm_write`
  runs the write **without a model call** (the tool's `exec`), once: a repeated confirm of a
  done write is a no-op; a write that failed or timed out may run again (Retry) while its card
  lives. `cancel_write` posts nothing.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Audience, Out, Progress, Seal, Tools}
  alias RisiMe.Repo

  defmodule Write do
    @moduledoc false
    use Ecto.Schema

    @primary_key {:write_id, :binary_id, autogenerate: false}
    schema "risi_pending_writes" do
      field :user_id, :binary_id
      field :conversation_id, :string
      field :card_conversation_id, :string
      field :request_id, :binary_id
      field :turn_id, :binary_id
      field :device_id, :binary_id
      field :tool, :string
      field :skill_id, :string
      field :args, :binary
      field :state, :string, default: "pending"
      field :via, :string
      field :personal, :boolean, default: false
      field :card_message_id, :string
      field :expires_at, :utc_datetime_usec
      field :confirmed_at, :utc_datetime_usec
      field :inserted_at, :utc_datetime_usec
    end
  end

  @ttl_s 24 * 3600

  @doc "How long a confirm card lives (24 h, §25.4)."
  def ttl_s, do: @ttl_s

  ## Proposals (from a turn step)

  @doc """
  A write tool's proposal in a turn. `card`: `args` (what `exec` needs, sealed), `summary`,
  `when` (`%{"start", "end", "all_day"}`), `text`, `personal` (bool), and optionally
  `skill_id` and `card_args` (a client tool's exact args without `write_id`, §26.5). Returns
  the step's `{:ok, result, meta}` or `{:error, status}`.
  """
  def propose(ctx, tool, card) do
    case mode(ctx, tool, card) do
      :allowed -> allowed(ctx, tool, card)
      :ask -> ask(ctx, tool, card)
    end
  end

  # §26.3: "Allowed" or ask (`RisiMe.Agent.Skills`).
  defp mode(ctx, tool, card), do: RisiMe.Agent.Skills.write_mode(ctx, tool, card)

  defp ask(ctx, tool, card) do
    wid = Ecto.UUID.generate()

    target =
      case Audience.target(ctx, card.personal) do
        {:here, conv} -> conv
        {:risi_chat, rc} -> rc
        # No Risi chat (a v1.24/v1.25 asker): the request's own text, in its own conversation.
        :nowhere -> ctx.conv
      end

    w = insert!(ctx, tool, card, wid, target, "pending", nil)
    expires = DateTime.add(w.inserted_at, @ttl_s, :second)

    risi =
      %{
        "kind" => "confirm",
        "request_id" => ctx.request_id,
        "write_id" => wid,
        "tool" => tool.name,
        "summary" => card.summary,
        "when" => card.when,
        "text" => card.text,
        "for" => [ctx.asker],
        "buttons" => ["add", "cancel"],
        "expires_at" => RisiMe.Agent.Clock.ts(expires),
        "turn_ref" => ctx.turn_id,
        "call_ref" => Map.get(ctx, :call_ref),
        "notify" => [ctx.asker]
      }
      |> put_some("skill_id", card[:skill_id])
      |> put_some("args", card[:card_args])

    case Out.post(target, card.summary <> "? Update RisiMe to answer.", risi) do
      {:ok, %{message_id: mid}} ->
        w |> Ecto.Changeset.change(card_message_id: mid) |> Repo.update!()
        Progress.send(ctx.asker, ctx.request_id, ctx.conv, "waiting_confirm")

        {:ok, %{"status" => "waiting_confirm", "write_id" => wid},
         %{personal: target != ctx.conv}}

      {:error, reason} ->
        Logger.warning("Risi confirm card not sent: #{inspect(reason)}")
        Repo.delete(w)
        {:error, "failed"}
    end
  end

  # §26.3: an allowed write runs now, in the turn, for the asker's own request.
  defp allowed(ctx, tool, card) do
    wid = Ecto.UUID.generate()
    rc = RisiMe.RisiChat.active_id(ctx.asker) || ctx.conv
    w = insert!(ctx, tool, card, wid, rc, "running", "allowed")

    case execute(w, card.args, ctx.device_id) do
      :ok -> {:ok, %{"status" => "done", "write_id" => wid}, %{personal: rc != ctx.conv}}
      {:error, status} -> {:error, status}
    end
  end

  defp insert!(ctx, tool, card, wid, target, state, via) do
    {:ok, key} = Seal.data()
    now = DateTime.utc_now()

    Repo.insert!(%Write{
      write_id: wid,
      user_id: ctx.asker,
      conversation_id: ctx.conv,
      card_conversation_id: target,
      request_id: ctx.request_id,
      turn_id: ctx.turn_id,
      device_id: ctx.device_id,
      tool: tool.name,
      skill_id: card[:skill_id],
      args: Seal.seal(key, aad(wid), card.args),
      state: state,
      via: via,
      personal: card.personal,
      expires_at: DateTime.add(now, @ttl_s, :second),
      confirmed_at: if(state == "running", do: now),
      inserted_at: now
    })
  end

  defp put_some(map, _k, nil), do: map
  defp put_some(map, k, v), do: Map.put(map, k, v)

  defp aad(wid), do: "risi_pending_writes:" <> wid

  ## Actions (§25.4)

  @doc """
  A `confirm_write` / `cancel_write` from `user` (sending device `device`) in `conv`. Oban
  result (always `:ok`: a refused or repeated action is ignored).
  """
  def act(conv, user, device, %{"action" => action, "target" => target})
      when action in ~w(confirm_write cancel_write) do
    with {:ok, wid} <- Ecto.UUID.cast(target),
         %Write{} = w <- Repo.get(Write, wid),
         true <- w.card_conversation_id == conv and w.user_id == user,
         true <- DateTime.compare(DateTime.utc_now(), w.expires_at) == :lt do
      if action == "cancel_write", do: cancel(w), else: confirm(w, device)
    else
      _ -> :ok
    end
  end

  def act(_conv, _user, _device, _env), do: :ok

  defp cancel(%Write{state: "pending"} = w) do
    finish(w, "cancelled")
    :ok
  end

  defp cancel(_w), do: :ok

  defp confirm(%Write{state: "void"} = w, _device), do: void_reply(w)

  defp confirm(%Write{state: state} = w, device) when state in ~w(pending confirmed) do
    # Checked again when it runs (§25.1, §26.5): a revoked skill voids the card.
    if gate(w) == :ok do
      now = DateTime.utc_now()

      {n, _} =
        Repo.update_all(
          from(x in Write, where: x.write_id == ^w.write_id and x.state == ^state),
          set: [state: "running", via: "confirm", confirmed_at: w.confirmed_at || now]
        )

      if n == 1 do
        w = Repo.get!(Write, w.write_id)

        case open_args(w) do
          {:ok, args} -> execute(w, args, device)
          :error -> finish(w, "void")
        end
      end

      :ok
    else
      finish(w, "void")
      void_reply(w)
    end
  end

  defp confirm(_w, _device), do: :ok

  # §26.5: the skill's gates at confirm time; a void card gets `skill_needed`.
  defp gate(w), do: RisiMe.Agent.Skills.confirm_gate(w)

  defp void_reply(w), do: RisiMe.Agent.Skills.void_reply(w)

  # Runs the write once: `exec` posts its own result. `:ok` → done (args wiped); anything else
  # → `confirmed` (may run again while the card lives).
  defp execute(%Write{} = w, args, device) do
    tool = Tools.get(w.tool) || Tools.known(w.tool)

    result =
      try do
        tool.exec.(w, args, device)
      rescue
        e ->
          Logger.warning("Risi write #{w.tool} failed: #{inspect(e.__struct__)}")
          {:error, "failed"}
      end

    case result do
      :ok ->
        finish(w, "done")
        :ok

      {:error, status} ->
        if w.via == "allowed",
          do: finish(w, "void"),
          else: w |> Ecto.Changeset.change(state: "confirmed") |> Repo.update!()

        {:error, status}
    end
  end

  defp open_args(w) do
    with {:ok, key} <- Seal.data(),
         bin when is_binary(bin) <- w.args,
         {:ok, args} <- Seal.open(key, aad(w.write_id), bin) do
      {:ok, args}
    else
      _ -> :error
    end
  end

  defp finish(w, state) do
    Repo.update_all(from(x in Write, where: x.write_id == ^w.write_id),
      set: [state: state, args: nil]
    )
  end

  ## Queries

  @doc """
  True when `write_id` has a confirmed write (§25.3 `409 write_not_confirmed` otherwise): the
  asker's confirm (or their "Allowed", §26.3) has been recorded.
  """
  def confirmed?(write_id) do
    Repo.exists?(
      from w in Write,
        where:
          w.write_id == ^write_id and w.state in ~w(running confirmed done) and
            not is_nil(w.confirmed_at)
    )
  end

  @doc "A write by id (or nil)."
  def get(write_id), do: Repo.get(Write, write_id)

  @doc "§26.5 revoke: every open card of `skill_id` for `user` is void (its args wiped)."
  def void_skill(user, skill_id) do
    Repo.update_all(
      from(w in Write,
        where: w.user_id == ^user and w.skill_id == ^skill_id and w.state in ~w(pending confirmed)
      ),
      set: [state: "void", args: nil]
    )

    :ok
  end

  @doc "Posts a write's result where its card is (or, for a personal write, the Risi chat)."
  def post(%Write{} = w, body, risi) do
    case Out.post(w.card_conversation_id, body, risi) do
      {:ok, _} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  @doc "Deletes the writes of a conversation (Official off, removal, a Risi chat left)."
  def forget(conv) do
    Repo.delete_all(
      from w in Write, where: w.conversation_id == ^conv or w.card_conversation_id == ^conv
    )

    :ok
  end

  @doc "Deletes rows whose card expired (their args with them)."
  def prune(now \\ DateTime.utc_now()) do
    Repo.delete_all(from w in Write, where: w.expires_at < ^now)
    :ok
  end
end
