defmodule RisiMe.Agent.Requests do
  @moduledoc """
  `risi_request` (§24.11): `ask`, `summarise` and `report` over the sealed buffer, which holds at
  most 24 h. `scope.since` further back than 24 h gets the `error` `out_of_window`; an empty
  window gets `nothing_to_summarise` for a summary or report (an `ask` always goes to the model,
  which knows what Risi can do: `RisiMe.Agent.Capabilities`). The global queue, the per-user
  limit and a model that is down make a request wait (the job snoozes) and answer late; only
  after 10 minutes of waiting comes `queue_overflow` (global queue) or `model_unavailable`
  (hotfix 2026-10-09: never `rate_limited` in normal use). An answer saying "the transcript does
  not contain …" is retried once (§25.1 post-check), and ref labels ("Message 1") are removed
  from model text: sources are real message refs only. Every reply notifies the requester only.
  """
  import Ecto.Query

  alias RisiMe.Agent.{Capabilities, Clock, Commitment, LLM, Out, Prompts, Secretary}
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
      # v1.25 §25.1: with RISI_TOOLS=on an `ask` is a turn of the tool loop.
      %{"type" => "risi_request", "request_id" => ^request_id, "action" => action} = env ->
        if action == "ask" and RisiMe.Risi.tools_on?() and valid_question?(env["text"]),
          do: RisiMe.Agent.Turn.run(conv, env, request_id, user),
          else: v124(conv, env, request_id, user)

      _ ->
        :ok
    end
  end

  defp valid_question?(q) when is_binary(q), do: String.length(String.trim(q)) in 1..1000
  defp valid_question?(_q), do: false

  # The v1.24 secretary path (`summarise`, `report`, and `ask` while RISI_TOOLS is off).
  defp v124(conv, %{"action" => action} = env, request_id, user) do
    now = DateTime.utc_now()

    case since(env, now) do
      :out_of_window -> reply_error(conv, request_id, user, "out_of_window")
      {:ok, since} -> run(action, conv, request_id, user, env, since, now)
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
      # An empty window still goes to the model: "what can you do" needs no chat lines.
      msgs = Secretary.text_messages(conv, since)
      {text, refs} = render(conv, msgs, now)

      complete(conv, rid, user, %{
        task: "ask",
        system: Prompts.answer_system(),
        user: text <> Prompts.question(String.trim(q)),
        schema_name: "answer",
        schema: Prompts.answer_schema(),
        msgs: msgs,
        max_tokens: 600,
        post_check: true,
        reply: fn out, call_ref, conf ->
          # Only refs the server handed out become sources (real message ids); labels go.
          ids = out["refs"] |> Enum.map(&refs.messages[&1]) |> Enum.reject(&is_nil/1)
          answer = Capabilities.strip_labels(out["answer"])

          {answer,
           %{
             "kind" => "answer",
             "request_id" => rid,
             "answer" => answer,
             "refs" => Enum.uniq(ids),
             "confidence" => conf,
             "call_ref" => call_ref
           }}
        end
      })
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
            out = strip_all(out, ~w(summary decisions action_items open_questions))

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
             "sections" =>
               Enum.map(
                 out["sections"],
                 &Map.update!(&1, "body", fn b -> Capabilities.strip_labels(b) end)
               ),
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

  # Ref labels out of every text field (strings and string lists).
  defp strip_all(out, keys) do
    Enum.reduce(keys, out, fn k, acc ->
      Map.update(acc, k, nil, fn
        s when is_binary(s) -> Capabilities.strip_labels(s)
        l when is_list(l) -> Enum.map(l, &Capabilities.strip_labels/1)
        other -> other
      end)
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

    case LLM.complete(req) |> post_check(req, t) do
      {:ok, %{call_ref: call_ref, output: out, confidence: conf}} ->
        {body, risi} = t.reply.(out, call_ref, conf)
        post(conv, body, Map.put(risi, "notify", [user]))

      # The global in-flight queue is full: wait (the job snoozes), answer late rather than
      # refuse; only after 10 minutes of waiting the polite error (§25.6 `queue_overflow`).
      {:error, :rate_limited} ->
        if waited_s() > @max_wait_s do
          Secretary.log_limit(user, conv, "refused", "waited_over_10_min")
          reply_error(conv, rid, user, "queue_overflow")
        else
          Secretary.log_limit(user, conv, "queued", "global_in_flight")
          {:snooze, 10}
        end

      # The model is down after its retries: try again later, answer late; only after 10
      # minutes the polite error.
      {:error, _} ->
        if waited_s() > @max_wait_s,
          do: reply_error(conv, rid, user, "model_unavailable"),
          else: {:snooze, 30}
    end
  end

  # §25.1 post-check (hotfix 2026-10-09): an answer saying the transcript/chat lacks something
  # is retried once with a correction; if it still does, a server-built answer says what Risi
  # can do now and what's coming.
  defp post_check({:ok, %{output: %{"answer" => a}}} = ok, req, %{post_check: true}) do
    if Capabilities.forbidden?(a) do
      retry =
        Map.put(req, :history, [
          %{"role" => "assistant", "content" => Jason.encode!(elem(ok, 1).output)},
          %{"role" => "user", "content" => Capabilities.retry_instruction()}
        ])

      case LLM.complete(retry) do
        {:ok, %{output: %{"answer" => a2}}} = ok2 ->
          if Capabilities.forbidden?(a2), do: fallback(ok2), else: ok2

        _ ->
          fallback(ok)
      end
    else
      ok
    end
  end

  defp post_check(result, _req, _t), do: result

  defp fallback({:ok, %{output: out} = r}),
    do:
      {:ok,
       %{
         r
         | output: %{out | "answer" => Capabilities.fallback_answer(), "refs" => []}
       }}

  @bodies %{
    "model_unavailable" => "I can't answer right now. Please try again later.",
    "rate_limited" => "Too many requests right now. Please try again in a few minutes.",
    "queue_overflow" =>
      "You have a lot of questions waiting, so I skipped this one. Please ask again in a moment.",
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
