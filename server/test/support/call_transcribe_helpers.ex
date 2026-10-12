defmodule RisiMe.FakeSpeech do
  @moduledoc """
  Test stand-in for the speech service (`config :risime, :risi_speech`), contract v1.39 §36.5.
  The "audio" is a script: `"<lang>:<text>"` is speech in that language (`"si:…"`, `"ta:…"`,
  `"en:…"`), `"silence"` has no speech, `"fail"` is a service error, and while
  `:fake_speech_gate` is `:closed` every call waits (queue tests). Each call sends
  `{:fake_speech, kind, audio}` to `:risi_test_pid`.

  v1.40 §36.11: `"sx:<text>"` is Sinhala that the service checked for script: `transcribe`
  answers it with `romanised: true` (still not Sinhala script after the retry), `compare` adds
  `script` / `romanised` to every result (`faster-whisper-large-v3` romanised, the rest fine).

  v1.41 §37.6: `transcribe` results carry `language_probability` (0.97) and, for Sinhala, the
  `script` check; `"lowp:<text>"` is speech whose language was detected with probability 0.4.
  """
  @behaviour RisiMe.Agent.Speech

  alias RisiMe.Agent.Speech

  @impl true
  def healthy?, do: Application.get_env(:risime, :fake_speech_healthy, true)

  @impl true
  def transcribe(audio, _opts \\ []) do
    wait_gate()
    notify(:transcribe, audio)

    case parse(audio) do
      :fail ->
        {:error, :unavailable}

      :silence ->
        {:ok,
         %{text: "", language: "en", model: Speech.route("en"), elapsed_ms: 5, speech: false}}

      {:script, text} ->
        {:ok,
         %{
           text: text,
           language: "si",
           model: "whisper-large-v3-sinhala",
           elapsed_ms: 40,
           speech: true,
           romanised: true,
           language_probability: 0.97,
           script: %{
             expected: "sinh",
             sinhala_chars: 0,
             latin_chars: String.length(text),
             ok: false
           }
         }}

      {:lowp, text} ->
        {:ok,
         %{
           text: text,
           language: "en",
           model: Speech.route("en"),
           elapsed_ms: 40,
           speech: true,
           language_probability: 0.4
         }}

      {lang, text} ->
        r = %{
          text: text,
          language: lang,
          model: Speech.route(lang),
          elapsed_ms: 40,
          speech: true,
          language_probability: 0.97
        }

        {:ok, if(lang == "si", do: Map.put(r, :script, si_script(text)), else: r)}
    end
  end

  defp si_script(text) do
    cs = String.to_charlist(text)
    si = Enum.count(cs, &(&1 in 0x0D80..0x0DFF))
    la = Enum.count(cs, &(&1 in ?a..?z or &1 in ?A..?Z))
    %{expected: "sinh", sinhala_chars: si, latin_chars: la, ok: si > 0}
  end

  @impl true
  def compare(audio, _opts \\ []) do
    wait_gate()
    notify(:compare, audio)

    case parse(audio) do
      :fail ->
        {:error, :unavailable}

      :silence ->
        {:ok,
         %{
           language_detected: "en",
           chosen: Speech.route("en"),
           results: for(m <- Speech.models(), do: result(m, "", "en"))
         }}

      {:script, text} ->
        {:ok,
         %{
           language_detected: "si",
           chosen: Speech.route("si"),
           results:
             for m <- Speech.models() do
               if m == "faster-whisper-large-v3",
                 do:
                   Map.merge(result(m, "Heta ude dahayata hamuwemu.", "si"), %{
                     script: %{expected: "sinh", sinhala_chars: 0, latin_chars: 23, ok: false},
                     romanised: true
                   }),
                 else:
                   Map.merge(result(m, text, "si"), %{
                     script: %{expected: "sinh", sinhala_chars: 17, latin_chars: 0, ok: true},
                     romanised: false
                   })
             end
         }}

      {lang, text} ->
        {:ok,
         %{
           language_detected: lang,
           chosen: Speech.route(lang),
           results:
             for m <- Speech.models() do
               if m == "tamil-asr-qwen3" and lang != "ta",
                 do: %{model: m, text: nil, language: nil, elapsed_ms: 30_000, error: "timeout"},
                 else: result(m, text, lang)
             end
         }}
    end
  end

  defp result(m, text, lang),
    do: %{model: m, text: text, language: lang, elapsed_ms: 640, error: nil}

  defp parse("fail"), do: :fail
  defp parse("silence"), do: :silence

  defp parse(audio) do
    case String.split(audio, ":", parts: 2) do
      ["sx", text] -> {:script, text}
      ["lowp", text] -> {:lowp, text}
      [lang, text] when lang in ["si", "ta", "en"] -> {lang, text}
      _ -> :silence
    end
  end

  defp notify(kind, audio) do
    if pid = Application.get_env(:risime, :risi_test_pid),
      do: send(pid, {:fake_speech, kind, audio})
  end

  defp wait_gate(n \\ 500) do
    if n > 0 and Application.get_env(:risime, :fake_speech_gate) == :closed do
      Process.sleep(10)
      wait_gate(n - 1)
    end
  end
