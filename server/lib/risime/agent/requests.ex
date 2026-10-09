defmodule RisiMe.Agent.Requests do
  @moduledoc """
  `risi_request` (§24.11): `ask`, `summarise` and `report` over the sealed buffer, which holds at
  most 24 h. `scope.since` further back than 24 h gets the `error` `out_of_window`; an empty
  window gets `nothing_to_summarise`; a model that is down or fails gets `model_unavailable`; a
  queue of 50+ pending requests (or 200 a day in a chat) gives `rate_limited`; the global queue and
  the per-user limit make a request wait instead. Every reply notifies the requester only.
  """
  import Ecto.Query

  alias RisiMe.Agent.{Clock, Commitment, LLM, Out, Prompts, Secretary}
  alias RisiMe.Repo

  @window_s 24 * 3600
  @max_wait_s 600
  # Clock skew between a phone and the server.
  @slack_s 300

  @doc "Answers one request (args of `RisiMe.Workers.Risi`). Oban result."
  def handle(conv, message_id, request_id, user, error \\ nil)

  def handle(conv, _message_id, request_id, user, code) when is_binary(code),
    do: reply_error(conv, request_id, user, code)

  def handle(conv, message_id, request_id, user, nil) do
    handle(conv, message_id, request_id, user, nil, nil)
  end

  @doc false
  def handle(conv, message_id, request_id, user, nil, t0) do
    Process.put(:risi_req_t0, t0)

    case Secretary.envelope(conv, message_id) do
      %{"type" => "risi_request", "request_id" => ^request_id, "action" => action} = env ->
        now = DateTime.utc_now()

        case since(env, now) do
          :out_of_window -> reply_error(conv, request_id, user, "out_of_window")
          {:ok, since} -> run(action, conv, request_id, user, env, since, now)
        end

      _ ->
        :ok
    end
  end

  defp waited_s do
    case Process.get(:risi_req_t0) do
      t0 when is_integer(t0) -> System.system_time(:second) - t0
      _ -> 0
    end
  end

  defp since(env, now) do
    floor = DateTime.add(now, -@window_s, :second)

    case get_in(env, ["scope", "since"]) do
      s when is_binary(s) ->
        case DateTime.from_iso8601(s) do
          {:ok, dt, _} ->
            if DateTime.diff(floor, dt) > @slack_s,
              do: :out_of_window,
              else: {:ok, Enum.max([dt, floor], DateTime)}

          _ ->
            {:ok, floor}
        end

      _ ->
        {:ok, floor}
    end
  end

  defp run("ask", conv, rid, user, env, since, now) do
    q = env["text"]

    if is_binary(q) and String.length(String.trim(q)) in 1..1000 do
      case Secretary.text_messages(conv, since) do
        [] ->
          reply_error(conv, rid, user, "nothing_to_summarise")

        msgs ->
          {text, refs} = render(conv, msgs, now)

          complete(conv, rid, user, %{
            task: "ask",
            system: Prompts.answer_system(),
            user: text <> Prompts.question(String.trim(q)),
            schema_name: "answer",
            schema: Prompts.answer_schema(),
            msgs: msgs,
            max_tokens: 600,
            reply: fn out, call_ref, conf ->
              ids = out["refs"] |> Enum.map(&refs.messages[&1]) |> Enum.reject(&is_nil/1)

              {out["answer"],
               %{
                 "kind" => "answer",
                 "request_id" => rid,
                 "answer" => out["answer"],
                 "refs" => Enum.uniq(ids),
                 "confidence" => conf,
                 "call_ref" => call_ref
               }}
            end
          })
      end
    else
      # A malformed ask (no or too long text) is ignored, as any invalid envelope.
      :ok
    end
  end

  defp run("summarise", conv, rid, user, _env, since, now) do
    case Secretary.text_messages(conv, since) do
      [] ->
        reply_error(conv, rid, user, "nothing_to_summarise")

      msgs ->
        {text, refs} = render(conv, msgs, now)

        complete(conv, rid, user, %{
          task: "summarise",
          system: Prompts.summary_system(),
          user: text,
          schema_name: "summary",
          schema: Prompts.summary_schema(),
          msgs: msgs,
          max_tokens: 1_000,
          reply: fn out, call_ref, _conf ->
            {"Summary: " <> out["summary"],
             %{
               "kind" => "summary",
               "request_id" => rid,
               "summary" => out["summary"],
               "decisions" => out["decisions"],
               "action_items" => out["action_items"],
               "open_questions" => out["open_questions"],
               "partial" => refs.partial,
               "call_ref" => call_ref
             }}
          end
        })
    end
  end

  defp run("report", conv, rid, user, _env, since, now) do
    msgs = Secretary.text_messages(conv, since)
    items = tracked(conv)

    if msgs == [] and items == [] do
      reply_error(conv, rid, user, "nothing_to_summarise")
    else
      {text, _refs} = render(conv, msgs, now)

      complete(conv, rid, user, %{
        task: "report",
        system: Prompts.report_system(),
        user: text <> Prompts.commitments_block(items),
        schema_name: "report",
        schema: Prompts.report_schema(),
        msgs: msgs,
        max_tokens: 1_400,
        reply: fn out, call_ref, _conf ->
          {"Report: " <> out["title"],
           %{
             "kind" => "report",
             "request_id" => rid,
             "title" => out["title"],
             "period" => %{"from" => Clock.ts(since), "to" => Clock.ts(now)},
             "sections" => out["sections"],
             "call_ref" => call_ref
           }}
        end
      })
    end
  end

  defp run(_action, _conv, _rid, _user, _env, _since, _now), do: :ok

  defp render(conv, msgs, now) do
    members = Secretary.members(conv)
    Prompts.render(members, msgs, Secretary.chat_tz(conv), now: now)
  end

  # Derived data only (no chat text): the chat's tracked and done commitments.
  defp tracked(conv) do
    names = Map.new(Secretary.members(conv), &{&1.user_id, &1.name})

    Repo.all(
      from c in Commitment,
        where: c.conversation_id == ^conv and c.state in ["confirmed", "edited", "done"],
        order_by: [asc: c.inserted_at]
    )
    |> Enum.map(fn c ->
      %{
        "text" => c.text,
        "owner" => names[c.owner_id],
        "state" => c.state,
        "due" => Clock.ts(c.due)
      }
    end)
  end

  defp complete(conv, rid, user, t) do
    req = %{
      task: t.task,
      conversation_id: conv,
      chat_id: Secretary.chat_id(conv),
      system: t.system,
      user: t.user,
      schema_name: t.schema_name,
      schema: t.schema,
      source_message_ids: Enum.map(t.msgs, & &1.message_id),
      max_tokens: t.max_tokens
    }

    case LLM.complete(req) do
      {:ok, %{call_ref: call_ref, output: out, confidence: conf}} ->
        {body, risi} = t.reply.(out, call_ref, conf)
        post(conv, body, Map.put(risi, "notify", [user]))

      # The global in-flight queue is full: wait (the job snoozes), answer late rather than
      # refuse; only after 10 minutes of waiting the polite error.
      {:error, :rate_limited} ->
        if waited_s() > @max_wait_s,
          do: reply_error(conv, rid, user, "rate_limited"),
          else: {:snooze, 10}

      {:error, _} ->
        reply_error(conv, rid, user, "model_unavailable")
    end
  end

  @bodies %{
    "model_unavailable" => "I can't answer right now. Please try again later.",
    "rate_limited" => "Too many requests right now. Please try again in a few minutes.",
    "nothing_to_summarise" => "There's nothing to summarise in that period.",
    "out_of_window" => "I can only summarise the last 24 hours."
  }

  @doc "Sends the §24.11 `error` envelope for a request."
  def reply_error(conv, rid, user, code) do
    post(conv, Map.fetch!(@bodies, code), %{
      "kind" => "error",
      "request_id" => rid,
      "code" => code,
      "notify" => [user]
    })
  end

  defp post(conv, body, risi) do
    case Out.post(conv, body, risi) do
      {:ok, _} -> :ok
      {:error, :rate_limited} -> {:snooze, 10}
      {:error, :not_member} -> :ok
      {:error, :private_tab} -> :ok
      {:error, reason} -> {:error, reason}
    end
  end
end
