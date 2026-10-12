defmodule RisiMe.Agents.Router do
  @moduledoc """
  The **Fast router** (decision 084, PART 6 step 2): classifies one Risi ask to a route and the
  agent that owns it, or to the Planner when it needs several steps.

  | Route | Agent | Today's path |
  |---|---|---|
  | `calendar_read` | calendar | `ScheduleIntent` → forced schedule read |
  | `ledger_items` | ledger | `ScheduleIntent` → Risi's items |
  | `memory` | memory | `MemoryIntent` → forced memory tool |
  | `summary_chat` | summary | the loop (§27 summarise this chat) |
  | `calendar_write` / `reminder` | calendar | the loop (write tools) |
  | `message_write` | planner | the loop (`schedule_message`) |
  | `chat` | direct | the loop, answered by `final` with no tool |
  | `planner` | planner | the loop |

  **Rules first** (microseconds): the deterministic routes reuse today's `ScheduleIntent`
  unchanged, then word lists in English, Sinhala, Tamil and Singlish. A rules decision has a
  confidence; below 0.6 (`planner` by default) the **model** may decide, when
  `RISI_ROUTER_MODEL` (flag table > env) names one: a tiny non-thinking prompt, ≤ 40 output
  tokens, schema `risi_route`, through `RisiMe.Agent.LLM` (queue, learning log). The endpoint
  is swappable by config: `RISI_ROUTER_MODEL=risi-l2 RISI_ROUTER_URL=http://127.0.0.1:8102/v1`
  (loopback only, `RisiMe.Agent.LLM.Local.check_url/1`); default URL = risi-l1's.

  Shadow by default (`RISI_AGENT_ROUTER=shadow`): `Agent.Turn` keeps routing as today and
  `RisiMe.Agents.Shadow` logs this router's decision against it.
  """
  @behaviour RisiMe.Agents.Agent

  alias RisiMe.Agent.{Lang, ScheduleIntent}

  @routes ~w(calendar_read calendar_write reminder ledger_items memory summary_chat message_write
             chat planner)
  @agent_of %{
    "calendar_read" => "calendar",
    "calendar_write" => "calendar",
    "reminder" => "calendar",
    "ledger_items" => "ledger",
    "memory" => "memory",
    "summary_chat" => "summary",
    "message_write" => "planner",
    "chat" => "direct",
    "planner" => "planner"
  }
  @model_below 0.6
  @rules_version "router-rules-v1"
  @prompt_version "router-prompt-v1"

  @impl true
  def spec do
    %{
      name: "router",
      type: :specialist,
      goal: "Send each ask to the right agent/path: rules < 50 ms, model < 1 s.",
      tools: [],
      memory: :none,
      eval_set: "router",
      budget: %{p50_ms: 50, p95_ms: 1_000, hard_ms: 1_500},
      learned_version: learned_version(),
      legacy: [RisiMe.Agent.ScheduleIntent, RisiMe.Agent.MemoryIntent],
      status: :shadow_ready
    }
  end

  @doc "The routes."
  def routes, do: @routes

  @doc "The agent that owns a route."
  def agent_of(route), do: Map.get(@agent_of, route, "planner")

  @doc "The learned-state version: the rules, plus the prompt and model when a model is on."
  def learned_version do
    case model() do
      nil -> @rules_version
      m -> "#{@rules_version}+#{@prompt_version}@#{m}"
    end
  end

  @impl true
  def label(%{route: r, by: by}), do: "#{r}/#{by}"
  def label(_), do: nil

  @impl true
  def run(text, ctx) when is_binary(text) do
    today = Map.get(ctx, :today) || Date.utc_today()
    rules = rules(text, today)

    if rules.confidence < @model_below and model() != nil do
      case model_route(text, ctx) do
        {:ok, m} -> {:ok, m}
        {:error, _} -> {:ok, rules}
      end
    else
      {:ok, rules}
    end
  end

  def run(_text, _ctx), do: {:error, :bad_input}

  ## Rules

  @smalltalk [
    ~r/^\s*(?:hi|hello|hey|hiya|yo|good\s+(?:morning|afternoon|evening|night)|thanks?(?:\s+you)?|thank\s+you|thx|ok(?:ay)?|cool|great|nice|bye|good\s*bye|see\s+you)\b[\s!.,]*(?:risi)?[\s!.]*$/iu,
    ~r/^\s*(?:how\s+are\s+you|who\s+are\s+you|what\s+are\s+you|what\s+can\s+you\s+do|what\s+do\s+you\s+do|help)\b.{0,20}$/iu,
    ~r/^\s*(?:ආයුබෝවන්|හලෝ|ස්තූතියි|ස්තුතියි|සුභ\s+උදෑසනක්|කොහොමද)[\s!.?]*$/u,
    ~r/^\s*(?:வணக்கம்|நன்றி|ஹலோ|எப்படி\s+இருக்கிறீர்கள்)[\s!.?]*$/u,
    ~r/^\s*(?:ayubowan|kohomada|sthuthi|isthuthi|vanakkam|nandri)\b[\s!.?]*$/iu
  ]

  @summary_chat [
    ~r/\b(?:summari[sz]e|summary|recap|tl;?dr)\b/iu,
    ~r/සාරාංශ|කෙටියෙන්\s+කියන්න/u,
    ~r/சுருக்க/u,
    ~r/\bsummary\s+ekak\b/iu
  ]

  @reminder [
    ~r/\bremind(?:er|ers)?\b/iu,
    ~r/\b(?:alarm|wake\s+me)\b/iu,
    ~r/මතක්\s+කර|මතක්\s+කරන්න|ඇලාම්/u,
    ~r/நினைவூட்ட|நினைவூட்டு|அலாரம்/u,
    ~r/\bmathak\s+karanna\b/iu
  ]

  @calendar_write [
    ~r/\b(?:add|put|book|create|schedule|set\s+up|arrange|block|move|reschedule|cancel|remove|delete)\b.{0,60}\b(?:meeting|call|appointment|event|calendar|diary|slot|lunch|dinner|catch[- ]?up|session|interview)\b/iu,
    ~r/\b(?:meeting|appointment|event)\b.{0,30}\b(?:add|book|create|schedule|cancel)\b/iu,
    ~r/(?:හමුවීම|රැස්වීම|calendar).{0,30}(?:දාන්න|එකතු\s+කරන්න|හදන්න|අවලංගු)/u,
    ~r/(?:சந்திப்பு|கூட்டம்|நாட்காட்டி).{0,30}(?:சேர்|உருவாக்கு|ரத்து)/u,
    ~r/\b(?:meeting|calendar)\b.{0,30}\b(?:danna|daanna|add\s+karanna|hadanna)\b/iu
  ]

  @message_write [
    ~r/\b(?:send|text|message|tell|inform|ask|let)\b.{0,30}\b(?:him|her|them|team|everyone|group|know|that|saying|to)\b/iu,
    ~r/\bschedule\s+(?:a\s+)?message\b/iu,
    # "send Kumu good morning", "text Kamal …": a write verb, then a name.
    ~r/\b(?:[Ss]end|[Tt]ext|[Mm]essage|[Tt]ell)\s+\p{Lu}\p{Ll}+/u,
    ~r/යවන්න|කියන්න\s+.{0,20}ට/u,
    ~r/அனுப்பு|அனுப்ப|சொல்லுங்கள்/u,
    ~r/\b(?:yawanna|yawapan|kiyanna)\b/iu
  ]

  @here ~r/\b(?:this\s+(?:chat|group|conversation|thread)|here|in\s+here)\b|මේ\s+(?:chat|කතාබහ|සංවාදය)|இந்த\s+(?:உரையாடல|chat|குழு)/iu

  @multi ~r/\b(?:and\s+then|then\s+(?:add|send|remind|tell)|after\s+that|also\s+(?:add|send|remind|tell)|and\s+(?:add|send|remind|tell|book))\b/iu

  @doc """
  The rules decision: `%{route, agent, confidence, by: "rules"}`. Deterministic, no I/O.
  """
  def rules(text, %Date{} = today) when is_binary(text) do
    t = String.trim(text)

    {route, conf} =
      cond do
        # "recap today's discussion here": this chat now (finer than today's memory route).
        any?(@summary_chat, t) and Regex.match?(@here, t) -> {"summary_chat", 0.9}
        true -> legacy_or_words(t, today)
      end

    %{route: route, agent: agent_of(route), confidence: conf, by: "rules"}
  end

  defp legacy_or_words(t, today) do
    case ScheduleIntent.classify(t, today) do
      {:schedule_read, _} -> {"calendar_read", 0.95}
      :risi_items -> {"ledger_items", 0.95}
      {:memory, _} -> {"memory", 0.9}
      _ -> words(t)
    end
  end

  defp words(""), do: {"chat", 0.5}

  defp words(t) do
    cond do
      Regex.match?(@multi, t) -> {"planner", 0.8}
      any?(@smalltalk, t) -> {"chat", 0.9}
      any?(@reminder, t) -> {"reminder", 0.85}
      any?(@calendar_write, t) -> {"calendar_write", 0.85}
      any?(@summary_chat, t) -> {"summary_chat", 0.8}
      any?(@message_write, t) -> {"message_write", 0.7}
      true -> {"planner", 0.4}
    end
  end

  defp any?(rs, t), do: Enum.any?(rs, &Regex.match?(&1, t))

  ## Model (optional; risi-l1 now, risi-l2 later by config)

  @doc "The model the router may ask (`RISI_ROUTER_MODEL`), or nil (rules only, the default)."
  def model do
    case RisiMe.Agents.Flags.raw("RISI_ROUTER_MODEL") do
      v when v in [nil, "", "off", "none", "rules"] -> nil
      v -> v
    end
  end

  @doc "The router model's base URL (`RISI_ROUTER_URL`), or nil for risi-l1's configured URL."
  def model_url do
    case System.get_env("RISI_ROUTER_URL") do
      v when v in [nil, ""] -> Application.get_env(:risime, :risi_router, [])[:url]
      v -> v
    end
  end

  @schema %{
    "type" => "object",
    "properties" => %{
      "route" => %{"enum" => @routes},
      "confidence" => %{"type" => "number", "minimum" => 0, "maximum" => 1}
    },
    "required" => ["route", "confidence"],
    "additionalProperties" => false
  }

  @doc false
  def schema, do: @schema

  @doc false
  def prompt do
    """
    Route one request to a RisiMe assistant. Answer only JSON {"route","confidence"}.
    Routes: calendar_read (asks what is on the calendar / free or busy), calendar_write (add, move or cancel an event), reminder (remind me / alarm), ledger_items (what Risi set up: my reminders, Risi's items), memory (what was said, agreed, decided or is pending with someone, or a summary of a past period), summary_chat (summarise this chat now), message_write (send or schedule a message to people), chat (greeting, thanks, small talk, what can you do), planner (several steps, or anything else).
    The request may be English, Sinhala, Tamil or Singlish.
    """
  end

  defp model_route(text, ctx) do
    req =
      %{
        task: "route",
        conversation_id: Map.get(ctx, :conv) || "",
        chat_id: Map.get(ctx, :chat_id) || "",
        system: prompt(),
        user:
          "Request (#{Map.get(ctx, :lang) || Lang.detect(text)}): " <> String.slice(text, 0, 600),
        schema_name: "risi_route",
        schema: @schema,
        source_message_ids: [],
        max_tokens: 40,
        timeout_ms: 1_200,
        retries: 0,
        model_alias: model()
      }
      |> then(fn r -> if u = model_url(), do: Map.put(r, :base_url, u), else: r end)

    case RisiMe.Agent.LLM.complete(req) do
      {:ok, %{output: %{"route" => route} = out}} when route in @routes ->
        {:ok,
         %{
           route: route,
           agent: agent_of(route),
           confidence: out["confidence"] / 1,
           by: "model"
         }}

      _ ->
        {:error, :model}
    end
  end
end
