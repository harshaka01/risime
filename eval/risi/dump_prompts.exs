alias RisiMe.Agent.{Turn, Tools, Prompts}
tools = Tools.Registry.tools()

strip = fn t ->
  %{"name" => t.name, "description" => t.description, "where" => to_string(t.where), "write" => t.write, "args" => t.args}
end

sets = %{
  "group_basic" => ~w(capabilities set_reminder),
  "risi_chat_phone" => ~w(capabilities set_reminder calendar_check calendar_add set_alarm schedule_message cancel_scheduled),
  "risi_chat_calendar" =>
    ~w(capabilities set_reminder risi_calendar_check risi_calendar_add calendar_check calendar_add set_alarm schedule_message cancel_scheduled)
}

turn =
  for {k, names} <- sets, into: %{} do
    allowed = Enum.filter(tools, &(&1.name in names))
    {k, %{"tools" => Enum.map(allowed, & &1.name), "system" => Turn.system(allowed, []), "schema" => Tools.schema(allowed, [])}}
  end

out = %{
  "tools" => Enum.map(tools, strip),
  "turn" => turn,
  "answer_system" => Prompts.answer_system(~w(set_reminder)),
  "answer_schema" => Prompts.answer_schema(),
  "summary_system" => Prompts.summary_system(),
  "summary_schema" => Prompts.summary_schema(),
  "extract_system" => Prompts.extract_system(),
  "extract_schema" => Prompts.extract_schema(),
  "note_system" => Prompts.note_system(),
  "note_schema" => Prompts.note_schema()
}

File.write!(hd(System.argv()), Jason.encode!(out, pretty: true))
IO.puts("ok")