end

defmodule RisiMe.CallTranscribeHelpers do
  @moduledoc """
  v1.39 §36 test helpers: a 1:1 Official chat of two users with `call_transcribe` devices (the
  peer's online), a rung call, the switch on with the fake speech service, short timers, and a
  raw multipart chunk upload (through the real parsers, so nothing is faked on the way).
  """
  import ExUnit.Callbacks, only: [on_exit: 1]
  import Plug.Conn, only: [put_req_header: 3]
  import Phoenix.ConnTest, only: [build_conn: 0, post: 3]

  alias RisiMe.Agent.CallTranscribe

  @endpoint RisiMeWeb.Endpoint

  @caps ~w(groups member_devices calls tabs risi_tools risi_ledger call_transcribe)

  @keys [
    :risi_call_transcribe,
    :risi_speech,
    :risi_call_transcribe_timing,
    :risi_dev_users,
    :fake_speech_gate,
    :fake_speech_healthy,
    :risi_call_transcribe_max_live,
    :risi_ledger,
    :risi_now
  ]

  @doc "Restores the §36 settings after the test."
  def restore_transcribe_env! do
    old = for k <- @keys, do: {k, Application.fetch_env(:risime, k)}

    on_exit(fn ->
      for {k, prev} <- old do
        case prev do
          {:ok, v} -> Application.put_env(:risime, k, v)
          :error -> Application.delete_env(:risime, k)
        end
      end

      # Nothing of a test's calls survives it.
      for {_, pid, _, _} <- DynamicSupervisor.which_children(CallTranscribe.CallSup),
          do: DynamicSupervisor.terminate_child(CallTranscribe.CallSup, pid)

      for pid <- Task.Supervisor.children(CallTranscribe.TaskSup),
          do: Task.Supervisor.terminate_child(CallTranscribe.TaskSup, pid)
    end)
  end

  @doc """
  The world: users A and B (`call_transcribe` devices `da`, `db`; B online), their 1:1
  Official chat `og` (chat id `dm`) with Risi, and a rung call `call_id` from A to B.
  """
  def transcribe_world!(opts \\ []) do
    restore_transcribe_env!()
    Application.put_env(:risime, :risi_call_transcribe, true)
    Application.put_env(:risime, :risi_speech, RisiMe.FakeSpeech)
    Application.put_env(:risime, :risi_call_transcribe_timing, Keyword.get(opts, :timing, []))

    RisiMe.MLSHelpers.with_attestation_key(%{})
    a = RisiMe.Fixtures.logged_in_user(display_name: "Test User A", company: "Risi")
    b = RisiMe.Fixtures.logged_in_user(display_name: "Test User B", company: "Risi")
    da = RisiMe.TabsHelpers.tabs_device!(a.user, caps: @caps)
    db = RisiMe.TabsHelpers.tabs_device!(b.user, caps: @caps)
    dm = RisiMe.Messaging.conversation_id(a.user.id, b.user.id)
    og = RisiMe.RisiHelpers.risi_chat!([a.user, b.user], chat_id: dm)
    online!(b.user.id, db)
    online!(a.user.id, da)
    call_id = ring!(a.user.id, b.user.id, dm)

    %{a: a, b: b, da: da, db: db, dm: dm, og: og, call_id: call_id}
  end

  @doc "Records a ring from `from` to `to` (as `RisiMe.Calls` does); returns the call id."
  def ring!(from, to, dm) do
    call_id = Ecto.UUID.generate()
    :ok = CallTranscribe.note_ring(call_id, from, to, dm, "audio")
    call_id
  end

  @doc "Keeps `device_id` of `user_id` online (a live inbox) until the test ends."
  def online!(user_id, device_id) do
    parent = self()

    pid =
      spawn(fn ->
        :ok = RisiMe.Presence.track(user_id, device_id, nil)
        send(parent, :tracked)

        receive do
          :stop -> :ok
        end
      end)

    receive do
      :tracked -> :ok
    after
      2_000 -> raise "presence not tracked"
    end

    on_exit(fn -> send(pid, :stop) end)
    pid
  end

  @doc "A `call:transcribe` push of `who` (`:a` | `:b`) from its device (or `device`)."
  def tpush!(w, who, action, extra \\ %{}, device \\ nil) do
    user = w[who].user.id
    device = device || w[if(who == :a, do: :da, else: :db)]

    payload =
      Map.merge(
        %{"action" => action, "call_id" => w.call_id, "conversation_id" => w.og},
        extra
      )

    CallTranscribe.push(user, device, payload)
  end

  @doc "A request by A and an allow by B: returns the session id (state `on`)."
  def start_on!(w) do
    {:ok, %{session: %{"session_id" => sid, "state" => "requested"}}} = tpush!(w, :a, "request")
    {:ok, %{session: %{"state" => "on"}}} = tpush!(w, :b, "allow", %{"session_id" => sid})
    sid
  end

  @doc """
  Uploads one chunk through the endpoint as a raw `multipart/form-data` body. `fields` override
  the defaults (`seq`, `offset_ms`, `duration_ms`, `codec`, `session_id`); `audio` is the part's
  bytes. Returns `{status, json}`.
  """
  def upload!(w, who, sid, audio, fields \\ %{}, opts \\ []) do
    token = w[who].token
    device = Keyword.get(opts, :device, w[if(who == :a, do: :da, else: :db)])
    call_id = Keyword.get(opts, :call_id, w.call_id)

    fields =
      Map.merge(
        %{
          "session_id" => sid,
          "seq" => "0",
          "offset_ms" => "0",
          "duration_ms" => "12000",
          "codec" => "ogg_opus"
        },
        Map.new(fields, fn {k, v} -> {k, to_string(v)} end)
      )

    boundary = "----risime" <> Base.encode16(:crypto.strong_rand_bytes(8))
    body = multipart(boundary, fields, audio)

    conn =
      build_conn()
      |> put_req_header("authorization", "Bearer " <> token)
      |> put_req_header("x-device-id", device)
      |> put_req_header("accept", "application/json")
      |> put_req_header("content-type", "multipart/form-data; boundary=" <> boundary)
      |> post("/api/v1/calls/#{call_id}/transcribe/chunks", body)

    {conn.status, Jason.decode!(conn.resp_body)}
  end

  @doc "A raw multipart body."
  def multipart(boundary, fields, audio) do
    parts =
      Enum.map(fields, fn {k, v} ->
        "--#{boundary}\r\ncontent-disposition: form-data; name=\"#{k}\"\r\n\r\n#{v}\r\n"
      end)

    audio_part =
      if audio,
        do: [
          "--#{boundary}\r\ncontent-disposition: form-data; name=\"audio\"; filename=\"chunk.ogg\"\r\n",
          "content-type: audio/ogg\r\n\r\n",
          audio,
          "\r\n"
        ],
        else: []

    IO.iodata_to_binary([parts, audio_part, "--#{boundary}--\r\n"])
  end

  @doc "Forgets every user's §36.3 10-s request gap (tests of several sessions)."
  def reset_request_gap! do
    :ets.match_delete(RisiMe.RateLimiter, {{:call_transcribe_request_gap, :_, :_}, :_, :_})
    :ok
  end

  @doc "Waits until `fun` returns a truthy value (at most `ms`)."
  def eventually(fun, ms \\ 2_000) do
    case fun.() do
      v when v in [nil, false] ->
        if ms <= 0 do
          raise "condition not met in time"
        else
          Process.sleep(20)
          eventually(fun, ms - 20)
        end

      v ->
        v
    end
  end

  @doc "The session row."
  def session!(sid), do: RisiMe.Repo.get!(RisiMe.Agent.CallTranscribe.Session, sid)

  @doc "Waits for the Risi post of `kind` (and `pred`) in `conv`; returns `{body, risi}`."
  def await_post(conv, kind, pred \\ fn _ -> true end, ms \\ 2_000) do
    receive do
      {:risi_post, ^conv, body, %{"kind" => ^kind} = risi} ->
        if pred.(risi), do: {body, risi}, else: await_post(conv, kind, pred, ms)
    after
      ms -> raise "no #{kind} post"
    end
  end

  @doc "Asserts no Risi post of `kind` arrives within `ms`."
  def refute_post(conv, kind, ms \\ 200) do
    receive do
      {:risi_post, ^conv, _body, %{"kind" => ^kind}} -> raise "unexpected #{kind} post"
    after
      ms -> :ok
    end
  end
end
