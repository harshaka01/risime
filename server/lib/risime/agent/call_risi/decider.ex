defmodule RisiMe.Agent.CallRisi.Decider do
  @moduledoc """
  Call Risi brain v3 (architect P0 2026-10-16): **one model decision per turn**. The model gets
  the last turns of the call, the open state (`RisiMe.Agent.CallRisi.Dialogue`: the open card, what
  Risi waits for), today's date and time zone and the tools, and returns one JSON decision:

      %{"tool" => "none" | "calendar_read" | "calendar_add" | "reminder_add" | "promises_read" |
                  "message_draft" | "list_contacts" | "cancel_open" | "confirm_open" |
                  "card_status" | "capabilities",
        "action" => "reply" | "ask" | "tool",
        "cancel_open" => bool, "request" => str, "say" => str, "text" => str, "to" => str}

  The model is a config (`config :risime, :risi_call_brain, model: "risi-l1"`), so a stronger
  one drops in later; it runs through `RisiMe.Agent.LLM.complete/1` (the global queue, JSON-schema
  guided decoding, one learning-log row per decision, task `voice_call_decision`: model, latency,
  the prompt's SHA-256 and only the decision's tool/action, **never** the words). Tests replace
  the whole decider with `config :risime, :risi_call_decider, Module` (same `decide/2`).
  """

  alias RisiMe.Agent.CallRisi.Dialogue

  @callback decide(input :: map, ctx :: map) :: {:ok, map} | {:error, term}

  @tools ~w(none calendar_read calendar_add reminder_add promises_read message_draft cancel_open
            confirm_open card_status capabilities list_contacts)

  @schema %{
    "type" => "object",
    "properties" => %{
      "tool" => %{"enum" => @tools},
      "action" => %{"enum" => ~w(reply ask tool)},
      "cancel_open" => %{"type" => "boolean"},
      "request" => %{"type" => "string"},
      "say" => %{"type" => "string"},
      "text" => %{"type" => "string"},
      "to" => %{"type" => "string"}
    },
    "required" => ~w(tool action cancel_open request say text to),
    "additionalProperties" => false
  }

  @system """
  You decide what Risi does next on a live phone call. Risi is the user's assistant. You get the \
  open state, the call so far and the user's latest words (speech-to-text: words and names may be \
  misheard). Return one JSON decision.

  action:
  - "reply": small talk or a general question. "say" = what Risi says: one to three short spoken \
  sentences, plain words. Never say Risi checked, added, saved, sent or set anything.
  - "ask": one short question back in "say" when you need something before you can act.
  - "tool": Risi uses a tool (set "tool"; "say" = "").

  tool:
  - "calendar_read": the user asks about their schedule, meetings, events or free time. "request" \
  = their question in their words, with the day if they named one.
  - "calendar_add": put an event, meeting or appointment in their calendar. "request" = the whole \
  request in their words (day, time, title).
  - "reminder_add": set a reminder. "request" as above.
  - "promises_read": their promises, commitments, reminders, to-dos or follow-ups.
  - "message_draft": send a message to a person. "to" = the person who gets it, as the user said \
  it (Risi looks it up in their contacts). The author of the text is not the recipient ("this is \
  a message from Harsha", "given by Harsha": Harsha wrote it; "to" = "" unless they name who gets \
  it). "text" = the message the user dictated, in their words, without lead-ins such as "just \
  say", "tell her that", "the message is", "the content should be"; "tell her that I called her" \
  may become "I called you". If the user did not say what the message should say, "text" = "" \
  (Risi then asks what it should say). If they name nobody ("send it to her"), "to" = "" (Risi \
  then asks who). Never use action "ask" for a message's recipient or text. Never write a message \
  yourself. When the user says they already said it ("I just said it"), "text" = the message they \
  said before in this call.
  - "cancel_open": cancel the open card (only when the open state has one).
  - "confirm_open": the user approves the open card ("yes", "that's right", "send it", "add it").
  - "card_status": the user asks whether the open card was added or sent, or says they can't see it.
  - "capabilities": the user asks what Risi can do.
  - "list_contacts": the user asks for names in their contacts or who they can message.
  "cancel_open": true only when the user says to cancel ("cancel that", "never mind", "forget \
  it", "start again"). If they also ask for something new in the same words (a new message, a \
  new event), "tool" is that new request with its slots; tool "cancel_open" only when they ask \
  for nothing else. A correction or a new text for the open card is not a cancel.
  With action "reply" or "ask": "tool" = "none" and "cancel_open" = false.

  Rules:
  1. When Risi's last line asked a question, the user's words answer it unless they clearly start \
  something else. When Risi waits for the text of a message, the user's words are the message: \
  tool "message_draft" with that text and the same "to". If they also say "cancel that", "start a \
  new message" or "listen first", the message is still the words after "just say", "say", "the \
  message is", "the content should be".
  When the user rejects the open message ("no, no", "that's wrong") or wants Risi to ask them \
  for the text ("ask me what the message should be", "I'll tell you the message"), and gives no \
  text yet: tool "message_draft" with the same "to" and "text" = "".
  2. Inside a message request, greetings and times ("good morning", "at 5") are message words, \
  never a calendar question.
  3. A correction of the open card ("no, say … instead", "it's for Nimali", "from 9 to 10, \
  discuss racing") is the same tool again with every slot filled, the changed ones changed; \
  "request" = the user's words.
  4. Words not meant for Risi → action "reply", "say" = "": thinking aloud ("let me see", "I need \
  to listen to it", "hmm, where was I"), a cut-off fragment that asks nothing, noise.
  5. "Yes", "send it", "that is correct", "add it" about the open card → tool "confirm_open" \
  (never a new draft). A bare "yes", "okay" or "yeah" with no open card and no question from Risi \
  → a short "reply".
  6. When "Open task" is a message, it stays open while the user talks about something else; \
  their next words about that message continue it. When "Open task" is "none", nothing is \
  pending: a bare "yeah" or "okay" repeats no earlier request (rule 5). A new request that has \
  nothing to do with Risi's last answer never gets the last tool again.
  7. A complaint about Risi's last answer ("you should have access", "why can't you get it", \
  "anything else?") → the same tool as that answer again, never "capabilities".
  8. Unused fields: "" (strings), "none", false.
  """

  @doc "The decision's system prompt (its hash is in the learning log)."
  def system, do: @system

  @doc "The decision's JSON schema."
  def schema, do: @schema

  @doc "The tool names a decision may carry."
  def tools, do: @tools

  @doc "The configured model alias (default risi-l1, the local vLLM route)."
  def model, do: Keyword.get(config(), :model, "risi-l1")

  defp config, do: Application.get_env(:risime, :risi_call_brain, [])

  @doc "The configured decider (tests: a recorded one)."
  def impl, do: Application.get_env(:risime, :risi_call_decider, __MODULE__)

  @doc """
  The input of one decision: `%{now, tz, card, awaiting, history, text}` → the user prompt.
  `history`: the call's `%{"role", "content"}` turns (the last 12 are used).
  """
  def user_prompt(%{} = input) do
    lines =
      ["Risi: Hi, it's Risi. How can I help?"] ++
        for %{"role" => r, "content" => c} <- Enum.take(input.history || [], -12),
            is_binary(c) and c != "" do
          if(r == "user", do: "User: ", else: "Risi: ") <> c
        end

    """
    Now: #{input.now} (#{input.tz}).
    Open card: #{Dialogue.card_line(input.dialogue)}
    Open task: #{Dialogue.pending_line(input.dialogue)}
    Risi is waiting for: #{Dialogue.awaiting_line(input.dialogue)}

    Call so far:
    #{Enum.join(lines, "\n")}

    Latest user words: "#{input.text}"
    """
  end

  @doc "Runs the configured decider and checks its output; `{:ok, decision}` or `{:error, why}`."
  def run(input, ctx) do
    case impl().decide(input, ctx) do
      {:ok, %{} = d} -> {:ok, normalize(d)}
      {:error, _} = e -> e
      _ -> {:error, :bad_output}
    end
  catch
    _, _ -> {:error, :failed}
  end

  @doc false
  def normalize(d) do
    s = fn k -> if is_binary(d[k]), do: String.trim(d[k]), else: "" end

    %{
      tool: if(d["tool"] in @tools, do: d["tool"], else: "none"),
      action: if(d["action"] in ~w(reply ask tool), do: d["action"], else: "reply"),
      cancel_open: d["cancel_open"] == true,
      request: s.("request"),
      say: s.("say"),
      text: s.("text"),
      to: s.("to")
    }
  end

  @doc false
  def decide(input, ctx) do
    req = %{
      task: "voice_call_decision",
      conversation_id: ctx.conv,
      chat_id: ctx.conv,
      system: @system,
      user: user_prompt(input),
      schema_name: "call_decision",
      schema: @schema,
      source_message_ids: [],
      model_alias: model(),
      max_tokens: 300,
      # One decision, the same for the same call: greedy (the golden calls replay it).
      temperature: 0.0,
      timeout_ms: Keyword.get(config(), :timeout_ms, 8_000),
      retries: 0,
      invalid_output: :return,
      # Only what was decided, never the words (the text, the request, what Risi says).
      log_output: fn o ->
        Jason.encode!(%{
          "tool" => o["tool"],
          "action" => o["action"],
          "cancel_open" => o["cancel_open"],
          "text?" => is_binary(o["text"]) and o["text"] != ""
        })
      end,
      meta: %{"call_id" => ctx.call_id}
    }

    case RisiMe.Agent.LLM.complete(req) do
      {:ok, %{output: out}} -> {:ok, out}
      {:error, {:invalid_output, %{} = out, _}} -> {:ok, out}
      {:error, e} -> {:error, e}
    end
  end
end
