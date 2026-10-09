defmodule RisiMe.Agent.TestSender do
  @moduledoc """
  Test stand-in for `RisiMe.Agent.Send` (`config :risime, :risi_sender`): applies the same
  §24.5 checks, then records the post and sends `{:risi_post, conv, body, risi}` to the pid in
  `:risi_test_pid` (if any). Each post gets a fresh TimeUUID message id.
  """
  def text(conv, body, risi) do
    cond do
      not RisiMe.Agent.official?(conv) ->
        {:error, :private_tab}

      not RisiMe.Agent.may_act?(conv) ->
        {:error, :not_member}

      true ->
        if pid = Application.get_env(:risime, :risi_test_pid),
          do: send(pid, {:risi_post, conv, body, risi})

        {:ok,
         %{
           message_id: RisiMe.TimeUUID.generate(),
           conversation_id: conv,
           server_ts: DateTime.utc_now()
         }}
    end
  end
end

defmodule RisiMe.RisiHelpers do
  @moduledoc """
  v1.24 S6/S7 test helpers: a fake `risi-l1` (a `Req.Test` stub that answers with scripted,
  schema-valid JSON and records every request body), an Official chat with Risi in it, and
  buffered member messages.
  """
  import ExUnit.Callbacks, only: [on_exit: 1]

  alias RisiMe.Agent.Transcript
  alias RisiMe.TimeUUID

  @doc """
  Installs the fake model for the calling test process (`Oban.Testing.perform_job/2` runs jobs
  in it). `reply` is a function `(task_schema_name, body) -> map | {:status, n} | :timeout`.
  Every request body is stored; read them with `llm_requests/0`.
  """
  def fake_llm!(reply) do
    {:ok, rec} = Agent.start_link(fn -> [] end)
    Process.put(:risi_llm_rec, rec)

    Req.Test.stub(RisiMe.Agent.LLM.Local, fn conn ->
      case conn.request_path do
        "/v1/models" ->
          Req.Test.json(conn, %{
            "data" => [%{"id" => "risi-l1", "root" => "/models/Qwen3.6-35B-A3B-FP8"}]
          })

        "/v1/chat/completions" ->
          {:ok, raw, conn} = Plug.Conn.read_body(conn)
          body = Jason.decode!(raw)
          Agent.update(rec, &[body | &1])
          name = get_in(body, ["response_format", "json_schema", "name"])

          case reply.(name, body) do
            {:status, n} ->
              Plug.Conn.send_resp(conn, n, "{}")

            :timeout ->
              Req.Test.transport_error(conn, :timeout)

            {:raw, content} ->
              Req.Test.json(conn, completion(content))

            out when is_map(out) ->
              Req.Test.json(conn, completion(Jason.encode!(out)))
          end
      end
    end)

    rec
  end

  defp completion(content),
    do: %{
      "model" => "risi-l1",
      "choices" => [%{"message" => %{"role" => "assistant", "content" => content}}],
      "usage" => %{"prompt_tokens" => 120, "completion_tokens" => 30}
    }

  @doc "The ref (`m3`, `u2`) of the last prompt line holding `needle` (a message text or a name)."
  def ref_of(body, needle) do
    body["messages"]
    |> List.last()
    |> Map.fetch!("content")
    |> String.split("\n")
    |> Enum.reverse()
    |> Enum.find_value(fn line ->
      case Jason.decode(line) do
        {:ok, %{"ref" => ref} = m} -> if m["text"] == needle or m["name"] == needle, do: ref
        _ -> nil
      end
    end)
  end

  @doc "Every request body the fake model received (oldest first)."
  def llm_requests do
    case Process.get(:risi_llm_rec) do
      nil -> []
      rec -> rec |> Agent.get(& &1) |> Enum.reverse()
    end
  end

  @doc """
  RISI on (seeded, faked device), a data key, posts to the test process; returns the Official
  group `og` (Risi an active agent member) with the given human `users` (first = admin).
  """
  def risi_chat!(users, opts \\ []) do
    keys = [:risi, :risi_data_key, :risi_test_pid, :risi_extract_delay_s]
    old = for k <- keys, do: {k, Application.fetch_env(:risime, k)}

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end
    end)

    %{user_id: risi} = RisiMe.TabsHelpers.risi_on!(0)
    Application.put_env(:risime, :risi_data_key, Base.encode64(:crypto.strong_rand_bytes(32)))
    Application.put_env(:risime, :risi_test_pid, self())

    members =
      users
      |> Enum.with_index()
      |> Enum.map(fn {u, i} -> {u.id, if(i == 0, do: "admin", else: "member")} end)

    chat_id = Keyword.get(opts, :chat_id, "grp:" <> Ecto.UUID.generate())
    RisiMe.TabsHelpers.official_group!(chat_id, members, agents: [risi])
  end

  @doc "Buffers a `text` message from `user` (as `Agent.Conversation` would) and returns its id."
  def say!(conv, user, text, at \\ nil) do
    id = if at, do: TimeUUID.at(at), else: TimeUUID.generate()
    pt = Jason.encode!(%{"v" => 1, "type" => "text", "body" => text})
    m = %{message_id: id, sender_id: user.id, sender_device: nil, plaintext: pt}
    :ok = Transcript.put(conv, m)
    RisiMe.Agent.Secretary.on_message(conv, m)
    id
  end

  @doc """
  The fake model answering like `scripts/fake-llm --script` (contract v1.25 §25.1, §25.9): the
  first rule whose `task` fully matches the schema name, `match` is found in the FIRST user
  message and `match_last` in the LAST one wins; the step index is the number of assistant
  messages in the request (past the end the last action repeats). `rules` is a list of rule maps
  or a path to a script file (default: `contract/v1/risi_gate_script.json`). A request no rule
  matches gets `fallback.(name, body)`.
  """
  def scripted_llm!(rules \\ nil, fallback \\ fn _, _ -> {:status, 500} end) do
    rules =
      case rules do
        nil ->
          gate_script()

        path when is_binary(path) ->
          path |> File.read!() |> Jason.decode!() |> Map.fetch!("rules")

        list when is_list(list) ->
          list
      end

    fake_llm!(fn name, body ->
      msgs = body["messages"]
      users = for %{"role" => "user", "content" => c} <- msgs, do: c
      first = List.first(users) || ""
      last = List.last(users) || ""
      step = Enum.count(msgs, &(&1["role"] == "assistant"))

      rule =
        Enum.find(rules, fn r ->
          (r["task"] == nil or Regex.match?(~r/\A(?:#{r["task"]})\z/, name || "")) and
            (r["match"] == nil or Regex.match?(Regex.compile!(r["match"]), first)) and
            (r["match_last"] == nil or Regex.match?(Regex.compile!(r["match_last"]), last))
        end)

      case rule do
        nil ->
          fallback.(name, body)

        %{"actions" => actions} ->
          case Enum.at(actions, min(step, length(actions) - 1)) do
            s when is_binary(s) -> {:raw, s}
            a -> a
          end
      end
    end)
  end

  @doc "The rules of `contract/v1/risi_gate_script.json`."
  def gate_script,
    do:
      Path.expand("../../../contract/v1/risi_gate_script.json", __DIR__)
      |> File.read!()
      |> Jason.decode!()
      |> Map.fetch!("rules")

  @doc "Buffers any envelope from `user` and hands it to the secretary; returns its id."
  def envelope!(conv, user, env, device \\ nil) do
    id = TimeUUID.generate()

    m = %{
      message_id: id,
      sender_id: user.id,
      sender_device: device,
      plaintext: Jason.encode!(env)
    }

    :ok = Transcript.put(conv, m)
    RisiMe.Agent.Secretary.on_message(conv, m)
    id
  end
end
