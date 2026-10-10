defmodule RisiMe.CalendarHelpers do
  @moduledoc """
  v1.29 §29 Risi Calendar test helpers: the switch, `risi_events` devices, a fixed clock, two
  calendar users (Harsha and Shenika) sharing an Official chat, each with an active Risi chat.
  """
  import RisiMe.RisiHelpers

  alias RisiMe.GroupHelpers

  @caps ~w(groups member_devices tabs risi_tools risi_skills risi_ledger risi_events)

  # Fri 9 Oct 2026, 10:30 in Colombo (+05:30).
  @now ~U[2026-10-09 05:00:00Z]

  def now, do: @now

  @doc "`RISI_EVENTS=on` for the test."
  def events_on! do
    restore_on_exit([:risi_events, :risi_send_limit])
    Application.put_env(:risime, :risi_events, true)
    # Many cards per test: Risi's own send limit must not interfere.
    Application.put_env(:risime, :risi_send_limit, 10_000)
    :ok
  end

  @doc "A `risi_events` device (with risi_tools, risi_skills, risi_ledger). Returns its id."
  def calendar_device!(user), do: RisiMe.TabsHelpers.tabs_device!(user, caps: @caps)

  @doc "v1.31: `RISI_GCAL=on` for the test (also needs `events_on!/0`)."
  def gcal_on! do
    restore_on_exit([:risi_gcal])
    Application.put_env(:risime, :risi_gcal, true)
    :ok
  end

  @doc "v1.31: a `risi_events` device that also advertises `google_calendar`. Returns its id."
  def gcal_device!(user, opts \\ []),
    do:
      RisiMe.TabsHelpers.tabs_device!(
        user,
        Keyword.put(opts, :caps, @caps ++ ["risi_notes", "google_calendar"])
      )

  @doc "The world: Harsha and Shenika, calendar users, an Official chat, their Risi chats."
  def calendar_world! do
    restore_on_exit([:risi_now, :risi_ledger])
    Application.put_env(:risime, :risi_now, @now)
    Application.put_env(:risime, :risi_ledger, true)
    RisiMe.MLSHelpers.with_attestation_key(%{})
    harsha = RisiMe.Fixtures.logged_in_user(display_name: "Harsha")
    shenika = RisiMe.Fixtures.logged_in_user(display_name: "Shenika")
    og = risi_chat!([harsha.user, shenika.user])
    RisiMe.TabsHelpers.risi_tools_on!()
    skills_on!()
    events_on!()
    hd = calendar_device!(harsha.user)
    sd = calendar_device!(shenika.user)
    hrc = own_risi_chat!(harsha.user)
    src = own_risi_chat!(shenika.user)

    %{
      harsha: harsha,
      shenika: shenika,
      h: harsha.user.id,
      s: shenika.user.id,
      og: og,
      hd: hd,
      sd: sd,
      hrc: hrc,
      src: src
    }
  end

  @doc "An API call as Harsha (`:h`) or Shenika (`:s`) from their calendar device."
  def as(ctx, who, method, path, body \\ nil) do
    {token, dev} =
      case who do
        :h -> {ctx.harsha.token, ctx.hd}
        :s -> {ctx.shenika.token, ctx.sd}
      end

    GroupHelpers.api(method, path, token, body, dev)
  end

  @doc "Every Risi post received so far: `[{conv, body, risi}]`."
  def posts(acc \\ []) do
    receive do
      {:risi_post, conv, body, risi} -> posts([{conv, body, risi} | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  def of_kind(ps, kind), do: for({_, _, %{"kind" => ^kind} = r} <- ps, do: r)

  # 14:00–15:00 Colombo on Mon 12 Oct.
  def interview(extra \\ %{}) do
    Map.merge(
      %{
        "client_event_id" => Ecto.UUID.generate(),
        "title" => "Interview",
        "start" => "2026-10-12T08:30:00.000Z",
        "end" => "2026-10-12T09:30:00.000Z",
        "all_day" => false,
        "tz" => "Asia/Colombo"
      },
      extra
    )
  end
end
