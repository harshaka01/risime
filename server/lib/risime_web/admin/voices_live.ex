defmodule RisiMeWeb.Admin.VoicesLive do
  @moduledoc """
  Admin console page 2, Voices / text-to-speech (decision 089, Part 12 item 2; candidates
  2026-10-14).

  * Per language (Sinhala, Mixed / Singlish, Tamil, English): Auto or an installed voice
    (`RisiMe.Agent.TTS`, the engines registered in `:risi_tts_engines`). Saving applies within
    5 s, no deploy; audit-logged. Catalogue engines that aren't registered show "not installed";
    engines whose weights haven't arrived show "downloading tonight".
  * Engines: each with its languages, licence and a "production OK?" flag (no non-commercial;
    Llama-derived only under the Llama licence; watermark noted). Risi's voice is a built-in or
    stock voice, never a clone of a real person.
  * Test: a sentence the admin types, spoken by one voice per engine (or every voice), one after
    another (the machine is shared), each with its load time, time to first audio, real-time
    factor and a player. Results appear as each voice finishes. The sentence is neither logged
    nor stored (the audit log records only its length).
  * Rating: 👍 / 👎 per reply (`RisiMe.Agent.TTS.Ratings`: audit-logged with the reply's latency,
    summed into the Voice agent's scoreboard row).
  * Call Risi (contract v1.48 §41.6): the maximum length of one Risi call, in minutes (1–60, the
    setting `call_risi_max_s` in seconds, default 30 min). Live within 5 s for new calls; audit-logged.
  """
  use RisiMeWeb, :live_view

  alias RisiMe.Admin
  alias RisiMe.Admin.Settings
  alias RisiMe.Agent.CallRisi
  alias RisiMe.Agent.TTS
  alias RisiMe.Agent.TTS.CallVoice

  @labels %{
    "si" => "Sinhala",
    "mixed" => "Mixed / Singlish",
    "ta" => "Tamil",
    "en" => "English"
  }
  @max_text 300
  @max_audio 8 * 1024 * 1024

  @impl true
  def mount(_params, _session, socket) do
    socket =
      socket
      |> assign(:page_title, "Voices · RisiMe admin")
      |> assign(:installed, :loading)
      |> assign(:test, nil)
      |> assign(:results, [])
      |> assign(:pending, [])
      |> assign(:text, "")
      |> assign(:which, "each_engine")
      |> assign(:test_lang, "any")
      |> assign(:notice, nil)
      |> assign(:auto, RisiMe.Agent.VoiceAuto.status(:tts))
      |> assign(:rate, rate_rows())
      |> assign(:rate_state, nil)
      |> assign(:rate_pending, [])
      |> load_choices()

    if connected?(socket), do: send(self(), :load_installed)
    {:ok, socket}
  end

  @impl true
  def handle_info(:load_installed, socket) do
    {:noreply, assign(socket, :installed, TTS.installed())}
  end

  @impl true
  def handle_event("save", %{"lang" => lang, "voice" => voice}, socket)
      when is_binary(lang) and is_binary(voice) do
    ids = socket |> voices() |> Enum.map(& &1.id)

    result =
      if lang in TTS.languages(),
        do: TTS.put(lang, voice, socket.assigns.admin_id, ids),
        else: {:error, :invalid}

    notice =
      case result do
        :ok when voice == "auto" -> {:ok, "#{@labels[lang]}: back to Auto."}
        :ok -> {:ok, "#{@labels[lang]}: #{voice} (live within 5 s)."}
        {:error, :not_installed} -> {:error, "#{voice} is not installed."}
        {:error, _} -> {:error, "Not saved."}
      end

    {:noreply, socket |> assign(:notice, notice) |> load_choices()}
  end

  def handle_event("call_risi_max", %{"minutes" => minutes}, socket) when is_binary(minutes) do
    {lo, hi} = CallRisi.max_s_bounds()

    result =
      case Integer.parse(String.trim(minutes)) do
        {m, ""} -> CallRisi.put_max_s(m * 60, socket.assigns.admin_id)
        _ -> {:error, :invalid}
      end

    notice =
      case result do
        :ok ->
          {:ok,
           "Call Risi: at most #{minutes(CallRisi.max_s())} per call (new calls, within 5 s)."}

        {:error, _} ->
          {:error, "Type whole minutes from #{div(lo, 60)} to #{div(hi, 60)}."}
      end

    {:noreply, socket |> assign(:notice, notice) |> load_choices()}
  end

  def handle_event("call_risi_max_reset", _params, socket) do
    :ok = CallRisi.reset_max_s(socket.assigns.admin_id)

    {:noreply,
     socket
     |> assign(:notice, {:ok, "Call Risi: back to #{minutes(CallRisi.max_s())} per call."})
     |> load_choices()}
  end

  def handle_event("test", %{"text" => text} = params, socket) when is_binary(text) do
    text = String.trim(text)
    which = if params["which"] == "each_engine", do: "each_engine", else: "all"
    lang = if params["lang"] in TTS.languages(), do: params["lang"], else: "any"
    picked = pick(voices(socket), which, lang)

    cond do
      socket.assigns.test == :running ->
        {:noreply, socket}

      text == "" or String.length(text) > @max_text ->
        {:noreply,
         assign(socket, :notice, {:error, "Type a sentence (up to #{@max_text} characters)."})}

      picked == [] ->
        {:noreply,
         assign(socket, :notice, {:error, "No voice is installed for that choice yet."})}

      true ->
        Admin.audit(
          socket.assigns.admin_id,
          "tts_test",
          "#{length(picked)} voices",
          nil,
          "#{String.length(text)} characters"
        )

        installed = if is_list(socket.assigns.installed), do: socket.assigns.installed, else: []

        socket =
          socket
          |> assign(test: :running, text: text, which: which, test_lang: lang, notice: nil)
          |> assign(results: [], pending: picked)
          |> next_voice(installed)

        {:noreply, socket}
    end
  end

  def handle_event("rate", %{"i" => i, "score" => score}, socket)
      when score in ["up", "down"] do
    with {n, ""} <- Integer.parse(to_string(i)),
         %{result: {:ok, a}, rated: nil} = r <- Enum.at(socket.assigns.results, n) do
      RisiMe.Agent.TTS.Ratings.rate(socket.assigns.admin_id, r.voice.id, score, a)
      results = List.replace_at(socket.assigns.results, n, %{r | rated: score})
      {:noreply, assign(socket, :results, results)}
    else
      _ -> {:noreply, socket}
    end
  end

  # "Rate for Call Risi" (item C, 2026-10-15): every voice speaks every sample, one after another.
  def handle_event("rate_prepare", _params, socket) do
    if socket.assigns.rate_state == :running do
      {:noreply, socket}
    else
      Admin.audit(socket.assigns.admin_id, "tts_test", "call_risi_voices", nil, "samples")
      rows = rate_rows()
      queue = for r <- rows, i <- 0..(length(CallVoice.samples()) - 1), do: {r.id, i}

      {:noreply,
       socket
       |> assign(rate: rows, rate_state: :running, rate_pending: queue, notice: nil)
       |> next_sample()}
    end
  end

  def handle_event("rate_vote", %{"v" => id, "score" => score}, socket)
      when score in ["up", "down"] do
    case Enum.find(socket.assigns.rate, &(&1.id == id)) do
      %{rated: nil, results: results} = row ->
        latency =
          case Enum.find(results, &match?({:ok, _}, &1)) do
            {:ok, a} -> %{a | first_audio_ms: CallVoice.first_ms(results)}
            _ -> %{}
          end

        RisiMe.Agent.TTS.Ratings.rate(socket.assigns.admin_id, id, score, latency)
        {:noreply, put_row(socket, %{row | rated: score})}

      _ ->
        {:noreply, socket}
    end
  end

  def handle_event("rate_pick", %{"v" => id}, socket) do
    ids = socket |> voices() |> Enum.map(& &1.id)

    notice =
      case Enum.find(socket.assigns.rate, &(&1.id == id)) do
        nil ->
          {:error, "Not saved."}

        row ->
          case CallVoice.pick(id, row.results, socket.assigns.admin_id, ids) do
            :ok -> {:ok, "Call Risi now speaks with #{row.name} (new calls, within 5 s)."}
            {:error, :not_installed} -> {:error, "#{row.name} is not installed."}
            {:error, reason} -> {:error, "#{row.name} can't be picked: #{live_text(reason)}."}
          end
      end

    rows =
      for r <- socket.assigns.rate,
          do: %{r | now?: r.id == RisiMe.Agent.CallRisi.voice("en")}

    {:noreply, socket |> assign(notice: notice, rate: rows) |> load_choices()}
  end

  def handle_event("refresh", _params, socket) do
    send(self(), :load_installed)
    {:noreply, assign(socket, :installed, :loading)}
  end

  @impl true
  def handle_async(:speak, {:ok, result}, socket) do
    installed = if is_list(socket.assigns.installed), do: socket.assigns.installed, else: []

    {:noreply,
     socket |> assign(:results, socket.assigns.results ++ [result]) |> next_voice(installed)}
  end

  def handle_async(:speak, {:exit, _}, socket),
    do: {:noreply, assign(socket, test: {:error, :failed}, pending: [])}

  def handle_async(:rate_speak, {:ok, {id, i, result}}, socket) do
    socket =
      case Enum.find(socket.assigns.rate, &(&1.id == id)) do
        nil -> socket
        row -> put_row(socket, %{row | results: List.replace_at(row.results, i, result)})
      end

    {:noreply, next_sample(socket)}
  end

  def handle_async(:rate_speak, {:exit, _}, socket),
    do: {:noreply, socket |> assign(:rate_pending, []) |> next_sample()}

  defp next_sample(socket) do
    case socket.assigns.rate_pending do
      [] ->
        assign(socket, :rate_state, :done)

      [{id, i} | rest] ->
        installed =
          if is_list(socket.assigns.installed),
            do: socket.assigns.installed,
            else: RisiMe.Agent.TTS.installed()

        socket
        |> assign(rate_pending: rest, installed: installed)
        |> start_async(:rate_speak, fn -> {id, i, CallVoice.speak(id, i, installed)} end)
    end
  end

  # One row per voice: `results` holds one slot per sample (nil until it has been spoken).
  defp rate_rows do
    n = length(CallVoice.samples())
    for v <- CallVoice.lineup(), do: Map.merge(v, %{results: List.duplicate(nil, n), rated: nil})
  end

  defp put_row(socket, row),
    do:
      assign(
        socket,
        :rate,
        Enum.map(socket.assigns.rate, &if(&1.id == row.id, do: row, else: &1))
      )

  @doc false
  def live_text(:ok), do: "fits live calls"
  def live_text(:unmeasured), do: "play the samples first"
  def live_text({:too_slow, ms}), do: "too slow for live calls (measured #{ms} ms)"

  def live_text({:not_streamed, ms}),
    do:
      "measured #{ms} ms, but Call Risi streams only from the resident Kokoro service, so this " <>
        "engine can't speak live calls yet"

  def live_text({:cannot_run, e}), do: "can't run now: #{error_text(e)}"
  def live_text(other), do: inspect(other)

  defp next_voice(socket, installed) do
    case socket.assigns.pending do
      [] ->
        assign(socket, :test, :done)

      [v | rest] ->
        text = socket.assigns.text

        socket
        |> assign(:pending, rest)
        |> start_async(:speak, fn -> speak_one(text, v, installed) end)
    end
  end

  @doc false
  # The voices a test speaks: every one, or the first of each engine; optionally one language.
  def pick(voices, which, lang) do
    voices = if lang == "any", do: voices, else: TTS.candidates(voices, lang)

    if which == "each_engine",
      do: Enum.uniq_by(voices, & &1[:engine]),
      else: voices
  end

  @doc false
  # One voice after another (the machine is shared): `[%{voice, result, rated}]`.
  def speak_all(text, voices, installed), do: Enum.map(voices, &speak_one(text, &1, installed))

  @doc false
  def speak_one(text, v, installed) do
    r =
      case TTS.synthesize(text, v.id, installed: installed) do
        {:ok, %{audio: a} = ok} when byte_size(a) <= @max_audio ->
          {:ok,
           %{
             first_audio_ms: ok.first_audio_ms,
             total_ms: ok.total_ms,
             load_ms: Map.get(ok, :load_ms, 0),
             rtf: rtf(ok),
             src: "data:#{ok.content_type};base64," <> Base.encode64(a)
           }}

        {:ok, _} ->
          {:error, :too_large}

        {:error, e} ->
          {:error, e}
      end

    %{voice: v, result: r, rated: nil}
  end

  # Real-time factor: synthesis time (without the model load) ÷ audio length. < 1 is faster than
  # real time. The audio length comes from the engine, or from a 16-bit mono WAV's size.
  defp rtf(%{total_ms: total} = ok) do
    audio_ms = ok[:audio_ms] || wav_ms(ok[:audio])

    if is_integer(audio_ms) and audio_ms > 0,
      do: Float.round(max(total - Map.get(ok, :load_ms, 0), 0) / audio_ms, 2),
      else: nil
  end

  defp wav_ms(
         <<"RIFF", _::32, "WAVE", "fmt ", 16::little-32, 1::little-16, 1::little-16,
           sr::little-32, _::32, _::16, 16::little-16, "data", size::little-32, _::binary>>
       )
       when sr > 0,
       do: div(size * 1000, sr * 2)

  defp wav_ms(_), do: nil

  defp voices(socket) do
    case socket.assigns.installed do
      list when is_list(list) -> TTS.voices(list)
      _ -> []
    end
  end

  defp load_choices(socket) do
    assign(socket,
      choices: TTS.choices(),
      sources: Map.new(TTS.languages(), &{&1, Settings.source(TTS.setting_key(&1))}),
      call_risi_max_s: CallRisi.max_s(),
      call_risi_source: CallRisi.max_s_source()
    )
  end

  defp minutes(s) when rem(s, 60) == 0, do: "#{div(s, 60)} min"
  defp minutes(s), do: "#{div(s, 60)} min #{rem(s, 60)} s"

  @impl true
  def render(assigns) do
    assigns =
      assign(assigns,
        labels: @labels,
        langs: TTS.languages(),
        all_voices: if(is_list(assigns.installed), do: TTS.voices(assigns.installed), else: [])
      )

    ~H"""
    <RisiMeWeb.Admin.Layouts.shell current="voices" admin_name={@admin_name}>
      <h1>Voices (text-to-speech)</h1>
      <p class="muted">
        Which voice Risi speaks with in each language (Call Risi, spoken answers). A change applies
        within 5 seconds, with no deploy. A manual choice wins over Auto until you set it back.
      </p>

      <div
        :if={@notice}
        class={["flash", elem(@notice, 0) == :error && "bad", elem(@notice, 0) == :ok && "ok"]}
      >
        {elem(@notice, 1)}
      </div>

      <div class="card" id="rate-call-risi">
        <div class="row" style="justify-content:space-between">
          <strong>Rate for Call Risi</strong>
          <button
            class="btn primary"
            phx-click="rate_prepare"
            disabled={@rate_state == :running}
          >
            {cond do
              @rate_state == :running -> "Preparing… #{length(@rate_pending)} to go"
              @rate_state == :done -> "Speak the samples again"
              true -> "Prepare the samples"
            end}
          </button>
        </div>
        <p class="muted">
          The same Risi answers in each voice. Listen, rate each voice 👍 or 👎, and tap
          <em>Use for Call Risi</em>
          on the one you want: new calls speak with it within 5 seconds (audit-logged). Live calls
          need the first audio within {CallVoice.budget_ms()} ms (p50); a voice outside that can be
          rated but not picked.
        </p>
        <ol class="muted">
          <li :for={s <- CallVoice.samples()}>{s}</li>
        </ol>
        <table>
          <tr>
            <th>Voice</th>
            <th :for={{_, n} <- Enum.with_index(CallVoice.samples(), 1)}>Sample {n}</th>
            <th>First audio</th>
            <th>Rate</th>
            <th>Call Risi</th>
          </tr>
          <tr :for={r <- @rate}>
            <td>
              <strong>{r.name}</strong>
              <span :if={r.now?} class="ok"> · now</span>
              <div class="muted">{r.why}</div>
            </td>
            <td :for={res <- r.results}>
              <%= case res do %>
                <% nil -> %>
                  <span class="muted">{if @rate_state == :running, do: "…", else: "-"}</span>
                <% {:ok, a} -> %>
                  <audio controls preload="none" src={a.src}></audio>
                <% {:error, e} -> %>
                  <span class="bad">{error_text(e)}</span>
              <% end %>
            </td>
            <td>
              {seconds(CallVoice.first_ms(r.results))} s
              <div class={[
                "muted",
                match?({:too_slow, _}, CallVoice.live(r.id, r.results)) && "bad"
              ]}>
                {live_text(CallVoice.live(r.id, r.results))}
              </div>
            </td>
            <td>
              <%= cond do %>
                <% r.rated -> %>
                  <span class="muted">{if r.rated == "up", do: "👍", else: "👎"} saved</span>
                <% Enum.any?(r.results, &match?({:ok, _}, &1)) -> %>
                  <button class="btn" phx-click="rate_vote" phx-value-v={r.id} phx-value-score="up">
                    👍
                  </button>
                  <button
                    class="btn"
                    phx-click="rate_vote"
                    phx-value-v={r.id}
                    phx-value-score="down"
                  >
                    👎
                  </button>
                <% true -> %>
                  <span class="muted">-</span>
              <% end %>
            </td>
            <td>
              <%= cond do %>
                <% r.now? -> %>
                  <strong class="ok">in use</strong>
                <% CallVoice.live(r.id, r.results) == :ok -> %>
                  <button class="btn primary" phx-click="rate_pick" phx-value-v={r.id}>
                    Use for Call Risi
                  </button>
                <% true -> %>
                  <span class="muted">-</span>
              <% end %>
            </td>
          </tr>
        </table>
      </div>

      <div class="card">
        <div class="row" style="justify-content:space-between">
          <strong>Voice per language</strong>
          <button class="btn" phx-click="refresh">Refresh</button>
        </div>
        <p :if={@installed == :loading} class="muted">Asking the voice engines…</p>
        <table>
          <tr>
            <th>Language</th>
            <th>Now</th>
            <th>Choose</th>
          </tr>
          <tr :for={lang <- @langs}>
            <td>{@labels[lang]}</td>
            <td>
              <strong>{if @choices[lang] == "auto", do: "Auto", else: @choices[lang]}</strong>
              <span :if={@sources[lang] == :env} class="muted"> (from the server environment)</span>
            </td>
            <td>
              <form phx-submit="save" class="row">
                <input type="hidden" name="lang" value={lang} />
                <select name="voice" aria-label={"Voice for " <> @labels[lang]}>
                  <option value="auto" selected={@choices[lang] == "auto"}>Auto</option>
                  <option
                    :for={v <- options(@all_voices, lang, @choices[lang])}
                    value={v.id}
                    selected={@choices[lang] == v.id}
                  >
                    {v.name || v.id}
                  </option>
                </select>
                <button class="btn primary" type="submit">Save</button>
              </form>
            </td>
          </tr>
        </table>
        <p>
          <strong>Auto:</strong>
          <span class="muted">
            today's route: the voice service's own default voice for the language. {@auto.text}
          </span>
        </p>
        <p class="muted">
          Call Risi speaks only with Kokoro voices (resident for live calls); the other engines are
          for comparing on this page.
        </p>
      </div>

      <div class="card">
        <strong>Engines</strong>
        <table>
          <tr>
            <th>Engine</th>
            <th>On this server</th>
            <th>Languages</th>
            <th>Licence</th>
            <th>Production OK?</th>
          </tr>
          <tr :for={e <- RisiMe.Agent.TTS.catalogue_info()}>
            <td>{e.name}</td>
            <td>{engine_status(@installed, e)}</td>
            <td>{Enum.join(e.languages, ", ")}</td>
            <td>{e.licence}</td>
            <td>
              <strong class={production_class(e.production)}>{production(e.production)}</strong>
              <div class="muted">{e.note}</div>
            </td>
          </tr>
        </table>
      </div>

      <div class="card">
        <strong>Call Risi</strong>
        <p class="muted">
          The longest one voice call with Risi may last; the call ends at the limit. A change
          applies to new calls within 5 seconds, with no deploy.
        </p>
        <p>
          Now: <strong>{minutes(@call_risi_max_s)}</strong>
          <span :if={@call_risi_source == :env} class="muted"> (from the server environment)</span>
          <span :if={@call_risi_source == :default} class="muted"> (default)</span>
        </p>
        <form phx-submit="call_risi_max" class="row">
          <input
            type="number"
            name="minutes"
            min="1"
            max="60"
            step="1"
            value={div(@call_risi_max_s, 60)}
            style="width:6em;font:inherit;padding:5px 8px;border-radius:6px;border:1px solid var(--line);background:var(--card);color:var(--fg)"
            aria-label="Maximum Call Risi length in minutes"
          />
          <span>minutes (1–60)</span>
          <button class="btn primary" type="submit">Save</button>
          <button
            :if={@call_risi_source == :table}
            class="btn"
            type="button"
            phx-click="call_risi_max_reset"
          >
            Back to default
          </button>
        </form>
      </div>

      <div class="card">
        <strong>Test</strong>
        <p class="muted">
          Type a sentence; the chosen voices speak it one after another, with the time to load the
          model, to the first audio, and the real-time factor (below 1 is faster than real time).
          Rate each reply 👍 or 👎: it feeds the Voice agent's score. The sentence is not stored.
        </p>
        <form phx-submit="test" class="row">
          <input
            type="text"
            name="text"
            value={@text}
            maxlength="300"
            style="flex:1 1 260px;font:inherit;padding:5px 8px;border-radius:6px;border:1px solid var(--line);background:var(--card);color:var(--fg)"
            aria-label="Sentence to speak"
          />
          <select name="which" aria-label="Which voices">
            <option value="each_engine" selected={@which == "each_engine"}>
              One voice per engine
            </option>
            <option value="all" selected={@which == "all"}>Every voice</option>
          </select>
          <select name="lang" aria-label="Language">
            <option value="any" selected={@test_lang == "any"}>Any language</option>
            <option :for={l <- @langs} value={l} selected={@test_lang == l}>{@labels[l]}</option>
          </select>
          <button class="btn primary" type="submit" disabled={@test == :running}>
            {if @test == :running, do: "Speaking…", else: "Test"}
          </button>
        </form>
        <.speak_result results={@results} test={@test} pending={length(@pending)} />
      </div>
    </RisiMeWeb.Admin.Layouts.shell>
    """
  end

  attr :results, :list, required: true
  attr :test, :any, required: true
  attr :pending, :integer, required: true

  defp speak_result(%{test: {:error, _}} = assigns) do
    ~H"""
    <p class="bad">The test did not run.</p>
    """
  end

  defp speak_result(%{results: [_ | _]} = assigns) do
    ~H"""
    <table>
      <tr>
        <th>Voice</th>
        <th>Load (s)</th>
        <th>First audio (s)</th>
        <th>Total (s)</th>
        <th>RTF</th>
        <th>Listen</th>
        <th>Rate</th>
      </tr>
      <tr :for={{r, i} <- Enum.with_index(@results)}>
        <td>{r.voice[:name] || r.voice.id}</td>
        <%= case r.result do %>
          <% {:ok, a} -> %>
            <td>{seconds(a[:load_ms])}</td>
            <td>{seconds(a.first_audio_ms)}</td>
            <td>{seconds(a.total_ms)}</td>
            <td>{a[:rtf] || "-"}</td>
            <td><audio controls preload="none" src={a.src}></audio></td>
            <td>
              <%= if r.rated do %>
                <span class="muted">{if r.rated == "up", do: "👍", else: "👎"} saved</span>
              <% else %>
                <button class="btn" phx-click="rate" phx-value-i={i} phx-value-score="up">👍</button>
                <button class="btn" phx-click="rate" phx-value-i={i} phx-value-score="down">
                  👎
                </button>
              <% end %>
            </td>
          <% {:error, e} -> %>
            <td colspan="6" class="bad">{error_text(e)}</td>
        <% end %>
      </tr>
    </table>
    <p :if={@test == :running} class="muted">Speaking… {@pending} more to go.</p>
    """
  end

  defp speak_result(%{test: :running} = assigns) do
    ~H"""
    <p class="muted">Speaking…</p>
    """
  end

  defp speak_result(assigns), do: ~H""

  defp seconds(ms) when is_integer(ms), do: :erlang.float_to_binary(ms / 1000, decimals: 2)
  defp seconds(_), do: "-"

  @doc false
  def error_text(:live_call), do: "skipped: a Call Risi call is running (live calls first)"
  def error_text(:no_memory), do: "refused: not enough free memory to load the model now"
  def error_text(:paused), do: "paused: live speech needs the machine now"
  def error_text(:busy), do: "busy: another test is using the voice lab"
  def error_text(:pinned), do: "skipped: the voice lab is holding Call Risi's voice"
  def error_text(:downloading), do: "downloading tonight"
  def error_text(:review_pending), do: "waiting for its code review"
  def error_text(:not_installed), do: "not installed"
  def error_text(:timeout), do: "timed out"
  def error_text(:too_large), do: "the audio is too long for the page"
  def error_text(:unavailable), do: "the voice service isn't reachable"
  def error_text({:http, status}), do: "the voice service answered HTTP #{status}"
  def error_text({:exception, msg}), do: "error: #{msg}"
  def error_text(e) when is_atom(e), do: to_string(e)
  def error_text(e), do: inspect(e)

  defp options(voices, lang, choice) do
    vs = TTS.candidates(voices, lang)

    if choice == "auto" or Enum.any?(vs, &(&1.id == choice)),
      do: vs,
      else: vs ++ [%{id: choice, name: choice <> " (not installed)"}]
  end

  defp production(:yes), do: "yes"
  defp production(:conditional), do: "with conditions"
  defp production(:no), do: "no"

  defp production_class(:yes), do: "ok"
  defp production_class(:no), do: "bad"
  defp production_class(_), do: nil

  @doc false
  def engine_status(:loading, _), do: "…"
  def engine_status(_installed, %{unavailable: text}), do: text

  def engine_status(installed, %{id: id}) when is_list(installed) do
    case Enum.find(installed, &(&1.id == id)) do
      nil -> "not installed"
      %{status: :downloading} -> "downloading tonight"
      %{status: :review_pending} -> "weights here; waiting for its code review"
      %{status: :unavailable} -> "registered, but its service is not answering"
      %{voices: vs} -> "installed: #{length(vs)} voices"
    end
  end
end
