defmodule RisiMe.Agent.Secretary do
  @moduledoc """
  Risi stage 1, the secretary (§24.11–§24.13, decision 066). Harsha's rules are binding here:
  **no product pushing; nothing tracked without ✓; Risi only sees Official; learn and delete.**

  `on_message/2` is called by `RisiMe.Agent.Conversation` right after an active human member's
  Official message was decrypted and buffered (`Agent.Transcript`). It never calls a model and
  never sends from the conversation's own process (that would deadlock its MLS lane): it only
  parses the envelope `type` and enqueues Oban jobs whose args are **ids only, never text**
  (`RisiMe.Workers.Risi`):

    * `text` → a debounced commitment extraction of the conversation (queue `risi`, one pending
      job per conversation);
    * `risi_request` → a request job (queue `risi_requests`, scheduled later when the user is
      over 20 a minute), or (more than 50 pending, §25.6) an `error` `queue_overflow` reply.
      Every decision logs `risi limit: user=<hash8> chat=<id8> decision=… reason=…`;
    * `risi_action` → an action job (queue `risi_timers`);
    * anything else → nothing. Free text is never parsed for intent.

  The jobs read the text back from the sealed buffer by message id.

  Deletion (§24.4, §24.12, §15): `forget/1` and `message_deleted/2`.
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{Commitment, Fact, Transcript}
  alias RisiMe.{RateLimiter, Repo, TimeUUID}
  alias RisiMe.Workers.Risi, as: Job

  @extract_delay_s 20

  ## Entry point (from Agent.Conversation)

  @doc "A buffered Official message of an active human member (see the module doc)."
  def on_message(conv, %{message_id: id, sender_id: user, plaintext: pt}) do
    if RisiMe.Agent.official?(conv) do
      case Jason.decode(pt) do
        # v1.25 §25.2: a plain `text` in a Risi chat is never a request and never extracted.
        {:ok, %{"type" => "text"}} -> unless risi_chat?(conv), do: schedule_extract(conv)
        {:ok, %{"type" => "risi_request"} = env} -> request(conv, id, user, env)
        {:ok, %{"type" => "risi_action"}} -> action(conv, id, user)
        _ -> :ok
      end
    else
      :ok
    end
  rescue
    e ->
      # Never the content: only the exception's type.
      Logger.warning("Risi secretary skipped a message in #{conv}: #{inspect(e.__struct__)}")
      :ok
  end

  defp risi_chat?(conv), do: RisiMe.Groups.Tabs.risi_chat?(conv)

  @doc "Enqueues the debounced extraction of `conv` (at most one pending job per conversation)."
  def schedule_extract(conv, delay_s \\ nil) do
    delay = delay_s || Application.get_env(:risime, :risi_extract_delay_s, @extract_delay_s)

    %{"kind" => "extract", "conv" => conv}
    |> Job.new(
      queue: :risi,
      schedule_in: delay,
      unique: [
        period: :infinity,
        keys: [:kind, :conv],
        states: [:available, :scheduled, :retryable]
      ]
    )
    |> Oban.insert()

    :ok
  end

  # §24.13/§25.6: 20 per user per minute (sliding window). Over it a request is never refused: its
  # job is scheduled for when the window frees, one slot `@req_spacing_s` after the user's
  # previous queued one (order kept). Only a user with more than 50 pending requests gets the
  # `queue_overflow` reply (hotfix 2026-10-09: no per-chat cap, never `rate_limited` in normal
  # use).
  @req_per_min 20
  @req_queue_max 50
  @req_spacing_s 3
  @pending_states ~w(available scheduled retryable)

  defp request(conv, id, user, env) do
    with rid when is_binary(rid) <- env["request_id"],
         {:ok, _} <- Ecto.UUID.cast(rid),
         true <- env["action"] in ~w(ask summarise report) do
      args = %{
        "kind" => "request",
        "conv" => conv,
        "message_id" => id,
        "request_id" => rid,
        "user_id" => user,
        "t0" => System.system_time(:second)
      }

      per_min = Application.get_env(:risime, :risi_req_per_min, @req_per_min)
      window = Application.get_env(:risime, :risi_req_window_ms, :timer.minutes(1))
      {count, last} = pending_requests(user)

      cond do
        count == 0 and RateLimiter.hit_if_allowed(:risi_req_user, user, per_min, window) == :ok ->
          log_limit(user, conv, "accepted", "under_limit")
          insert_unique(args, :risi_requests, [:kind, :request_id])

        count >= @req_queue_max ->
          log_limit(user, conv, "refused", "pending_over_#{@req_queue_max}")
          limited_reply(args, user)

        true ->
          log_limit(
            user,
            conv,
            "queued",
            if(count > 0, do: "pending=#{count}", else: "over_#{per_min}_per_min")
          )

          # v1.25 §25.4: the bubble shows the place in the asker's queue.
          if RisiMe.Risi.tools_on?(),
            do: RisiMe.Agent.Progress.send(user, rid, conv, "queued", position: count + 1)

          spacing = Application.get_env(:risime, :risi_req_spacing_s, @req_spacing_s)
          first = DateTime.add(DateTime.utc_now(), RateLimiter.retry_after_s(window), :second)

          at =
            if last,
              do: Enum.max([first, DateTime.add(last, spacing, :second)], DateTime),
              else: first

          insert_unique(Map.put(args, "queued", true), :risi_requests, [:kind, :request_id], at)
      end
    else
      _ -> :ok
    end
  end

  # One `queue_overflow` reply per user per minute; further ones are dropped silently.
  defp limited_reply(args, user) do
    if RateLimiter.hit_if_allowed(:risi_req_limited_reply, user, 1, :timer.minutes(1)) == :ok,
      do:
        insert_unique(Map.put(args, "error", "queue_overflow"), :risi_requests, [
          :kind,
          :request_id
        ]),
      else: :ok
  end

  @doc """
  One log line per limiter decision (hotfix 2026-10-09): ids are hashed or shortened, never
  content. `decision` = `accepted` | `queued` | `refused`.
  """
  def log_limit(user, conv, decision, reason) do
    Logger.info(
      "risi limit: user=#{RisiMe.Push.Dispatcher.user_hash(user)} chat=#{short(conv)} " <>
        "decision=#{decision} reason=#{reason}"
    )
  end

  defp short("grp:" <> id), do: String.slice(id, 0, 8)
  defp short(id), do: id |> to_string() |> String.slice(0, 8)

  # {pending queued requests of the user, scheduled_at of the latest one}
  defp pending_requests(user) do
    Repo.one(
      from j in Oban.Job,
        where:
          j.queue == "risi_requests" and j.state in ^@pending_states and
            fragment("?->>'user_id' = ?", j.args, ^user) and
            fragment("?->>'queued' = 'true'", j.args),
        select: {count(j.id), max(j.scheduled_at)}
    )
  end

  defp action(conv, id, user),
    do:
      insert_unique(
        %{"kind" => "action", "conv" => conv, "message_id" => id, "user_id" => user},
        :risi_timers,
        [:kind, :message_id]
      )

  defp insert_unique(args, queue, keys, scheduled_at \\ nil) do
    opts = [queue: queue, unique: [period: 86_400, keys: keys]]
    opts = if scheduled_at, do: [{:scheduled_at, scheduled_at} | opts], else: opts

    args
    |> Job.new(opts)
    |> Oban.insert()

    :ok
  end

  ## Reading the buffer (jobs)

  @doc """
  The decoded envelope of one buffered message, or nil (gone, deleted, or not buffered).
  """
  def envelope(conv, message_id) do
    before = message_id |> TimeUUID.to_datetime() |> DateTime.add(-1, :millisecond)

    conv
    |> Transcript.list(TimeUUID.at(before), 100)
    |> Enum.find_value(fn row ->
      if row.message_id == message_id do
        case Jason.decode(row.plaintext) do
          {:ok, %{} = env} ->
            env
            |> Map.put("__sender", row.sender_id)
            |> Map.put("__device", row.sender_device)
            |> Map.put("__ts", RisiMe.Agent.Clock.ts(TimeUUID.to_datetime(row.message_id)))

          _ ->
            nil
        end
      end
    end)
  end

  @doc """
  The buffered `text` messages of `conv` (oldest first), optionally only from `since` on:
  `[%{message_id, sender_id, text}]`. Other envelope types (requests, actions, media) are left
  out.
  """
  def text_messages(conv, since \\ nil, limit \\ 2_000) do
    after_id = if since, do: TimeUUID.at(DateTime.add(since, -1, :millisecond)), else: nil

    for row <- Transcript.list(conv, after_id, limit),
        {:ok, %{"type" => "text", "body" => body}} <- [Jason.decode(row.plaintext)],
        is_binary(body) and body != "" do
      %{message_id: row.message_id, sender_id: row.sender_id, text: body}
    end
  end

  @doc "Active human members of `conv` with names, in join order: `[%{user_id, name}]`."
  def members(conv) do
    Repo.all(
      from m in RisiMe.Groups.Member,
        join: u in RisiMe.Accounts.User,
        on: u.id == m.user_id,
        where:
          m.group_id == ^conv and m.state == "active" and m.kind == "user" and u.kind == "user",
        order_by: [asc: m.inserted_at, asc: m.user_id],
        select: %{user_id: m.user_id, name: u.display_name}
    )
    |> Enum.map(fn m -> %{m | name: m.name || "Member"} end)
  end

  @doc "True when `user` is an active human member of `conv`."
  def active_human?(conv, user) do
    case RisiMe.Groups.member(conv, user) do
      %{state: "active", kind: "user"} -> not RisiMe.Risi.agent?(user)
      _ -> false
    end
  end

  @doc "The chat id of an Official conversation (the Private anchor's id)."
  def chat_id(conv) do
    case RisiMe.Groups.get_group(conv) do
      %{chat_id: c} when is_binary(c) -> c
      _ -> conv
    end
  end

  @doc "The chat's clock: the most common zone among its active human members."
  def chat_tz(conv) do
    case members(conv) do
      [] ->
        RisiMe.Agent.Clock.default_tz()

      ms ->
        ms
        |> Enum.map(& &1.user_id)
        |> RisiMe.Agent.Clock.user_tzs()
        |> Map.values()
        |> Enum.frequencies()
        |> Enum.max_by(fn {_tz, n} -> n end)
        |> elem(0)
    end
  end

  ## Deletion

  @doc """
  Deletes everything Risi derived from `conv` (§24.4 Official off, removal): its buffer rows,
  commitments, facts (and their embeddings, by cascade), chat state and pending jobs. The
  learning log keeps only what §24.12 allows (no raw text by construction).
  """
  def forget(conv) do
    Transcript.purge(conv)
    cancel_jobs(conv)
    # v1.25 §25.4: its confirm cards' writes (sealed args) go too.
    RisiMe.Agent.Writes.forget(conv)
    RisiMe.Agent.Reminders.forget(conv)

    Repo.transaction(fn ->
      Repo.delete_all(from f in Fact, where: f.conversation_id == ^conv)
      Repo.delete_all(from c in Commitment, where: c.conversation_id == ^conv)
      Repo.delete_all(from s in "risi_chat_state", where: s.conversation_id == ^conv)
    end)

    :ok
  end

  @doc "Cancels the conversation's pending Risi jobs."
  def cancel_jobs(conv) do
    Oban.cancel_all_jobs(
      from j in Oban.Job,
        where:
          j.worker == "RisiMe.Workers.Risi" and
            j.state in ["available", "scheduled", "retryable"] and
            fragment("?->>'conv' = ?", j.args, ^conv)
    )

    :ok
  end

  @doc """
  §15 delete for everyone in an Official conversation (§24.12): facts and commitments derived
  **only** from the deleted messages are deleted (their jobs cancelled); others lose those ids.
  """
  def message_deleted(conv, [_ | _] = ids) do
    ids = Enum.uniq(ids)

    for c <-
          Repo.all(
            from c in Commitment,
              where:
                c.conversation_id == ^conv and
                  fragment("? && ?::varchar[]", c.source_message_ids, ^ids)
          ) do
      case c.source_message_ids -- ids do
        [] ->
          RisiMe.Agent.Commitments.cancel_timers(c.id)
          Repo.delete!(c)

        rest ->
          c |> Ecto.Changeset.change(source_message_ids: rest) |> Repo.update!()
      end
    end

    for f <-
          Repo.all(
            from f in Fact,
              where:
                f.conversation_id == ^conv and
                  fragment("? && ?::varchar[]", f.source_message_ids, ^ids)
          ) do
      case f.source_message_ids -- ids do
        [] -> Repo.delete!(f)
        rest -> f |> Ecto.Changeset.change(source_message_ids: rest) |> Repo.update!()
      end
    end

    :ok
  end

  def message_deleted(_conv, _ids), do: :ok
end
