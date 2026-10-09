defmodule RisiMe.Agent.Tools do
  @moduledoc """
  Risi's tool registry and `authorize/3` (contract v1.25 §25.5, decision 068).

  A tool is a map:

    * `name`, `description` (for the system prompt);
    * `where`: `:server` | `:client` (a client tool runs on the asking phone, §25.3);
    * `personal`: true for tools over the asker's own data (calendar, notes, RisiWork,
      cross-conversation search): offered only to an asker with an active Risi chat, and their
      output goes to that Risi chat (§25.1 audience rule);
    * `write`: true for a write proposal (`set_reminder`, `calendar_add`; at most 2 a turn): its
      `run` returns `{:propose, card}` (`RisiMe.Agent.Writes.propose/3`), and its `exec`
      runs the write once confirmed;
    * `finds`: true for a tool that can look something up (the §25.1 post-check);
    * `args`: the JSON schema of its `args` (the server's, §25.5);
    * `run`: `(args, ctx) -> {:ok, result, meta} | {:error, status}` where `meta` may carry
      `refs: %{"c1" => Source}` (refs handed to the model, §25.4) and `personal: true`; a status
      is a §25.4 `StepStatus`; `{:error, "failed", reason}` tells the model why (an ambiguous
      time: the `final` asks, §25.5);
    * `skill`: the §26.1 skill it runs under (nil for the read-only tools that need none).

  The registry is `config :risime, :risi_tool_registry` (a module with `tools/0`), default
  `RisiMe.Agent.Tools.Registry` (tests use stub registries).

  **Permission by construction:** each step's `risi_next_action` schema lists only the tools
  `allowed/1` returns for this asker, device and conversation, and `authorize/3` checks a tool
  again when it runs (a refused step has status `denied`).
  """

  alias RisiMe.Agent.Capabilities

  @type ctx :: %{
          required(:asker) => String.t(),
          required(:device_id) => String.t() | nil,
          required(:conv) => String.t(),
          optional(atom) => term
        }

  @doc "The registered tools."
  def registry, do: Application.get_env(:risime, :risi_tool_registry, __MODULE__.Registry)

  def tools, do: registry().tools()

  @doc "A registered tool by name, or nil."
  def get(name), do: Enum.find(tools(), &(&1.name == name))

  @doc """
  A tool this server knows by name (registered or not, e.g. a write whose card outlived a
  registry change), or nil.
  """
  def known(name), do: Enum.find(__MODULE__.Registry.tools(), &(&1.name == name))

  @doc """
  `:ok` or `{:error, :denied}`: may the asker use `tool` here, now? Client tools need the asking
  device to advertise `risi_tools`; personal tools need an active Risi chat of the asker; every
  tool needs the asker to be an active human member of an Official conversation where Risi may
  act (§25.0, §25.5). A tool's own `authorize` (if any) is consulted last.
  """
  def authorize(%{} = tool, ctx, _opts \\ []) do
    cond do
      not member?(ctx) -> {:error, :denied}
      tool.where == :client and not client_device?(ctx) -> {:error, :denied}
      tool.personal and not risi_chat?(ctx) -> {:error, :denied}
      # v1.26 §26.8: the skill gates (state, risi_skills device, reported permission).
      not RisiMe.Agent.Skills.allows?(tool, ctx) -> {:error, :denied}
      f = Map.get(tool, :authorize) -> if f.(ctx) == :ok, do: :ok, else: {:error, :denied}
      true -> :ok
    end
  end

  @doc "The tools `authorize/3` allows for this context (computed once per step)."
  def allowed(ctx), do: Enum.filter(tools(), &(authorize(&1, ctx) == :ok))

  defp member?(ctx) do
    Map.get_lazy(ctx, :member?, fn ->
      RisiMe.Agent.Secretary.active_human?(ctx.conv, ctx.asker) and
        RisiMe.Agent.may_act?(ctx.conv)
    end)
  end

  defp client_device?(%{device_id: nil}), do: false

  defp client_device?(ctx),
    do:
      Map.get_lazy(ctx, :client_device?, fn ->
        RisiMe.Devices.risi_tools_device?(ctx.asker, ctx.device_id)
      end)

  defp risi_chat?(ctx),
    do: Map.get_lazy(ctx, :risi_chat?, fn -> RisiMe.RisiChat.active?(ctx.asker) end)

  ## The per-step schema (§25.1)

  @ref "^[mcnl][0-9]{1,3}$"

  @doc """
  The `risi_next_action` JSON schema for the allowed tools: one alternative per tool
  (`{"tool": <name>, "args": <its schema>}`) plus `final`.
  """
  def schema(allowed, needed \\ []) do
    tool_alts =
      for t <- allowed do
        %{
          "type" => "object",
          "properties" => %{"tool" => %{"enum" => [t.name]}, "args" => t.args},
          "required" => ["tool", "args"],
          "additionalProperties" => false
        }
      end

    final = %{
      "type" => "object",
      "properties" => %{
        "tool" => %{"enum" => ["final"]},
        "answer" => %{"type" => "string", "maxLength" => 2_000},
        "sources" => %{
          "type" => "array",
          "maxItems" => 20,
          "items" => %{"type" => "string", "pattern" => @ref}
        },
        "next_steps" => %{
          "type" => "array",
          "maxItems" => 3,
          "items" => %{"type" => "string", "maxLength" => 120}
        },
        # P0 2026-10-09: the structured slots of an event or reminder the asker asks for
        # (optional; `RisiMe.Agent.ActionDraft`).
        "draft" => RisiMe.Agent.ActionDraft.schema()
      },
      "required" => ["tool", "answer", "sources", "next_steps"],
      "additionalProperties" => false
    }

    # v1.26 §26.5: the pseudo-tool `need_skill`, over the skills this asker lacks.
    need =
      if needed == [],
        do: [],
        else: [
          %{
            "type" => "object",
            "properties" => %{
              "tool" => %{"enum" => ["need_skill"]},
              "args" => %{
                "type" => "object",
                "properties" => %{"skill_id" => %{"enum" => needed}},
                "required" => ["skill_id"],
                "additionalProperties" => false
              }
            },
            "required" => ["tool", "args"],
            "additionalProperties" => false
          }
        ]

    %{"anyOf" => tool_alts ++ need ++ [final]}
  end

  @doc "The tool lines of the system prompt."
  def prompt_lines(allowed),
    do: Enum.map_join(allowed, "\n", &"- #{&1.name}: #{&1.description}")

  defmodule Registry do
    @moduledoc "The default registry (§25.5, §26.6)."

    alias RisiMe.Agent.ClientTools

    def tools,
      do: [
        RisiMe.Agent.Tools.capabilities(),
        RisiMe.Agent.Reminders.tool(),
        ClientTools.calendar_check(),
        ClientTools.calendar_add(),
        ClientTools.set_alarm(),
        ClientTools.schedule_message(),
        ClientTools.cancel_scheduled()
      ]
  end

  @doc "The `capabilities` tool: lists what Risi can do for this asker now and what's coming."
  def capabilities do
    %{
      name: "capabilities",
      description: "list what you can do for this person now and what is coming",
      where: :server,
      personal: false,
      write: false,
      finds: false,
      args: %{"type" => "object", "properties" => %{}, "additionalProperties" => false},
      run: fn _args, ctx ->
        names = Map.get(ctx, :allowed_names, [])
        {:ok, %{"now" => Capabilities.now(names), "coming" => Capabilities.coming(names)}, %{}}
      end
    }
  end
end
