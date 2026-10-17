defmodule RisiMe.Agent.CallRisi.Decider do
  @moduledoc """
  Call Risi brain v3 (architect P0 2026-10-16): **one model decision per turn**. The model gets
  the last turns of the call, the open state (`RisiMe.Agent.CallRisi.Dialogue`: the open card, what
  Risi waits for, the people of this call), today's date and time zone and the tools, and returns
  one JSON decision:

      %{"tool" => one of tools/0, "action" => "reply" | "ask" | "tool",
        "cancel_open" => bool, "request" => str, "say" => str, "text" => str, "to" => str,
        "tab" => "private" | "official" | ""}

  Only `tool` and `action` are required; the prompt asks the model to leave out every field it
  doesn't use (architect 15:35 item 6, speed: the decision's output tokens are most of its time).

  **Tool coverage** (architect 16:05 item 6): calendar_read, calendar_add, reminder_add,
  my_promises, promises_by, read_messages, message_draft (Private or Official, `tab`), dictate
  (a long message, Risi listens), list_contacts, cancel_open, confirm_open, card_status,
  undo_send, capabilities, and **unsupported** ("I can't do that yet, but here's what I can do")
  instead of forcing the nearest tool.

  **Dictation** (`input.mode == :dictation`, `RisiMe.Agent.CallRisi.Dictation`): while Risi takes
  down a message, the words said since the last pause are classified once (`more` | `end` |
  `instruction` | `noise`), and at the end the whole text is composed from the caller's words
  (`text`, filler and talk to Risi removed, never invented) and, when something looks misheard, a
  corrected version (`text_fixed`). One call per pause, none per fragment.

  The model is a config (`config :risime, :risi_brain, model: "risi-l1"`; the older key
  `:risi_call_brain` is read as an alias), so a stronger
  one drops in later; it runs through `RisiMe.Agent.LLM.complete/1` (the global queue, JSON-schema
  guided decoding, one learning-log row per decision, task `voice_call_decision` /
  `voice_call_dictation`: model, latency, the prompt's SHA-256 and only the decision's
  tool/action, **never** the words). Tests replace the whole decider with
  `config :risime, :risi_call_decider, Module` (same `decide/2`).
  """

  alias RisiMe.Agent.CallRisi.Dialogue

  @callback decide(input :: map, ctx :: map) :: {:ok, map} | {:error, term}

  @tools ~w(none calendar_read calendar_add reminder_add my_promises promises_by read_messages
            replied_since message_draft dictate cancel_open confirm_open card_status undo_send
            capabilities sources list_contacts unsupported)

  # Older names (recorded decisions): the same tool.
  @aliases %{"promises_read" => "my_promises"}

  @schema %{
    "type" => "object",
    "properties" => %{
      "tool" => %{"enum" => @tools},
      "action" => %{"enum" => ~w(reply ask tool)},
      "cancel_open" => %{"type" => "boolean"},
      "confidence" => %{"type" => "number"},
      "request" => %{"type" => "string"},
      "say" => %{"type" => "string"},
      "tab" => %{"enum" => ["private", "official", ""]},
      "text" => %{"type" => "string"},
      "to" => %{"type" => "string"}
    },
    "required" => ~w(tool action confidence to text tab),
    "additionalProperties" => false
  }

  # Item 11: the same schemas as compact-JSON grammars (no whitespace: ~35 tokens a decision, not
  # ~65; the prompt is unchanged).
  @grammar RisiMe.Agent.CompactJson.grammar(@schema)

  @system """
  You decide what Risi does next on a live phone call. Risi is the user's assistant. You get the \
  open state, the call so far and the user's latest words (speech-to-text: words and names may be \
  misheard). Return one JSON decision. "to", "text" and "tab" are always there ("" when not \
  used); leave out the other fields you don't use. "confidence" = how sure you are of this \
  decision, from 0 (a guess) to 1 (certain).

  action:
  - "reply": small talk or a general question. "say" = what Risi says: one or two short spoken \
  sentences, plain words. Never say Risi checked, added, saved, sent or set anything.
  - "ask": one short question back in "say" when the request is unclear. Never draft a message \
  to find out.
  - "tool": Risi uses a tool (set "tool").

  tool:
  - "calendar_read": their schedule, meetings, events or free time. "request" = the question in \
  their words, with the day if they named one.
  - "calendar_add": put an event, meeting or appointment in their calendar ("add …", "make an \
  appointment …", "I need a slot on Friday at 11 for …", "block …"). "request" = the whole \
  request in their words (day, time, title).
  - "reminder_add": set a reminder. "request" as above.
  - "my_promises": what the user promised or must do, their to-dos, reminders or follow-ups.
  - "promises_by": promises other people made to the user ("did Kamal promise anything", "has \
  Kamal followed up on the delivery", "any promises by Ruwan", "what does Nimali owe me"). "to" \
  = that person.
  - "read_messages" (check these first): the user wants to hear messages they got: "read me \
  Kamal's last message", "what did Ruwan say?", "check the messages from Shakya", "the last \
  three days of messages sent by Nimali", "any new messages?", "you should be able to tell me if \
  somebody sent me a message". "to" = the person, or "" for anyone.
  - "replied_since": the user asks whether a person answered THEIR message: "did Nimali reply?", \
  "has she answered?", "did he write back?". "to" = the person. A question about a reply is \
  never a message to send, even when the same words also say "send a message to Nimali": "You \
  sent a message to Nimali. Did she reply to me?" = replied_since, "to" = "Nimali". "tab" = \
  "private" only when the user says private or personal.
  - "message_draft": send a message to a person ("tell Ruwan …", "let Nimali know that …", \
  "message Kamal"). Never for a question about messages the user got ("did she reply?": \
  read_messages). "to" = the person who gets it, as the user said \
  it. The author of the text is not the recipient ("a message from Harsha": "to" = "" unless \
  they name who gets it). "tab" = "official" when they say work, office, official or the work \
  chat; "private" when they say private or personal; else leave it out. Work, official, private, \
  home, the chat and the group are never a person. "text" = the message the user dictated, in \
  their words, without lead-ins such as "just say", "tell her that", "the message is"; "tell her \
  that I called her" may become "I called you". No text given ("message Kamal please"): "text" = \
  "" (Risi asks); the request itself is never the text. Never use action "ask" for a message's \
  recipient or text: tool "message_draft" with them "" (Risi asks). \
  Nobody named ("send it to her"): leave "to" out unless Open task or Open card names the person \
  (that person). Never write a message yourself. "I just said it": "text" = the message they \
  said before in this call.
  - "dictate": the user starts a longer message, one that comes in pieces, or asks Risi to \
  listen until they finish: "the message is going to be…", "so the message is…" followed by \
  words that go on, "let me start the message", "let's draft a bigger (or long) message", "let \
  me redraft it", "listen until I say okay", "take this down". Use dictate, not message_draft, \
  for these, also when message words follow in the same breath ("so, let me start the message: \
  hi Nimali, how are you…": dictate with those words as "text"). "to" as for message_draft; "text" = message words they already said in these \
  words, or "".
  - "cancel_open": cancel the open card or the open task (only when the open state has one).
  - "confirm_open": the user approves the open card or Risi's read-back ("yes", "that's right", \
  "send it", "add it", "go ahead").
  - "card_status": the user asks whether the open card was added or sent, or can't see it.
  - "undo_send": the user wants a message Risi just sent taken back ("undo that", "unsend it").
  - "capabilities": the user asks what Risi can do. Asked whether Risi can do one thing it has \
  a tool for, use that tool now ("can you tell me if somebody sent me a message?": \
  read_messages).
  - "sources": the user asks which account, chats or data Risi reads ("to which account are \
  you reading?", "where are you looking?").
  - "list_contacts": the user asks Risi to tell them names from their contacts ("who is in my \
  contacts?", "tell me ten names").
  - "unsupported": the user asks for something none of these tools does (never for a question \
  about the open card or Risi's last answer) (call someone, book a \
  taxi, send an email, read Private chats, search the web).
  "cancel_open": true only when the user says to cancel ("cancel that", "never mind", "forget \
  it", "start again"). If they also ask for something new in the same words, "tool" is that new \
  request with its fields; tool "cancel_open" only when they ask for nothing else. A correction \
  or a new text for the open card is not a cancel.
  With action "reply" or "ask": "tool" = "none".

  Rules:
  0. A question whether someone replied, answered or wrote to the user is "replied_since"; a \
  request to read or check someone's messages is "read_messages"; never "message_draft", even \
  when the same words also say "send a message to …".
  1. When Risi's last line asked a question, the user's words answer it unless they clearly start \
  something else. When Risi waits for the text of a message, words that are the message are \
  tool "message_draft" with that text and the same "to"; words that are an instruction to Risi \
  ("no", "I'm not asking you to write", "read…", "check…", "what…?", "cancel") are that \
  instruction, never the text.
  When the user rejects the open message ("no, no", "that's wrong") or wants Risi to ask them \
  for the text, and gives no text yet: tool "message_draft" with the same "to" and no text.
  2. Inside a message request, greetings and times ("good morning", "at 5") are message words, \
  never a calendar question; "tell Tharindi about the schedule" is a message to Tharindi (a \
  person named to tell, message or let know), not a calendar question. A greeting alone ("good morning", "hello", "how are you?") is a \
  "reply".
  3. A correction of the open card or task ("no, say … instead", "it's for Nimali, not Kamal", \
  "the message has to go to Nimali, not Kamal": only "to" changes, the text stays; \
  "send it to work", "I don't know. From 9 to 10, discuss racing") is the same tool again with \
  every field filled, the changed ones changed; "request" = the open card's request with the \
  change. A correction is never a cancel. But when the user starts the text over to dictate it \
  ("just modify the message, let me start the message: hi …", "listen until I say okay"), it is \
  "dictate".
  4. Words not meant for Risi → action "reply" with no "say" at all (Risi stays silent, not even \
  "take your time"): thinking aloud ("let me see", \
  "hmm, where was I"), a cut-off fragment that asks nothing, noise.
  5. "Yes", "send it", "that is correct", "add it" about the open card or Risi's read-back → \
  tool "confirm_open" (never a new draft). A bare "yes", "okay" or "yeah" with nothing open and \
  no question from Risi → a short "reply".
  6. When the user starts a new request, the open task is finished: never continue it. A new \
  request never gets the last tool again unless it asks the same thing.
  7. A complaint about Risi's last answer ("you should have access", "why can't you get it") → \
  the same tool as that answer again, never "capabilities".
  8. "her", "him", "she", "he" mean the person in focus (People in this call) when there is one.
  9. Any request to send, tell or message someone is "message_draft" (or "dictate"), also when \
  it names nobody or names someone Risi doesn't know yet.
  """

  @dictation_schema %{
    "type" => "object",
    "properties" => %{
      "tool" => %{"enum" => ~w(more restart end instruction noise)},
      "text" => %{"type" => "string"},
      "text_fixed" => %{"type" => "string"}
    },
    "required" => ~w(tool),
    "additionalProperties" => false
  }

  @dictation_grammar RisiMe.Agent.CompactJson.grammar(@dictation_schema)

  @dictation_system """
  Risi (the assistant; speech-to-text often writes its name as "a receipt", "Rishi", "Reese" or \
  "RISD") is taking down a message that the user dictates on a phone call (speech-to-text: words \
  may be misheard; a long message comes in pieces with pauses). You get the message so far and \
  the user's newest words. Return JSON for the newest words only.

  tool:
  - "more": the next piece of the message; Risi keeps listening.
  - "restart": the user starts the message over ("let me start again", "so, hi Nimali…" with the \
  greeting again after the message began): this piece replaces everything before it.
  - "end": the user is done: an end signal ("okay", "that's it", "done", "that's the message", \
  "send it", "all done", "I told you what to say"), possibly after the last words of the \
  message; or Risi asked what the message should say and these words are a whole short message.
  - "instruction": not message text but words to Risi: a question to Risi ("who is it for?", \
  "did he message me?", "is Kamal in today?"), a correction of who it is for ("no, it's for \
  Ruwan"), "no", "cancel", "stop", "I'm not asking you to write anything", another request \
  ("read…", "check my calendar", "remind me…"). Words to Risi are never message text. For \
  example: "Check if Ruwan answered." "Who's this going to?" "No, it's for Kamal instead." "Did \
  he write back?" "Wait, ask me what it should say." are all instructions.
  - "noise": speech-to-text noise: a short odd sentence that has nothing to do with the message \
  or makes no sense, or the user thinking aloud.

  "text" = the message words of the NEWEST words only, in the user's own words and order, \
  without lead-ins ("the message is", "okay so", "let me start the message", "no wait, let me \
  start again"), talk to Risi \
  ("listen until I say okay", "let me finish"), filler ("you know", "um") and the end signal. \
  "" when they have none. Never add, reword or invent anything.
  "text_fixed" = the same words with an obvious speech-recognition error corrected (a misheard \
  name or word that makes no sense here: "a receipt can send" → "Risi can send"); leave it out \
  when nothing looks misheard.
  """

  @doc "The decision's system prompt (its hash is in the learning log)."
  def system, do: @system

  @doc "The dictation check's system prompt."
  def dictation_system, do: @dictation_system

  @doc "The decision's JSON schema."
  def schema, do: @schema

  @doc "The tool names a decision may carry."
  def tools, do: @tools

  @doc "The configured model alias (default risi-l1, the local vLLM route)."
  def model, do: Keyword.get(config(), :model, "risi-l1")

  @doc """
  The brain's config slot (architect 19:15: one word for both products): `:risi_brain`, else the
  older `:risi_call_brain`.
  """
  def config,
    do:
      Application.get_env(:risime, :risi_brain) ||
        Application.get_env(:risime, :risi_call_brain, [])

  @doc "The configured decider (tests: a recorded one)."
  def impl, do: Application.get_env(:risime, :risi_call_decider, __MODULE__)

  @doc """
  The input of one decision: `%{now, tz, dialogue, history, text}` → the user prompt.
  `history`: the call's `%{"role", "content"}` turns (the last 12 are used).
  """
  def user_prompt(%{mode: :dictation} = input) do
    d = input.dialogue

    to =
      case d[:dictation] do
        %{to: to} when is_binary(to) -> to
        _ -> "someone (not said yet)"
      end

    asked? = match?(%{asked?: true}, d[:dictation])

    """
    The message is for: #{to}
    #{if asked?, do: "Risi asked what the message should say.\n", else: ""}Dictated so far: #{quote_parts(input.parts)}
    The user's newest words: "#{input.text}"
    #{if input[:ended?], do: "The user has stopped talking for a while: this is the end of the message unless the newest words are an instruction.\n", else: ""}
    """
  end

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
    People in this call: #{Dialogue.people_line(input.dialogue)}

    Call so far:
    #{Enum.join(lines, "\n")}

    Latest user words: "#{input.text}"
    """
  end

  defp quote_parts([]), do: "(nothing yet)"
  defp quote_parts(parts), do: Enum.map_join(parts, " / ", &"\"#{&1}\"")

  @doc "Runs the configured decider and checks its output; `{:ok, decision}` or `{:error, why}`."
  def run(input, ctx) do
    case impl().decide(input, ctx) do
      {:ok, %{} = d} -> {:ok, escalated(normalize(d, input[:mode]), input, ctx)}
      {:error, _} = e -> e
      _ -> {:error, :bad_output}
    end
  catch
    _, _ -> {:error, :failed}
  end

  # D-001 (off, no model set): a turn decision the local brain is unsure of asks the stronger
  # model once; its answer is used and kept as a training example (labels only).
  defp escalated(%{tool: _} = dec, %{mode: :dictation}, _ctx), do: dec

  defp escalated(%{tool: _} = dec, input, ctx) do
    alias RisiMe.Agent.CallRisi.Escalate

    if Escalate.escalate?(dec, ctx) do
      case decide(input, ctx, Escalate.model()) do
        {:ok, %{} = d} ->
          better = normalize(d)

          Escalate.example(ctx, "escalated", %{
            local_tool: dec.tool,
            local_action: dec.action,
            final_tool: better.tool,
            final_action: better.action,
            confidence: dec.confidence
          })

          better

        _ ->
          dec
      end
    else
      dec
    end
  end

  defp escalated(dec, _input, _ctx), do: dec

  @doc false
  def normalize(d, mode \\ nil)

  def normalize(d, :dictation) do
    s = fn k -> if is_binary(d[k]), do: String.trim(d[k]), else: "" end
    kind = if d["tool"] in ~w(more restart end instruction noise), do: d["tool"], else: "more"
    %{kind: kind, text: s.("text"), fixed: s.("text_fixed")}
  end

  def normalize(d, _mode) do
    s = fn k -> if is_binary(d[k]), do: String.trim(d[k]), else: "" end
    tool = Map.get(@aliases, d["tool"], d["tool"])

    %{
      confidence: if(is_number(d["confidence"]), do: min(max(d["confidence"] / 1, 0.0), 1.0)),
      tool: if(tool in @tools, do: tool, else: "none"),
      action: if(d["action"] in ~w(reply ask tool), do: d["action"], else: "reply"),
      cancel_open: d["cancel_open"] == true,
      request: s.("request"),
      say: s.("say"),
      text: s.("text"),
      to: s.("to"),
      tab: if(d["tab"] in ~w(private official), do: d["tab"], else: "")
    }
  end

  @doc false
  def decide(%{mode: :dictation} = input, ctx) do
    request(
      ctx,
      model(),
      "voice_call_dictation",
      @dictation_system,
      user_prompt(input),
      "call_dictation",
      @dictation_schema,
      400,
      fn o -> Jason.encode!(%{"tool" => o["tool"], "text?" => text?(o["text"])}) end
    )
  end

  def decide(input, ctx), do: decide(input, ctx, model())

  defp decide(input, ctx, model) do
    request(
      ctx,
      model,
      "voice_call_decision",
      @system,
      user_prompt(input),
      "call_decision",
      @schema,
      300,
      # Only what was decided, never the words (the text, the request, what Risi says).
      fn o ->
        Jason.encode!(%{
          "tool" => o["tool"],
          "action" => o["action"],
          "cancel_open" => o["cancel_open"],
          "confidence" => o["confidence"],
          "text?" => text?(o["text"])
        })
      end
    )
  end

  defp text?(t), do: is_binary(t) and t != ""

  defp grammar_for("call_dictation"), do: if(compact?(), do: @dictation_grammar)
  defp grammar_for(_name), do: if(compact?(), do: @grammar)

  # Config `compact_json: false` goes back to the JSON schema (pretty-printed output).
  defp compact?, do: Keyword.get(config(), :compact_json, true)

  defp request(ctx, model, task, system, user, name, schema, max, log) do
    req = %{
      task: task,
      conversation_id: ctx.conv,
      chat_id: ctx.conv,
      system: system,
      user: user,
      schema_name: name,
      schema: schema,
      source_message_ids: [],
      model_alias: model,
      max_tokens: max,
      # D-001: the call brain stays on RisiMe's own model (no automatic fallback); the only way
      # to another model is the escalation path above, off and with none configured.
      local_only: model == model(),
      grammar: if(model == model(), do: grammar_for(name)),
      # One decision, the same for the same call: greedy (the golden calls replay it).
      temperature: 0.0,
      timeout_ms: Keyword.get(config(), :timeout_ms, 8_000),
      retries: 0,
      invalid_output: :return,
      log_output: log,
      meta: %{"call_id" => ctx.call_id}
    }

    case RisiMe.Agent.LLM.complete(req) do
      {:ok, %{output: out}} -> {:ok, out}
      {:error, {:invalid_output, %{} = out, _}} -> {:ok, out}
      {:error, e} -> {:error, e}
    end
  end
end
