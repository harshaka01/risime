defmodule RisiMeWeb.Admin.VoicesLive do
  @moduledoc """
  Admin console page 2, Voices / text-to-speech (decision 089, Part 12 item 2).

  * Per language (Sinhala, Mixed / Singlish, Tamil, English): Auto or an installed voice
    (`RisiMe.Agent.TTS`, the engines registered in `:risi_tts_engines`). Saving applies within
    5 s, no deploy; audit-logged. Catalogue engines that aren't registered show "not installed".
  * Test: a sentence the admin types, spoken by every installed voice one after another, each
    with its time to first audio and a player. The sentence is neither logged nor stored (the
    audit log records only its length).
  """
  use RisiMeWeb, :live_view

  alias RisiMe.Admin
  alias RisiMe.Admin.Settings
  alias RisiMe.Agent.TTS

  @labels %{
    "si" => "Sinhala",
    "mixed" => "Mixed / Singlish",
    "ta" => "Tamil",
    "en" => "English"
  }
  @max_text 300
  @max_audio 5 * 1024 * 1024

  @impl true
  def mount(_params, _session, socket) do
    socket =
      socket
      |> assign(:page_title, "Voices · RisiMe admin")
      |> assign(:installed, :loading)
      |> assign(:test, nil)
      |> assign(:text, "")
      |> assign(:notice, nil)
      |> assign(:auto, RisiMe.Agent.VoiceAuto.status(:tts))
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

  def handle_event("test", %{"text" => text}, socket) when is_binary(text) do
    text = String.trim(text)
    installed = if is_list(socket.assigns.installed), do: socket.assigns.installed, else: []
    voices = voices(socket)

    cond do
      socket.assigns.test == :running ->
        {:noreply, socket}

      text == "" or String.length(text) > @max_text ->
        {:noreply,
         assign(socket, :notice, {:error, "Type a sentence (up to #{@max_text} characters)."})}

      voices == [] ->
        {:noreply, assign(socket, :notice, {:error, "No voice is installed yet."})}

      true ->
        Admin.audit(
          socket.assigns.admin_id,
          "tts_test",
          "#{length(voices)} voices",
          nil,
          "#{String.length(text)} characters"
        )

        socket =
          socket
          |> assign(:test, :running)
          |> assign(:text, text)
          |> start_async(:speak, fn -> speak_all(text, voices, installed) end)

        {:noreply, socket}
    end
  end

  def handle_event("refresh", _params, socket) do
    send(self(), :load_installed)
    {:noreply, assign(socket, :installed, :loading)}
  end

  @impl true
  def handle_async(:speak, {:ok, results}, socket), do: {:noreply, assign(socket, :test, results)}

  def handle_async(:speak, {:exit, _}, socket),
    do: {:noreply, assign(socket, :test, {:error, :failed})}

  @doc false
  # One voice after another (the machine is shared): `[%{voice, result}]`.
  def speak_all(text, voices, installed) do
    for v <- voices do
      r =
        case TTS.synthesize(text, v.id, installed: installed) do
          {:ok, %{audio: a} = ok} when byte_size(a) <= @max_audio ->
            {:ok,
             %{
               first_audio_ms: ok.first_audio_ms,
               total_ms: ok.total_ms,
               src: "data:#{ok.content_type};base64," <> Base.encode64(a)
             }}

          {:ok, _} ->
            {:error, :too_large}

          {:error, e} ->
            {:error, e}
        end

      %{voice: v, result: r}
    end
  end

  defp voices(socket) do
    case socket.assigns.installed do
      list when is_list(list) -> TTS.voices(list)
      _ -> []
    end
  end

  defp load_choices(socket) do
    assign(socket,
      choices: TTS.choices(),
      sources: Map.new(TTS.languages(), &{&1, Settings.source(TTS.setting_key(&1))})
    )
  end

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
      </div>

      <div class="card">
        <strong>Engines</strong>
        <table>
          <tr>
            <th>Engine</th>
            <th>On this server</th>
          </tr>
          <tr :for={{id, name} <- RisiMe.Agent.TTS.catalogue()}>
            <td>{name}</td>
            <td>{engine_status(@installed, id)}</td>
          </tr>
        </table>
      </div>

      <div class="card">
        <strong>Test</strong>
        <p class="muted">
          Type a sentence; every installed voice speaks it, one after another, with the time to first
          audio. The sentence is not stored.
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
          <button class="btn primary" type="submit" disabled={@test == :running}>
            {if @test == :running, do: "Speaking…", else: "Test"}
          </button>
        </form>
        <.speak_result test={@test} />
      </div>
    </RisiMeWeb.Admin.Layouts.shell>
    """
  end

  attr :test, :any, required: true

  defp speak_result(%{test: list} = assigns) when is_list(list) do
    ~H"""
    <table>
      <tr>
        <th>Voice</th>
        <th>First audio (s)</th>
        <th>Total (s)</th>
        <th>Listen</th>
      </tr>
      <tr :for={r <- @test}>
        <td>{r.voice[:name] || r.voice.id}</td>
        <%= case r.result do %>
          <% {:ok, a} -> %>
            <td>{seconds(a.first_audio_ms)}</td>
            <td>{seconds(a.total_ms)}</td>
            <td><audio controls preload="none" src={a.src}></audio></td>
          <% {:error, e} -> %>
            <td colspan="3" class="bad">{e}</td>
        <% end %>
      </tr>
    </table>
    """
  end

  defp speak_result(%{test: {:error, _}} = assigns) do
    ~H"""
    <p class="bad">The test did not run.</p>
    """
  end

  defp speak_result(assigns), do: ~H""

  defp seconds(ms) when is_integer(ms), do: :erlang.float_to_binary(ms / 1000, decimals: 2)
  defp seconds(_), do: "-"

  defp options(voices, lang, choice) do
    vs = TTS.candidates(voices, lang)

    if choice == "auto" or Enum.any?(vs, &(&1.id == choice)),
      do: vs,
      else: vs ++ [%{id: choice, name: choice <> " (not installed)"}]
  end

  defp engine_status(:loading, _), do: "…"

  defp engine_status(installed, "phone") when is_list(installed),
    do: "On the phone only, not a server voice"

  defp engine_status(installed, id) when is_list(installed) do
    case Enum.find(installed, &(&1.id == id)) do
      nil -> "not installed"
      %{status: :unavailable} -> "installed, not answering"
      %{voices: vs} -> "installed: #{length(vs)} voices"
    end
  end
end
