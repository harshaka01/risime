defmodule RisiMe.Agent.TTS.VoicePick do
  @moduledoc """
  Harsha's pick of Risi's call voice by ear (Harsha 2026-10-15 21:08 SLT): the same three Risi lines
  spoken by each candidate engine (1 Kokoro, 2 Chatterbox-Turbo, 3 Dia2, 4 Orpheus), posted in his
  Risi chat, then a choice question.

  **No wire change.** Risi cannot post audio into the (MLS) Risi chat: a `voice` envelope needs an
  `A256GCM-S64K` blob sealed inside a crypto core (§14.3), and Risi's server NIF has no seal API
  (§37.8: Risi's answers are text). So each engine's message is an ordinary rule-made Risi `answer`
  whose lines (and `sources` of type `link`, §25.4) are **short-lived signed https links** to
  `GET /admin/voice-samples/:token` (`RisiMeWeb.Admin.VoiceSampleController`):

    * the token (`Phoenix.Token`, salt `"voice-sample"`) names one sample file and the one admin
      it was made for; it expires after #{24} h;
    * the route sits behind the admin console's sign-in (`RequireAdmin`), and the signed-in admin
      must be the token's admin (Harsha): anyone else gets 403 even with the link;
    * the audio is served `Cache-Control: no-store`; only the audit line `voice_sample_played`
      (admin, file name) is written; no text or audio is logged.

  The samples are made by `infra/voice/tts_lab_bench.py --samples` into `samples_dir/0` (outside
  Git, `RISI_VOICE_SAMPLES_DIR`, default `~/risime-voice-samples`): `<engine>-<line>.wav` and
  `stats.json` (first audio p50/p95 and RTF per engine). `post/0` (`scripts/voice-pick-post`)
  posts what is there; an engine without its three files gets a one-line "waiting" message.
  """
  alias RisiMe.Agent.{MadeBy, Out, RootInbox}

  @salt "voice-sample"
  @max_age_s 24 * 3600

  @engines [
    {"1", "Kokoro", "kokoro"},
    {"2", "Chatterbox-Turbo", "chatterbox-turbo"},
    {"3", "Dia2", "dia2"},
    {"4", "Orpheus", "orpheus"}
  ]

  @lines [
    {"greeting", "Greeting", "Hi, it's Risi. How can I help?"},
    {"calendar", "Calendar answer",
     "Tomorrow you have a reminder to call Shakya at 10, and you're busy from 10:30 to 11:30 and from 3 to 4."},
    {"check", "Let me check", "Sure, give me a second, let me pull up your calendar."}
  ]

  @doc "The engines in posting order: `[{number, label, engine_id}]`."
  def engines, do: @engines

  @doc "The three lines every engine speaks: `[{key, title, text}]`."
  def lines, do: @lines

  @doc "Where the sample files are."
  def samples_dir do
    Application.get_env(:risime, :risi_voice_samples_dir) ||
      System.get_env("RISI_VOICE_SAMPLES_DIR") ||
      Path.expand("~/risime-voice-samples")
  end

  @doc "Every file name the route may serve."
  def files, do: for({_, _, e} <- @engines, {k, _, _} <- @lines, do: "#{e}-#{k}.wav")

  @doc "A token for `file`, valid only for admin `user`."
  def sign(file, user) when is_binary(file) and is_binary(user),
    do: Phoenix.Token.sign(RisiMeWeb.Endpoint, @salt, %{"f" => file, "u" => user})

  @doc "`{:ok, file}` when `token` is valid, unexpired, names a known file and was made for `admin`."
  def verify(token, admin) when is_binary(token) and is_binary(admin) do
    case Phoenix.Token.verify(RisiMeWeb.Endpoint, @salt, token, max_age: @max_age_s) do
      {:ok, %{"f" => f, "u" => ^admin}} -> if f in files(), do: {:ok, f}, else: {:error, :unknown}
      {:ok, _} -> {:error, :not_yours}
      {:error, e} -> {:error, e}
    end
  end

  def verify(_, _), do: {:error, :invalid}

  @doc "The https link for `file` (for admin `user`)."
  def link(file, user),
    do: RisiMeWeb.Endpoint.url() <> "/admin/voice-samples/" <> sign(file, user)

  @doc "The sample's bytes, or `{:error, :enoent}`."
  def read(file),
    do:
      if(file in files(), do: File.read(Path.join(samples_dir(), file)), else: {:error, :enoent})

  defp ready?(engine),
    do:
      Enum.all?(@lines, fn {k, _, _} ->
        File.regular?(Path.join(samples_dir(), "#{engine}-#{k}.wav"))
      end)

  defp stats do
    with {:ok, b} <- File.read(Path.join(samples_dir(), "stats.json")),
         {:ok, %{} = m} <- Jason.decode(b),
         do: m,
         else: (_ -> %{})
  end

  @doc """
  The message for one engine: `{body, sources}` (links for `user`), or a one-line "waiting" body
  with no sources when its samples are not there.
  """
  def message({n, label, engine}, user, stats \\ %{}) do
    if ready?(engine) do
      s = Map.get(stats, engine, %{})

      head =
        "#{n} #{label}" <>
          case s do
            %{"p50_ms" => p50, "p95_ms" => p95, "rtf" => rtf} ->
              " (first audio #{p50} ms typical, #{p95} ms slowest; #{rtf}x real time)"

            _ ->
              ""
          end

      links =
        for {k, title, _} <- @lines do
          {title, link("#{engine}-#{k}.wav", user)}
        end

      body =
        head <>
          "\n" <>
          Enum.map_join(links, "\n", fn {t, u} -> "#{t}: #{u}" end) <>
          "\nThe links open after your admin sign-in and stop working in 24 hours."

      sources =
        for {t, u} <- links, do: %{"type" => "link", "url" => u, "title" => "#{n} #{label}: #{t}"}

      {body, sources}
    else
      {"#{n} #{label}: waiting for access", []}
    end
  end

  @doc """
  Posts the four engine messages and the choice question in Harsha's (`RootInbox.user/0`) Risi
  chat. `{:ok, [{label, message_id | question_id}]}` or `{:error, reason}`.
  """
  def post do
    with u when is_binary(u) <- RootInbox.user() || {:error, :not_configured},
         rc when is_binary(rc) <- RisiMe.RisiChat.active_id(u) || {:error, :no_risi_chat},
         true <- Out.ready?() || {:error, :risi_off} do
      st = stats()

      posted =
        for {n, label, _} = e <- @engines do
          {body, sources} = message(e, u, st)

          risi = %{
            "kind" => "answer",
            "request_id" => Ecto.UUID.generate(),
            "answer" => body,
            "refs" => [],
            "confidence" => 1.0,
            "sources" => sources,
            "next_steps" => [],
            "call_ref" => nil,
            "made_by" => MadeBy.rule(),
            "notify" => [u]
          }

          case Out.post(rc, body, risi, memory: false) do
            {:ok, sent} -> {"#{n} #{label}", sent[:message_id]}
            {:error, e} -> {"#{n} #{label}", {:error, e}}
          end
        end

      # §25.4: at most 3 `next_actions`, so 4 options are lettered lines; a number works too.
      q =
        RootInbox.post(
          "voice-pick",
          "Which voice should Risi use on calls? Reply 1, 2, 3 or 4. Call Risi keeps today's voice until you pick.",
          for({n, label, _} <- @engines, do: "#{n} #{label}"),
          "ask"
        )

      {:ok, posted ++ [{"choice", q}]}
    else
      {:error, _} = e -> e
    end
  end

  @doc "For `scripts/voice-pick-post` (rpc): prints one line per message."
  def cli_post do
    case post() do
      {:ok, rows} ->
        for {label, r} <- rows, do: IO.puts("posted #{label} #{inspect(r)}")
        :ok

      {:error, e} ->
        IO.puts("refused: #{inspect(e)}")
        :error
    end
  end
end
