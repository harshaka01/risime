defmodule RisiMe.Agent.CallRisi.VoiceFactsTest do
  @moduledoc """
  P0 2026-10-15 item 4: the spoken calendar answer's facts (`RisiItems.voice_facts/4`) merge the
  phone's busy times with Risi's own things (Risi Calendar events, events Risi added to the phone,
  reminders, promises…), sorted by time, local times; a block Risi itself added is listed once; a
  failed read says what failed. And `Turn.voice_sink/3` hands them to the call before the answer.
  """
  use RisiMe.DataCase, async: false

  import RisiMe.CalendarHelpers

  alias RisiMe.Agent.{RisiItems, Writes}
  alias RisiMe.Agent.CallRisi.Spoken

  @moduletag capture_log: true
  @tz "Asia/Colombo"

  setup do
    calendar_world!()
  end

  defp check(blocks) do
    %{
      status: "ok",
      reason: nil,
      result: %{
        # Thu 15 Oct 00:00 → Sat 17 Oct 00:00 in Colombo.
        "from" => "2026-10-14T18:30:00.000Z",
        "to" => "2026-10-16T18:30:00.000Z",
        "blocks" => blocks,
        "sources" => [
          %{"source" => "risi_calendar", "read_ok" => true, "reason" => nil, "calendars" => []},
          %{"source" => "risi_items", "read_ok" => true, "reason" => nil, "calendars" => []},
          %{
            "source" => "phone_provider",
            "read_ok" => true,
            "reason" => nil,
            "calendars" => [%{"name" => "Work", "account_type" => "com.google", "events" => 3}]
          }
        ]
      }
    }
  end

  defp event!(ctx, title, start, stop) do
    {201, _} =
      as(ctx, :h, :post, "/api/v1/risi/calendar/events", %{
        "client_event_id" => Ecto.UUID.generate(),
        "title" => title,
        "start" => start,
        "end" => stop,
        "all_day" => false,
        "tz" => @tz
      })

    :ok
  end

  defp block(s, e), do: %{"start" => s, "end" => e, "busy" => true, "all_day" => false}

  test "phone busy times + Risi's things, sorted, local, the Risi-added block once", ctx do
    w = %Writes.Write{write_id: Ecto.UUID.generate(), user_id: ctx.h}

    :ok =
      RisiItems.record_phone_event(
        w,
        %{
          "title" => "Dentist",
          "wire" => %{
            "start" => "2026-10-16T04:30:00.000Z",
            "end" => "2026-10-16T05:30:00.000Z",
            "all_day" => false
          }
        },
        ctx.hd,
        %{"event_id" => "4711", "verified" => true, "calendar" => %{"name" => "Work"}}
      )

    event!(ctx, "Interview with Shenika", "2026-10-16T08:30:00.000Z", "2026-10-16T09:30:00.000Z")

    checks = [
      check([
        # Thu 14:00–15:00 local (phone only), and the Dentist block Risi itself added.
        block("2026-10-15T08:30:00.000Z", "2026-10-15T09:30:00.000Z"),
        block("2026-10-16T04:30:00.000Z", "2026-10-16T05:30:00.000Z")
      ])
    ]

    f = RisiItems.voice_facts(checks, ctx.h, @tz, ~U[2026-10-15 07:11:00Z])
    assert %{ok?: true, today: ~D[2026-10-15], from: ~D[2026-10-15], to: ~D[2026-10-17]} = f

    assert [
             {"busy", nil, ~N[2026-10-15 14:00:00]},
             {"phone_event_added", "Dentist", ~N[2026-10-16 10:00:00]},
             {"risi_calendar_event", "Interview with Shenika", ~N[2026-10-16 14:00:00]}
           ] = for(e <- f.events, do: {e.kind, e.title, NaiveDateTime.truncate(e.start, :second)})

    {text, true} = Spoken.render(f)
    assert text =~ "Today: busy from 2 to 3 pm."

    assert text =~
             "Tomorrow: Dentist from 10 to 11 am and Interview with Shenika from 2 to 3 pm on your Risi Calendar."
  end

  test "a failed phone read: what failed, one sentence", ctx do
    checks = [%{status: "timeout", reason: nil, result: nil}]
    f = RisiItems.voice_facts(checks, ctx.h, @tz, ~U[2026-10-15 07:11:00Z])
    assert %{ok?: false, failed: failed} = f
    assert failed =~ "I couldn't read your calendar"
    refute failed =~ "\n"
  end

  describe "Turn.voice_sink/3" do
    test "facts first, then the answer text; the card gets the call_id" do
      Process.put(:risi_voice_sink, {self(), "call-1"})
      card = %{"kind" => "answer", "request_id" => "r1"}

      assert RisiMe.Agent.Turn.voice_sink("Thu 15 Oct:", card, fn -> %{ok?: true} end) ==
               Map.put(card, "call_id", "call-1")

      assert_received {:risi_voice_facts, "r1", %{ok?: true}}
      assert_received {:risi_voice_answer, "r1", "Thu 15 Oct:"}
      Process.delete(:risi_voice_sink)
    end

    test "a facts error never breaks the turn; no call, no facts computed" do
      Process.put(:risi_voice_sink, {self(), "call-1"})
      card = %{"kind" => "answer", "request_id" => "r2"}
      RisiMe.Agent.Turn.voice_sink("x", card, fn -> raise "boom" end)
      refute_received {:risi_voice_facts, _, _}
      assert_received {:risi_voice_answer, "r2", "x"}
      Process.delete(:risi_voice_sink)

      assert RisiMe.Agent.Turn.voice_sink("x", card, fn -> flunk("computed without a call") end) ==
               card
    end
  end
end
