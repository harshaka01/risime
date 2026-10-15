defmodule RisiMe.Agent.RootInbox do
  @moduledoc """
  Root reaches Harsha through his Risi chat (Harsha 2026-10-15 15:31 SLT). No wire change: a
  question is an ordinary Risi `answer` (§25.4 `next_actions` `ask` chips, sealed and pushed as
  any Risi answer); a tap sends "Root: <option>" as his own Risi request.

    * **Config:** `RISI_ROOT_NOTIFY_USER` (one user id, read at runtime; app env
      `:risi_root_notify_user` wins, for tests). Unset → `post/4` refuses and nothing is
      intercepted.
    * `post/4` (`scripts/risi-notify`): the body starts "Root needs you:" (`ask`), "Try now:"
      (`try`) or "P0:" (`p0`); up to 3 options become `ask` chips with the text
      "Root: <option>"; more are listed as lettered lines ("Reply with the letter."). One open
      question per topic: a new post supersedes the old one; the same text again is refused.
    * `intercept/4` (the very start of `RisiMe.Agent.Turn`): only for the configured user, only
      in their own active Risi chat, never in a call. A request starting with "Root:", or a
      short reply-like request (an option, its letter or number, "yes", "no", …) while a
      question posted < 30 min ago is open, is recorded as the newest open question's answer;
      Risi replies "Got it, I've passed that to root." and the brain does not run. Anything else
      goes to the brain.
    * `fetch_new/0` (`scripts/risi-inbox`): answers not yet delivered, marked delivered.

  Root acts on a reply only for decisions and confirmations; sudo, money, deletion, keys and any
  consequential action still need Harsha in the shell (`scripts/risi-inbox` prints that note).
  """
  import Ecto.Query

  require Logger

  alias RisiMe.Agent.{MadeBy, Out}
  alias RisiMe.Repo

  @table "risi_root_questions"
  @prefix %{
    "ask" => "Root needs you:",
    "try" => "Try now:",
    "p0" => "P0:",
    # Harsha 15 Oct: new releases and finished work.
    "release" => "New release:",
    "done" => "Done:"
  }
  @ack "Got it, I've passed that to root."
  @reply_window_s 30 * 60
  @max_chips 3
  @max_options 9
  @max_text 1_500
  @max_answer 500
  @reply_words ~w(yes no y n ok okay sure yep yeah nope go later done tested works agreed approve
                  approved fine skip wait stop cancel)

  @doc "The configured user id, or nil."
  def user do
    case Application.get_env(:risime, :risi_root_notify_user) ||
           System.get_env("RISI_ROOT_NOTIFY_USER") do
      v when is_binary(v) ->
        case Ecto.UUID.cast(String.trim(v)) do
          {:ok, id} -> id
          :error -> nil
        end

      _ ->
        nil
    end
  end

  @doc "The acknowledgement Risi posts for a recorded reply."
  def ack, do: @ack

  ## Post

  @doc """
  Posts a root question. `{:ok, id}` or `{:error, :not_configured | :no_risi_chat | :risi_off |
  :empty | :too_long | :too_many_options | :bad_kind | :duplicate | reason}`.
  """
  def post(topic, text, options \\ [], kind \\ "ask") do
    text = String.trim(text || "")
    options = (options || []) |> Enum.map(&String.trim/1) |> Enum.reject(&(&1 == ""))
    topic = slug(topic) || slug(text) || "root"

    with {:ok, user} <- configured(),
         :ok <- validate(text, options, kind),
         rc when is_binary(rc) <- RisiMe.RisiChat.active_id(user) || {:error, :no_risi_chat},
         true <- Out.ready?() || {:error, :risi_off},
         :ok <- not_duplicate(user, topic, text, options) do
      body = body(kind, text, options)
      now = DateTime.utc_now()

      risi = %{
        "kind" => "answer",
        "request_id" => Ecto.UUID.generate(),
        "answer" => body,
        "refs" => [],
        "confidence" => 1.0,
        "sources" => [],
        "next_steps" => [],
        "next_actions" => chips(options),
        "call_ref" => nil,
        "made_by" => MadeBy.rule(),
        "notify" => [user]
      }

      case Out.post(rc, body, risi, memory: false) do
        {:ok, sent} ->
          supersede(user, topic, now)
          id = Ecto.UUID.generate()

          Repo.insert_all(@table, [
            %{
              id: Ecto.UUID.dump!(id),
              user_id: Ecto.UUID.dump!(user),
              conversation_id: rc,
              message_id: sent[:message_id],
              topic: topic,
              kind: kind,
              text: text,
              options: options,
              posted_at: now,
              # A release or done notice asks nothing: never catches a reply.
              answered_at: if(kind in ~w(release done), do: now),
              delivered_at: if(kind in ~w(release done), do: now)
            }
          ])

          {:ok, id}

        {:error, _} = e ->
          e
      end
    else
      {:error, _} = e -> e
    end
  end

  defp configured do
    case user() do
      nil -> {:error, :not_configured}
      u -> {:ok, u}
    end
  end

  defp validate(text, options, kind) do
    cond do
      not Map.has_key?(@prefix, kind) -> {:error, :bad_kind}
      text == "" -> {:error, :empty}
      String.length(text) > @max_text -> {:error, :too_long}
      length(options) > @max_options -> {:error, :too_many_options}
      true -> :ok
    end
  end

  defp not_duplicate(user, topic, text, options) do
    case open_on(user, topic) do
      %{text: ^text, options: ^options} -> {:error, :duplicate}
      _ -> :ok
    end
  end

  @doc "The message body for a question."
  def body(kind, text, options) do
    head = @prefix[kind] <> " " <> text

    if length(options) > @max_chips do
      lines =
        options
        |> Enum.with_index()
        |> Enum.map_join("\n", fn {o, i} -> "#{letter(i)}. #{o}" end)

      head <> "\n\n" <> lines <> "\n\nReply with the letter."
    else
      head
    end
  end

  @doc "The `ask` chips for ≤ 3 options (none for more: they are listed as lines)."
  def chips(options) when length(options) > @max_chips, do: []

  def chips(options) do
    for o <- options,
        do: %{"label" => String.slice(o, 0, 40), "action" => "ask", "text" => "Root: " <> o}
  end

  defp letter(i), do: <<?A + i>>

  defp supersede(user, topic, now) do
    from(q in @table,
      where:
        q.user_id == type(^user, :binary_id) and q.topic == ^topic and is_nil(q.answered_at) and
          is_nil(q.superseded_at)
    )
    |> Repo.update_all(set: [superseded_at: now])
  end

  defp open_on(user, topic) do
    Repo.one(
      from q in open_q(user),
        where: q.topic == ^topic,
        order_by: [desc: q.posted_at],
        limit: 1,
        select: %{text: q.text, options: q.options}
    )
  end

  defp open_q(user) do
    from q in @table,
      where:
        q.user_id == type(^user, :binary_id) and is_nil(q.answered_at) and
          is_nil(q.superseded_at)
  end

  defp newest_open(user) do
    Repo.one(
      from q in open_q(user),
        order_by: [desc: q.posted_at],
        limit: 1,
        select: %{
          id: type(q.id, :binary_id),
          topic: q.topic,
          text: q.text,
          options: q.options,
          posted_at: type(q.posted_at, :utc_datetime_usec)
        }
    )
  end

  ## Intercept

  @doc """
  At the start of a Risi turn: `:pass` (the brain runs) or the Oban result of a recorded reply.
  """
  def intercept(conv, env, request_id, asker) do
    with u when is_binary(u) <- user(),
         true <- u == asker,
         nil <- Process.get(:risi_voice_sink),
         nil <- env["__schedule"],
         ^conv <- RisiMe.RisiChat.active_id(asker),
         text when is_binary(text) <- env["text"],
         {:reply, q, answer, kind} <- classify(asker, String.trim(text)) do
      record(asker, q, answer, kind)
      reply(conv, request_id, asker)
    else
      _ -> :pass
    end
  rescue
    e ->
      Logger.warning("Root inbox intercept failed: #{inspect(e.__struct__)}")
      :pass
  end

  # {:reply, question | nil, answer, "tap" | "text"} or :pass.
  defp classify(user, text) do
    case Regex.run(~r/^root\s*:\s*(.*)$/isu, text) do
      [_, rest] ->
        q = newest_open(user)
        rest = String.trim(rest)
        {answer, kind} = resolve(q, rest)
        {:reply, q, answer, if(kind == :option and rest == answer, do: "tap", else: "text")}

      nil ->
        with %{} = q <- newest_open(user),
             true <- recent?(q),
             true <- reply_like?(q, text) do
          {answer, _} = resolve(q, text)
          {:reply, q, answer, "text"}
        else
          _ -> :pass
        end
    end
  end

  defp recent?(%{posted_at: at}),
    do: DateTime.diff(DateTime.utc_now(), at, :second) < @reply_window_s

  # An option's text (exact or by letter/number) → {option, :option}; else {text, :free}.
  defp resolve(nil, text), do: {text, :free}

  defp resolve(%{options: options}, text) do
    n = norm(text)
    by_text = Enum.find(options, &(norm(&1) == n))

    by_index =
      case Regex.run(~r/^(?:option\s*)?([a-i]|[1-9])$/i, n) do
        [_, k] -> Enum.at(options, index(k))
        _ -> nil
      end

    case by_text || by_index do
      nil -> {text, :free}
      o -> {o, :option}
    end
  end

  defp index(<<d>>) when d in ?1..?9, do: d - ?1
  defp index(<<c>>) when c in ?a..?i, do: c - ?a

  # Short and clearly an answer: an option, its letter/number, or a yes/no-like word.
  defp reply_like?(q, text) do
    n = norm(text)
    words = String.split(n, " ", trim: true)

    cond do
      n == "" or String.length(text) > 60 or String.contains?(text, "?") -> false
      elem(resolve(q, text), 1) == :option -> true
      length(words) <= 4 and hd(words) in @reply_words -> true
      true -> false
    end
  end

  defp norm(s),
    do:
      s
      |> String.downcase()
      |> String.replace(~r/[^\p{L}\p{N}\s]/u, "")
      |> String.split()
      |> Enum.join(" ")

  defp record(user, q, answer, kind) do
    now = DateTime.utc_now()
    answer = String.slice(answer, 0, @max_answer)

    case q do
      %{id: id} ->
        from(r in @table, where: r.id == type(^id, :binary_id))
        |> Repo.update_all(set: [answered_at: now, answer: answer, answer_kind: kind])

      nil ->
        # "Root: …" with nothing open: a note for root.
        Repo.insert_all(@table, [
          %{
            id: Ecto.UUID.dump!(Ecto.UUID.generate()),
            user_id: Ecto.UUID.dump!(user),
            conversation_id: RisiMe.RisiChat.active_id(user) || "",
            topic: "note",
            kind: "ask",
            text: "(no open question)",
            options: [],
            posted_at: now,
            answered_at: now,
            answer: answer,
            answer_kind: kind
          }
        ])
    end
  end

  defp reply(conv, request_id, user) do
    risi = %{
      "kind" => "answer",
      "request_id" => request_id,
      "answer" => @ack,
      "refs" => [],
      "confidence" => 1.0,
      "sources" => [],
      "next_steps" => [],
      "next_actions" => [],
      "call_ref" => nil,
      "made_by" => MadeBy.rule(),
      "notify" => [user]
    }

    case Out.post(conv, @ack, risi) do
      {:ok, _} -> :ok
      {:error, :rate_limited} -> {:snooze, 5}
      {:error, _} = e -> e
    end
  end

  ## Fetch

  @doc """
  The answers not yet delivered (oldest first) as maps with `answered_at`, `topic`, `question`,
  `answer`, `answer_kind`; marks them delivered.
  """
  def fetch_new do
    rows =
      Repo.all(
        from q in @table,
          where: not is_nil(q.answered_at) and is_nil(q.delivered_at),
          order_by: [asc: q.answered_at],
          select: %{
            id: q.id,
            answered_at: type(q.answered_at, :utc_datetime_usec),
            topic: q.topic,
            question: q.text,
            answer: q.answer,
            answer_kind: q.answer_kind
          }
      )

    ids = Enum.map(rows, & &1.id)

    if ids != [] do
      from(q in @table, where: q.id in ^ids)
      |> Repo.update_all(set: [delivered_at: DateTime.utc_now()])
    end

    Enum.map(rows, &Map.delete(&1, :id))
  end

  @doc "One `docs/status/inbox.md` line for a fetched answer."
  def line(a) do
    t = a.answered_at |> DateTime.truncate(:second) |> DateTime.to_iso8601()

    "- #{t} · #{a.topic} · Q: #{one_line(a.question)} · A: #{one_line(a.answer)} (#{a.answer_kind})"
  end

  defp one_line(s), do: s |> to_string() |> String.replace(~r/\s+/u, " ") |> String.trim()

  ## CLI (scripts/risi-notify, scripts/risi-inbox via scripts/risi-rpc)

  @doc false
  def cli_post(b64) do
    %{"text" => text} = args = b64 |> Base.decode64!() |> Jason.decode!()

    cmd =
      if is_binary(args["sudo"]) and String.trim(args["sudo"]) != "",
        do: String.trim(args["sudo"])

    text = sections(args["kind"], text, args)

    # Harsha 15 Oct: the command comes next as its own message (long-press → Copy copies exactly
    # it), so the question only points at it.
    text =
      if cmd,
        do:
          text <>
            "\n\nThe command is in the next message: long-press it and tap Copy. Run on spark2 (gx10-e23a).",
        else: text

    case post(args["topic"], text, args["options"] || [], args["kind"] || "ask") do
      {:ok, id} ->
        if cmd, do: post_command(cmd)
        IO.puts("posted #{id}")

      {:error, why} ->
        IO.puts("refused: #{why}")
    end
  end

  @doc """
  Harsha 15 Oct: a release or done notice has three sections, in this order: the details, what
  Harsha has to do, and where it is available. Other kinds: `text` unchanged.
  """
  def sections(kind, text, args) when kind in ~w(release done) do
    {d, a} =
      if kind == "release",
        do: {"Release details:", "New release available:"},
        else: {"Work details:", "Available now:"}

    bullets = fn
      [] -> nil
      xs -> Enum.map_join(xs, "\n", &("• " <> &1))
    end

    [
      text,
      d <> "\n" <> (bullets.(args["details"] || []) || "• (none)"),
      "Harsha to action:\n" <> (bullets.(args["actions"] || []) || "Nothing for you."),
      a <> "\n" <> (args["available"] || "Now, on the pilot server (no app update needed).")
    ]
    |> Enum.join("\n\n")
  end

  def sections(_kind, text, _args), do: text

  # The bare command, one Risi message (no chips, no second push).
  defp post_command(cmd) do
    with {:ok, user} <- configured(),
         rc when is_binary(rc) <- RisiMe.RisiChat.active_id(user) do
      risi = %{
        "kind" => "answer",
        "request_id" => Ecto.UUID.generate(),
        "answer" => cmd,
        "refs" => [],
        "confidence" => 1.0,
        "sources" => [],
        "next_steps" => [],
        "next_actions" => [],
        "call_ref" => nil,
        "made_by" => MadeBy.rule(),
        "notify" => []
      }

      Out.post(rc, cmd, risi, memory: false)
    end
  end

  @doc false
  def cli_fetch do
    Enum.each(fetch_new(), &IO.puts(line(&1)))
  end

  defp slug(nil), do: nil

  defp slug(s) do
    s =
      s
      |> String.downcase()
      |> String.replace(~r/[^a-z0-9\s-]/u, "")
      |> String.split(~r/[\s-]+/, trim: true)
      |> Enum.take(5)
      |> Enum.join("-")
      |> String.slice(0, 60)

    if s == "", do: nil, else: s
  end
end
