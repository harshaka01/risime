defmodule RisiMe.Agent.CallRisi.PhoneCalendarTest do
  @moduledoc "v1.52 §41.8 meeting titles from the phone: the pure request/reply/redaction helpers."
  use ExUnit.Case, async: true

  alias RisiMe.Agent.CallRisi.PhoneCalendar

  @dir Path.expand("../../../../../contract/v1/examples", __DIR__)

  defp example(name), do: @dir |> Path.join(name) |> File.read!() |> Jason.decode!()

  defp dt(s) do
    {:ok, d, _} = DateTime.from_iso8601(s)
    d
  end

  # The same range as call_risi_cal_request.json.
  defp cmd(id \\ "8f3a6c21-4d7e-4b59-a1c8-3e9f0d2b7a64"),
    do: %{
      "cmd" => "cal_req",
      "id" => id,
      "from" => "2026-10-15T00:00:00+05:30",
      "to" => "2026-10-17T00:00:00+05:30"
    }

  defp ev(fields), do: Map.merge(%{"ev" => "cal", "id" => cmd()["id"]}, fields)

  defp e(start, stop, title, opts \\ []),
    do: %{
      "start" => start,
      "end" => stop,
      "all_day" => Keyword.get(opts, :all_day, false),
      "title" => title,
      "busy" => Keyword.get(opts, :busy, true)
    }

  test "capability only with call_risi" do
    assert PhoneCalendar.capable?(~w(group_calls risi_tools call_risi call_risi_cal))
    refute PhoneCalendar.capable?(~w(group_calls risi_tools call_risi))
    refute PhoneCalendar.capable?(~w(call_risi_cal))
    refute PhoneCalendar.capable?(nil)
    assert PhoneCalendar.capability() == "call_risi_cal"
    assert PhoneCalendar.topic() == "risi.cal"
    assert PhoneCalendar.timeout_ms() == 2_000
  end

  test "request: RFC 3339 with the offset, a fresh id, at most 8 days" do
    # 2026-10-15 00:00 in Colombo (+05:30) is 2026-10-14 18:30 UTC.
    from = dt("2026-10-14T18:30:00.123Z")
    to = DateTime.add(from, 2 * 86_400)
    off = PhoneCalendar.offset_s(from, ~N[2026-10-15 00:00:00.123])
    assert off == 19_800

    assert {:ok, c} =
             PhoneCalendar.request(from, to,
               offset_s: off,
               id: "8f3a6c21-4d7e-4b59-a1c8-3e9f0d2b7a64"
             )

    ex = example("call_risi_cal_request.json")
    assert c == ex |> Map.delete("t") |> Map.put("cmd", "cal_req")

    assert {:ok, %{"id" => id, "from" => "2026-10-14T18:30:00+00:00"}} =
             PhoneCalendar.request(from, to)

    assert {:ok, _} = Ecto.UUID.cast(id)

    assert {:ok, %{"from" => "2026-10-14T14:30:00-04:00"}} =
             PhoneCalendar.request(from, to, offset_s: -4 * 3600)

    assert {:error, :bad_range} = PhoneCalendar.request(to, from)
    assert {:error, :bad_range} = PhoneCalendar.request(from, from)
    assert {:error, :bad_range} = PhoneCalendar.request(from, DateTime.add(from, 8 * 86_400 + 1))
    assert {:ok, _} = PhoneCalendar.request(from, DateTime.add(from, 8 * 86_400))
  end

  test "the example reply: events with titles (a null title stays nil)" do
    reply = example("call_risi_cal_reply.json")
    assert {:ok, [a | _] = events} = PhoneCalendar.match(Map.put(reply, "ev", "cal"), cmd())
    assert a.title == "Call with Shakya" and a.busy and not a.all_day
    assert a.start == dt("2026-10-15T04:30:00Z")
    assert Enum.any?(events, &is_nil(&1.title))
    assert PhoneCalendar.titles_n(events) == 1
  end

  test "error replies: the four reasons, anything else is error" do
    err = example("call_risi_cal_reply_error.json") |> Map.put("ev", "cal")
    assert {:error, "no_permission"} = PhoneCalendar.match(err, cmd())

    for r <- ~w(no_calendars off error),
        do: assert({:error, ^r} = PhoneCalendar.match(ev(%{"ok" => false, "reason" => r}), cmd()))

    assert {:error, "error"} = PhoneCalendar.match(ev(%{"ok" => false, "reason" => "x"}), cmd())
    assert {:error, "error"} = PhoneCalendar.match(ev(%{"ok" => true}), cmd())
    assert {:error, "error"} = PhoneCalendar.match(ev(%{"ok" => "yes", "events" => []}), cmd())
  end

  test "another id or event is ignored" do
    assert :ignore =
             PhoneCalendar.match(ev(%{"id" => "other", "ok" => true, "events" => []}), cmd())

    assert :ignore = PhoneCalendar.match(%{"ev" => "said"}, cmd())
  end

  test "validation: bad events dropped, out of range dropped, sorted, at most 100, titles trimmed" do
    many =
      for i <- 1..150 do
        h = rem(i, 20) + 1
        m = String.pad_leading("#{rem(i, 60)}", 2, "0")

        e(
          "2026-10-15T#{String.pad_leading("#{h}", 2, "0")}:#{m}:00+05:30",
          "2026-10-15T22:00:00+05:30",
          "T#{i}"
        )
      end

    bad = [
      e("not a time", "2026-10-15T10:00:00+05:30", "x"),
      e("2026-10-15T10:00:00+05:30", "2026-10-15T09:00:00+05:30", "backwards"),
      e("2026-10-20T10:00:00+05:30", "2026-10-20T11:00:00+05:30", "outside"),
      "junk"
    ]

    assert {:ok, events} =
             PhoneCalendar.match(ev(%{"ok" => true, "events" => bad ++ many}), cmd())

    assert length(events) == 100
    starts = Enum.map(events, &DateTime.to_unix(&1.start))
    assert starts == Enum.sort(starts)
    refute Enum.any?(events, &(&1.title in ["x", "backwards", "outside"]))

    long = String.duplicate("a", 300)

    assert {:ok, [t1, t2, t3]} =
             PhoneCalendar.match(
               ev(%{
                 "ok" => true,
                 "events" => [
                   e("2026-10-15T10:00:00+05:30", "2026-10-15T11:00:00+05:30", long),
                   e("2026-10-15T11:00:00+05:30", "2026-10-15T12:00:00+05:30", "  \n "),
                   e(
                     "2026-10-15T12:00:00+05:30",
                     "2026-10-15T13:00:00+05:30",
                     "Line\u0000one\ntwo"
                   )
                 ]
               }),
               cmd()
             )

    assert String.length(t1.title) == 200
    assert t2.title == nil
    assert t3.title == "Line one two"
  end

  test "await: the reply for the id within the timeout, else timeout" do
    c = cmd()
    send(self(), {:call_risi_cal, ev(%{"id" => "other", "ok" => true, "events" => []})})
    send(self(), {:call_risi_cal, ev(%{"ok" => false, "reason" => "off"})})
    assert {:error, "off"} = PhoneCalendar.await(c, 50)
    assert {:error, "timeout"} = PhoneCalendar.await(c, 10)
    # The other id stays in the mailbox.
    assert_received {:call_risi_cal, %{"id" => "other"}}
  end

  test "enrich and the model's context: local times from the event's offset" do
    {:ok, events} =
      PhoneCalendar.match(Map.put(example("call_risi_cal_reply.json"), "ev", "cal"), cmd())

    [a | _] = PhoneCalendar.enrich(events)

    assert a.time_text == "10:00–10:30" and a.day_text == "Thu 15 Oct" and
             a.times_only == "10:00–10:30 busy"

    ctx = PhoneCalendar.context_text(events)
    assert ctx =~ "Thu 15 Oct 10:00–10:30, busy: Call with Shakya"
    assert ctx =~ "(no title)"
    assert PhoneCalendar.context_text([]) =~ "no events"
  end

  test "redact: titles become time-only wording; a paraphrase falls back to the times list" do
    {:ok, events} =
      PhoneCalendar.match(Map.put(example("call_risi_cal_reply.json"), "ev", "cal"), cmd())

    assert {"Tomorrow at 10 you have 10:00–10:30 busy.", true} =
             PhoneCalendar.redact("Tomorrow at 10 you have call with shakya.", events)

    {safe, true} = PhoneCalendar.redact("At 10 you're talking to Shakya.", events)
    refute safe =~ ~r/shakya/i
    assert safe =~ "Thu 15 Oct 10:00–10:30 busy"

    # No titles in the events: nothing to hide, titles_spoken stays off.
    no_titles = Enum.map(events, &%{&1 | title: nil})
    assert {"At 10 you're busy.", false} = PhoneCalendar.redact("At 10 you're busy.", no_titles)
  end

  test "transcript_line: time-only text and titles_spoken, as the titles example" do
    {:ok, events} =
      PhoneCalendar.match(Map.put(example("call_risi_cal_reply.json"), "ev", "cal"), cmd())

    line = %{
      "who" => "risi",
      "text" => "Tomorrow you have Call with Shakya at 10.",
      "lang" => "en",
      "at" => "2026-10-14T03:00:09.655Z"
    }

    out = PhoneCalendar.transcript_line(line, events)
    assert out["titles_spoken"] == true
    refute out["text"] =~ "Shakya"

    ex = example("envelope_risi_call_risi_transcript_titles.json")
    titled = Enum.find(ex["risi"]["lines"], & &1["titles_spoken"])

    assert Enum.sort(Map.keys(out)) -- ["request_id", "interrupted"] ==
             Enum.sort(Map.keys(titled)) -- ["request_id", "interrupted"]

    refute ex |> Jason.encode!() =~ "Shakya"

    user = %{"who" => "user", "text" => "Is my call with Shakya tomorrow?"}
    assert PhoneCalendar.transcript_line(user, events) == user
    assert PhoneCalendar.transcript_line(line, []) == line
  end

  test "log fields: counts only, never a title" do
    {:ok, events} =
      PhoneCalendar.match(Map.put(example("call_risi_cal_reply.json"), "ev", "cal"), cmd())

    f = PhoneCalendar.log_fields({:ok, events})
    assert f == %{"cal_ok" => true, "events_n" => length(events), "titles_n" => 1}
    refute Jason.encode!(f) =~ "Shakya"

    assert PhoneCalendar.log_fields({:error, "timeout"}) ==
             %{"cal_ok" => false, "cal_reason" => "timeout", "events_n" => 0, "titles_n" => 0}
  end
end
