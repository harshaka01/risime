defmodule RisiMeWeb.Admin.SpeechLive do
  @moduledoc """
  Admin console page 1, Speech models (decision 089).

  * Per language (Sinhala, Tamil, English, Mixed/Singlish): Auto or an installed model
    (`RisiMe.Agent.SpeechRoutes`). Saving applies within 5 s, no deploy; audit-logged.
  * Test: a clip from the dev test-clip directory (`RisiMe.Admin.TestClips`, never customer
    voice notes) through `RisiMe.Agent.Speech.compare/2`: every candidate side by side with its
    text and seconds. Audit-logged (the clip's name only). Compare-only candidates from the
    extra compare service (omniASR, `RISI_SPEECH_COMPARE_EXTRA_URL`) can be ticked per test, e.g.
    "omniASR 7B (compare only, slow)"; they are never offered as a language's route.
  * The last admin actions (from the audit log).
  """
  use RisiMeWeb, :live_view

  alias RisiMe.Admin
  alias RisiMe.Admin.{Settings, TestClips}
  alias RisiMe.Agent.SpeechRoutes

  @labels %{
    "si" => "Sinhala",
    "ta" => "Tamil",
    "en" => "English",
    "mixed" => "Mixed / Singlish"
  }

  @impl true
  def mount(_params, _session, socket) do
    socket =
      socket
      |> assign(:page_title, "Speech models · RisiMe admin")
      |> assign(:installed, :loading)
      |> assign(:extra, [])
      |> assign(:extra_picked, [])
      |> assign(:clips, TestClips.list())
      |> assign(:clips_dir?, TestClips.dir() != nil)
      |> assign(:clip, nil)
      |> assign(:test, nil)
      |> assign(:notice, nil)
      |> assign(:auto, RisiMe.Agent.VoiceAuto.status(:stt))
      |> load_choices()
      |> load_audit()

    if connected?(socket), do: send(self(), :load_installed)
    {:ok, socket}
  end

  @impl true
  def handle_info(:load_installed, socket) do
    extra =
      case RisiMe.Agent.Speech.extra_models() do
        {:ok, ms} -> ms
        _ -> []
      end

    ids = Enum.map(extra, & &1.id)
    picked = Enum.filter(RisiMe.Agent.Speech.extra_default_models(), &(&1 in ids))

    {:noreply,
     socket
     |> assign(:installed, SpeechRoutes.installed())
     |> assign(:extra, extra)
     |> assign(:extra_picked, picked)}
  end

  @impl true
  def handle_event("save", %{"lang" => lang, "model" => model}, socket)
      when is_binary(lang) and is_binary(model) do
    installed =
      case socket.assigns.installed do
        {:ok, %{models: ms}} -> Enum.map(ms, & &1.id)
        _ -> []
      end

    result =
      if lang in SpeechRoutes.languages(),
        do: SpeechRoutes.put(lang, model, socket.assigns.admin_id, installed),
        else: {:error, :invalid}

    notice =
      case result do
        :ok when model == "auto" -> {:ok, "#{@labels[lang]}: back to Auto."}
        :ok -> {:ok, "#{@labels[lang]}: #{model} (live within 5 s)."}
        {:error, :not_installed} -> {:error, "#{model} is not installed on the speech service."}
        {:error, _} -> {:error, "Not saved."}
      end

    {:noreply, socket |> assign(:notice, notice) |> load_choices() |> load_audit()}
  end

  def handle_event("pick_clip", %{"clip" => clip} = params, socket) do
    {:noreply,
     socket
     |> assign(:clip, if(clip in socket.assigns.clips, do: clip))
     |> assign(:extra_picked, picked_extra(params, socket))}
  end

  def handle_event("test", %{"clip" => clip} = params, socket) do
    cond do
      socket.assigns.test == :running ->
        {:noreply, socket}

      clip not in socket.assigns.clips ->
        {:noreply, assign(socket, :notice, {:error, "Pick a test clip first."})}

      true ->
        Admin.audit(socket.assigns.admin_id, "speech_test", "clip:" <> clip)
        extra = picked_extra(params, socket)

        socket =
          socket
          |> assign(:clip, clip)
          |> assign(:extra_picked, extra)
          |> assign(:test, :running)
          |> start_async(:compare, fn ->
            with {:ok, audio} <- TestClips.read(clip) do
              RisiMe.Agent.Speech.compare(audio, extra_models: extra)
            end
          end)
          |> load_audit()

        {:noreply, socket}
    end
  end

  def handle_event("refresh", _params, socket) do
    send(self(), :load_installed)
    {:noreply, socket |> assign(:installed, :loading) |> assign(:clips, TestClips.list())}
  end

  @impl true
  def handle_async(:compare, {:ok, result}, socket) do
    {:noreply, assign(socket, :test, result)}
  end

  def handle_async(:compare, {:exit, _reason}, socket) do
    {:noreply, assign(socket, :test, {:error, :failed})}
  end

  # Ticked compare-only candidates, limited to what the extra service serves.
  defp picked_extra(params, socket) do
    ids = Enum.map(socket.assigns.extra, & &1.id)
    params |> Map.get("extra", []) |> List.wrap() |> Enum.filter(&(&1 in ids))
  end

  @doc false
  def extra_label(%{id: id} = m) do
    size =
      cond do
        SpeechRoutes.omni_7b?(id) -> "omniASR 7B"
        SpeechRoutes.omni_3b?(id) -> "omniASR 3B"
        SpeechRoutes.omni_1b?(id) -> "omniASR 1B"
        true -> id
      end

    kind = if id =~ ~r/ctc/i, do: " CTC", else: ""
    size <> kind <> if(m[:streamed], do: " (compare only, slow)", else: " (compare only)")
  end

  defp load_choices(socket) do
    langs = SpeechRoutes.languages()

    assign(socket,
      choices: SpeechRoutes.choices(),
      sources: Map.new(langs, &{&1, Settings.source(SpeechRoutes.setting_key(&1))})
    )
  end

  defp load_audit(socket), do: assign(socket, :audit, Admin.recent_audit(15))

  @impl true
  def render(assigns) do
    assigns = assign(assigns, labels: @labels, langs: SpeechRoutes.languages())

    ~H"""
    <RisiMeWeb.Admin.Layouts.shell current="speech" admin_name={@admin_name}>
      <h1>Speech models</h1>
      <p class="muted">
        Which model transcribes each language for Risi (calls and voice notes). A change applies
        within 5 seconds, with no deploy. A manual choice wins over Auto until you set it back.
      </p>

      <div
        :if={@notice}
        class={["flash", elem(@notice, 0) == :error && "bad", elem(@notice, 0) == :ok && "ok"]}
      >
        {elem(@notice, 1)}
      </div>

      <div class="card">
        <div class="row" style="justify-content:space-between">
          <strong>Routing</strong>
          <button class="btn" phx-click="refresh">Refresh</button>
        </div>
        <p :if={@installed == :loading} class="muted">Asking the speech service…</p>
        <p :if={@installed == {:error, :unavailable}} class="warn">
          The speech service is not reachable, so models can't be listed. Setting a language back to Auto still works.
        </p>
        <table>
          <tr>
            <th>Language</th>
            <th>Now</th>
            <th>Auto uses today</th>
            <th>Choose</th>
          </tr>
          <tr :for={lang <- @langs}>
            <td>{@labels[lang]}</td>
            <td>
              <strong>{if @choices[lang] == "auto", do: "Auto", else: @choices[lang]}</strong>
              <span :if={@sources[lang] == :env} class="muted"> (from the server environment)</span>
            </td>
            <td class="muted">{auto_route(@installed, lang)}</td>
            <td>
              <form phx-submit="save" class="row">
                <input type="hidden" name="lang" value={lang} />
                <select name="model" aria-label={"Model for " <> @labels[lang]}>
                  <option value="auto" selected={@choices[lang] == "auto"}>Auto</option>
                  <option
                    :for={m <- candidates(@installed, lang, @choices[lang])}
                    value={m.id}
                    selected={@choices[lang] == m.id}
                  >
                    {m.id}{if m.language, do: " (" <> m.language <> ")", else: ""}
                  </option>
                </select>
                <button class="btn primary" type="submit">Save</button>
              </form>
            </td>
          </tr>
        </table>
        <p class="muted">
          Auto today is the production route: the speech service detects the language and picks its
          routed model. Mixed / Singlish means the chunks the detector is unsure about.
        </p>
        <p><strong>Auto:</strong> <span class="muted">{@auto.text}</span></p>
      </div>

      <div class="card">
        <strong>Models</strong>
        <table>
          <tr>
            <th>Family</th>
            <th>On this server</th>
          </tr>
          <tr :for={f <- catalogue(@installed, @extra)}>
            <td>{f.family}</td>
            <td>
              <span :if={f[:phone]} class="muted">
                On the phone only (developer flag phone_stt), not a server model
              </span>
              <span :if={!f[:phone] and f.installed == []} class="muted">not installed</span>
              <span :if={f.installed != []} class="ok">
                {Enum.map_join(f.installed, ", ", &compare_only_tag(&1, @extra))}
              </span>
            </td>
          </tr>
        </table>
      </div>

      <div class="card">
        <strong>Test</strong>
        <p class="muted">
          Runs every candidate model on one dev test clip, side by side. Only clips from the test-clip
          directory, never customer voice notes.
        </p>
        <p :if={!@clips_dir?} class="warn">
          No test-clip directory is set (RISI_ADMIN_TEST_CLIPS_DIR).
        </p>
        <p :if={@clips_dir? and @clips == []} class="warn">The test-clip directory has no clips.</p>
        <form :if={@clips != []} phx-submit="test" phx-change="pick_clip" class="row">
          <select name="clip" aria-label="Test clip">
            <option :for={c <- @clips} value={c} selected={c == @clip}>{c}</option>
          </select>
          <label :for={m <- @extra}>
            <input type="checkbox" name="extra[]" value={m.id} checked={m.id in @extra_picked} />
            {extra_label(m)}
          </label>
          <button class="btn primary" type="submit" disabled={@test == :running}>
            {if @test == :running, do: "Running…", else: "Test"}
          </button>
        </form>
        <.test_result test={@test} />
      </div>

      <h2>Recent admin actions</h2>
      <div class="card">
        <table>
          <tr>
            <th>When (UTC)</th>
            <th>Who</th>
            <th>Action</th>
            <th>Target</th>
            <th>Before → after</th>
          </tr>
          <tr :for={e <- @audit}>
            <td>{Calendar.strftime(e.at, "%Y-%m-%d %H:%M:%S")}</td>
            <td>{e.actor}</td>
            <td>{e.action}</td>
            <td>{e.target}</td>
            <td>{if e.before || e.after, do: "#{e.before || "-"} → #{e.after || "-"}", else: ""}</td>
          </tr>
        </table>
      </div>
    </RisiMeWeb.Admin.Layouts.shell>
    """
  end

  attr :test, :any, required: true

  defp test_result(%{test: {:ok, %{results: _}}} = assigns) do
    ~H"""
    <p class="muted">
      Detected language: <strong>{elem(@test, 1).language_detected}</strong>; Auto would use
      <strong>{elem(@test, 1).chosen}</strong>.
    </p>
    <table>
      <tr>
        <th>Model</th>
        <th>Text</th>
        <th>Seconds</th>
      </tr>
      <tr :for={r <- elem(@test, 1).results}>
        <td>
          {r.model}<span :if={r.model == elem(@test, 1).chosen} class="tag">Auto</span>
        </td>
        <td class="text">
          <span :if={r.error} class="bad">{r.error}</span>
          <span :if={!r.error}>{r.text}</span>
          <span :if={Map.get(r, :romanised) == true} class="warn"> (not in Sinhala script)</span>
        </td>
        <td>{seconds(r.elapsed_ms)}</td>
      </tr>
    </table>
    """
  end

  defp test_result(%{test: {:error, _}} = assigns) do
    ~H"""
    <p class="bad">The test did not run (speech service unavailable, timed out or clip unreadable).</p>
    """
  end

  defp test_result(assigns), do: ~H""

  defp seconds(ms) when is_integer(ms), do: :erlang.float_to_binary(ms / 1000, decimals: 2)
  defp seconds(_), do: "-"

  # The installed models for `lang`, plus the current manual choice if it isn't among them.
  defp candidates(installed, lang, choice) do
    ms =
      case installed do
        {:ok, %{models: models}} -> SpeechRoutes.candidates(models, lang)
        _ -> []
      end

    if choice == "auto" or Enum.any?(ms, &(&1.id == choice)),
      do: ms,
      else: ms ++ [%{id: choice, language: "not installed"}]
  end

  # The families, including the extra compare service's models (shown "compare only"; they are
  # never offered as a route: `candidates/3` only sees the speech service's models).
  defp catalogue(installed, extra) do
    models =
      case installed do
        {:ok, %{models: models}} -> models
        _ -> []
      end

    SpeechRoutes.catalogue(models ++ Enum.map(extra, &%{id: &1.id}))
  end

  defp compare_only_tag(id, extra) do
    if Enum.any?(extra, &(&1.id == id)), do: id <> " (compare only)", else: id
  end

  defp auto_route({:ok, %{routes: routes}}, lang), do: Map.get(routes, lang, "-")
  defp auto_route(_, _), do: "-"
end
