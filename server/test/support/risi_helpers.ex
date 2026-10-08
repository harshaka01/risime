defmodule RisiMe.RisiHelpers do
  @moduledoc """
  v1.24 S6/S7 test helpers: a fake `risi-l1` (a `Req.Test` stub that answers with scripted,
  schema-valid JSON and records every request body), an Official chat with Risi in it, and
  buffered member messages.
  """
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
end
