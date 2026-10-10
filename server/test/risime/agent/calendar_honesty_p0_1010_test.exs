defmodule RisiMe.Agent.CalendarHonestyP01010Test do
  @moduledoc """
  P0 2026-10-10 (Harsha, nightly.47): Risi said it could not read Google Calendar although Google
  calendars synced to the phone are readable through the phone's calendar provider. The separate
  Google API link (`google_api`, RISI_GCAL) is optional and its absence is never named.
  """
  use RisiMe.DataCase, async: true

  alias RisiMe.Agent.{Capabilities, CalendarHonesty, CalendarTools, ClientTools, Prompts, Turn}

  @tz "Asia/Colombo"
  @window %{"from" => "2026-10-12T04:30:00.000Z", "to" => "2026-10-12T05:30:00.000Z"}
  @work %{"name" => "Work", "account_type" => "com.google", "events" => 0}
  @risi %{
    "source" => "risi_calendar",
    "calendars" => [%{"name" => "Risi Calendar", "account_type" => "risime", "events" => 0}],
    "read_ok" => true,
    "reason" => nil
  }
  @g_none %{
    "source" => "google_api",
    "calendars" => [],
    "read_ok" => false,
    "reason" => "not_connected"
  }
  @clear "Your calendar is clear on Monday at 10 am."

  defp phone(cals, ok, reason \\ nil),
    do: %{"source" => "phone_provider", "calendars" => cals, "read_ok" => ok, "reason" => reason}

  defp ok(blocks, sources),
    do:
      CalendarHonesty.check(
        "ok",
        Map.merge(@window, %{"blocks" => blocks, "sources" => sources}),
        nil
      )

  @forbidden ~r/(?:cannot|can't|can not|unable to|couldn't|not able to)\s+(?:read|see|access|write|use)[^.\n]{0,40}google/i

  test "no prompt or tool text says Risi cannot read or write Google Calendar" do
    tools = [
      ClientTools.calendar_check(),
      ClientTools.calendar_add(),
      CalendarTools.risi_calendar_check(),
      CalendarTools.risi_calendar_add()
    ]

    names = Enum.map(tools, & &1.name)

    texts = [
      Capabilities.prompt([]),
      Capabilities.prompt(names),
      Capabilities.sentence(names),
      Capabilities.retry_instruction(),
      Capabilities.calendar_rules(),
      Prompts.answer_system(),
      Prompts.answer_system(tools),
      Turn.system(tools),
      Turn.system([]),
      RisiMe.Agent.Tools.prompt_lines(tools)
    ]

    for t <- texts do
      refute Regex.match?(@forbidden, t), "forbidden claim in: #{String.slice(t, 0, 200)}"
      refute t =~ "can't see your calendar"
    end

    # P0 2026-10-10: no prompt tells the model to claim a write.
    for t <- texts do
      refute Regex.match?(~r/I(?:'ve| have) (?:added|set|scheduled|created)/i, t)
    end

    assert Capabilities.calendar_rules() =~ "never state that an event"
    assert Capabilities.prompt([]) =~ "Google accounts synced to the phone"
    assert ClientTools.calendar_check().description =~ "Google accounts synced to the phone"
    assert ClientTools.calendar_add().description =~ "Google accounts synced to the phone"
  end

  test "server-built answers never match the forbidden claim either" do
    for r <- ~w(no_permission sync_off no_calendars query_failed not_connected api_error) do
      refute Regex.match?(@forbidden, CalendarHonesty.cant_read(CalendarHonesty.reason_text(r)))
    end

    refute Regex.match?(@forbidden, CalendarHonesty.cant_check())
  end

  describe "an unconnected google_api is never named (RISI_GCAL off or no link)" do
    test "Checked line with Risi Calendar and phone" do
      out = CalendarHonesty.enforce(@clear, [ok([], [@risi, phone([@work], true), @g_none])], @tz)
      assert out == @clear <> "\n\nChecked: Risi Calendar · Phone calendar (Work)."
      refute out =~ "Google"
    end

    test "nothing read: one line per real source, no Google line" do
      out =
        CalendarHonesty.enforce(
          @clear,
          [
            ok([], [
              @risi |> Map.put("read_ok", false) |> Map.put("reason", "unavailable"),
              @g_none
            ])
          ],
          @tz
        )

      assert out =~ "Risi Calendar: it couldn't be opened."
      refute out =~ "Google"
    end

    test "phone-only check (no Risi Calendar): the phone's own reason, never 'not connected'" do
      out =
        CalendarHonesty.enforce(
          @clear,
          [ok([], [phone([], false, "no_permission"), @g_none])],
          @tz
        )

      assert out == CalendarHonesty.cant_read("calendar permission is off")
      assert out =~ "calendar permission is off"
      refute out =~ "Google"

      # Only an unconnected google_api came back: no Google words either.
      out = CalendarHonesty.enforce(@clear, [ok([], [@g_none])], @tz)
      refute out =~ "Google"
      refute out =~ "not connected"
    end

    test "answer.sources leaves it out" do
      srcs = CalendarHonesty.answer_sources([ok([], [phone([@work], true), @g_none])])
      assert Enum.map(srcs, & &1["source"]) == ["phone_provider"]
    end

    test "the model-facing result leaves it out" do
      assert ClientTools.never_connected_google?(@g_none)
      refute ClientTools.never_connected_google?(Map.put(@g_none, "count", 2))

      refute ClientTools.never_connected_google?(%{
               "source" => "google_api",
               "reason" => "reauth_needed"
             })
    end
  end

  describe "RISI_GCAL on with a link: the Google API source is named again" do
    test "a connected link that was not read is named, with the §31.5 reason" do
      g = %{
        "source" => "google_api",
        "calendars" => [],
        "count" => 2,
        "read_ok" => false,
        "reason" => "network"
      }

      out = CalendarHonesty.enforce(@clear, [ok([], [@risi, phone([@work], true), g])], @tz)
      assert out =~ "free then, but I couldn't check Google Calendar (no network on the phone)."
      assert out =~ "Not checked: Google Calendar (no network on the phone)."
    end
  end

  describe "answers come from the tool result only" do
    test "Google calendars synced to the phone are named as such on a read" do
      out = CalendarHonesty.enforce(@clear, [ok([], [phone([@work], true)])], @tz)
      assert out =~ "Phone calendar — Work (Google) 0 events"
    end

    test "empty read names the calendars searched; free stands" do
      cals = [@work, %{"name" => "Home", "account_type" => "com.google", "events" => 0}]
      out = CalendarHonesty.enforce(@clear, [ok([], [phone(cals, true)])], @tz)
      assert out =~ "Work (Google) 0 events, Home (Google) 0 events"
    end

    test "events found: free is rewritten to busy times (phone gives times only, no titles)" do
      busy = [
        %{
          "start" => "2026-10-12T04:30:00.000Z",
          "end" => "2026-10-12T05:30:00.000Z",
          "busy" => true
        }
      ]

      out =
        CalendarHonesty.enforce(
          @clear,
          [ok(busy, [phone([%{@work | "events" => 1}], true)])],
          @tz
        )

      assert out =~ "You're not free then"
      assert out =~ "1 busy time"
    end

    test "no permission, sync off, phone silent: each says exactly why, never free" do
      out = CalendarHonesty.enforce(@clear, [ok([], [phone([], false, "no_permission")])], @tz)
      assert out =~ "calendar permission is off"

      out = CalendarHonesty.enforce(@clear, [ok([], [phone([@work], false, "sync_off")])], @tz)
      assert out =~ "sync is off, so it may be out of date"
      refute out =~ "Your calendar is clear"

      out = CalendarHonesty.enforce(@clear, [CalendarHonesty.check("timeout", nil, nil)], @tz)
      assert out =~ "your phone didn't answer in time"
      refute out =~ "Your calendar is clear"

      out =
        CalendarHonesty.enforce(@clear, [CalendarHonesty.check("no_permission", nil, nil)], @tz)

      assert out =~ "calendar permission is off"
    end

    test "never free without any check" do
      out = CalendarHonesty.enforce("You're free Monday at 10.", [], @tz)
      assert out =~ "I couldn't read your calendar"
    end
  end

  describe "local_events (v1.32 §29.7)" do
    test "only after a successful phone_provider read" do
      ok_read = ok([], [phone([@work], true)])

      assert CalendarHonesty.local_events([ok_read]) == %{
               "from" => @window["from"],
               "to" => @window["to"]
             }

      assert CalendarHonesty.local_events([ok([], [phone([@work], false, "sync_off")])]) == nil
      assert CalendarHonesty.local_events([ok([], [@risi])]) == nil
      assert CalendarHonesty.local_events([CalendarHonesty.check("timeout", nil, nil)]) == nil
      assert CalendarHonesty.local_events([]) == nil
    end

    test "the calendar rules tell Risi the phone lists the events" do
      assert Capabilities.calendar_rules() =~ "the phone lists the events"
      refute Regex.match?(@forbidden, Capabilities.calendar_rules())
    end
  end
end
