defmodule RisiMeWeb.SocialTest do
  @moduledoc "Contract v1.6 §9: invites, redemption, friends, blocks, privacy."
  use RisiMeWeb.ConnCase, async: false

  import RisiMe.Fixtures
  import RisiMe.OIDCHelpers
  import Ecto.Query, only: [from: 2]

  alias RisiMe.{Repo, Social}
  alias RisiMe.Accounts.User
  alias RisiMe.Social.{FriendRequest, Invite}

  setup do
    RisiMe.Auth.clear_cache()
    a = logged_in_user(display_name: "Alice", company: "CodeGen")
    %{a: a}
  end

  defp authed(conn, token), do: put_req_header(conn, "authorization", "Bearer " <> token)

  defp api(conn, token, method, path, body \\ nil),
    do: conn |> authed(token) |> dispatch(@endpoint, method, path, body)

  defp friends(conn, token), do: api(conn, token, :get, "/api/v1/friends") |> json_response(200)

  defp request(conn, token, phone),
    do: api(conn, token, :post, "/api/v1/friends/requests", %{"phone" => phone})

  defp new_phone, do: unique_phone()

  describe "invites" do
    test "create returns the Invite with link, subject and share_text", %{conn: conn, a: a} do
      resp =
        api(conn, a.token, :post, "/api/v1/invites", %{
          "phone" => new_phone(),
          "email" => "Kamal@Example.com",
          "name" => " Kamal Perera "
        })

      assert %{"invite" => i} = json_response(resp, 201)

      assert i["email"] == "kamal@example.com" and i["name"] == "Kamal Perera" and
               i["status"] == "pending"

      assert i["link"] == "https://risicloud.ai/app/risime/"
      assert i["subject"] == "Join me on RisiMe"
      assert i["share_text"] =~ i["link"] and i["share_text"] =~ "kamal@example.com"
      assert String.length(i["share_text"]) <= 300
      {:ok, exp, _} = DateTime.from_iso8601(i["expires_at"])
      assert_in_delta DateTime.diff(exp, DateTime.utc_now(), :day), 30, 1

      assert %{"invites" => [^i]} =
               api(conn, a.token, :get, "/api/v1/invites") |> json_response(200)
    end

    test "validation errors", %{conn: conn, a: a} do
      base = %{"phone" => new_phone(), "email" => "x@example.com", "name" => "X"}

      for {field, value, code} <- [
            {"phone", "0771234567", "invalid_phone"},
            {"email", "nope", "invalid_email"},
            {"name", "", "invalid_name"},
            {"name", String.duplicate("n", 65), "invalid_name"}
          ] do
        assert %{"error" => %{"code" => ^code}} =
                 api(conn, a.token, :post, "/api/v1/invites", Map.put(base, field, value))
                 |> json_response(422)
      end
    end

    test "identical replies for a member's phone or email, and for my own phone", %{
      conn: conn,
      a: a
    } do
      %{user: member, token: mt} = logged_in_user(display_name: "Member")

      shapes =
        for {phone, email} <- [
              {new_phone(), "nobody@example.com"},
              {member.phone, "other@example.com"},
              {new_phone(), member.email},
              {a.user.phone, "me@example.com"}
            ] do
          resp =
            api(conn, a.token, :post, "/api/v1/invites", %{
              "phone" => phone,
              "email" => email,
              "name" => "N"
            })

          assert resp.status == 201
          json_response(resp, 201)["invite"] |> Map.keys() |> Enum.sort()
        end

      assert length(Enum.uniq(shapes)) == 1

      # The member gets a silent friend request; my own phone stores nothing.
      assert [%{"user_id" => uid}] = friends(conn, mt)["incoming"]
      assert uid == a.user.id

      invites =
        api(conn, a.token, :get, "/api/v1/invites") |> json_response(200) |> Map.fetch!("invites")

      assert length(invites) == 3
      assert Enum.all?(invites, &(&1["status"] == "pending"))
    end

    test "limits: 10 per 24 h and 50 pending, counted in Postgres, with Retry-After", %{
      conn: conn,
      a: a
    } do
      for _ <- 1..10 do
        assert api(conn, a.token, :post, "/api/v1/invites", %{
                 "phone" => new_phone(),
                 "email" => "x@example.com",
                 "name" => "X"
               }).status == 201
      end

      resp =
        api(conn, a.token, :post, "/api/v1/invites", %{
          "phone" => new_phone(),
          "email" => "x@example.com",
          "name" => "X"
        })

      assert %{"error" => %{"code" => "rate_limited"}} = json_response(resp, 429)
      assert [_] = get_resp_header(resp, "retry-after")

      # 50 pending (older than a day) also blocks.
      %{user: b, token: bt} = logged_in_user()
      old = DateTime.add(DateTime.utc_now(), -2, :day)

      Repo.insert_all(
        Invite,
        for(
          _ <- 1..50,
          do: %{
            id: Ecto.UUID.generate(),
            inviter_id: b.id,
            phone: new_phone(),
            email: "y@example.com",
            name: "Y",
            status: "pending",
            expires_at: DateTime.add(old, 30, :day),
            inserted_at: old,
            updated_at: old
          }
        )
      )

      assert api(conn, bt, :post, "/api/v1/invites", %{
               "phone" => new_phone(),
               "email" => "y@example.com",
               "name" => "Y"
             }).status == 429
    end

    test "revoke is a no-op unless pending and mine", %{conn: conn, a: a} do
      %{"invite" => %{"id" => id}} =
        api(conn, a.token, :post, "/api/v1/invites", %{
          "phone" => new_phone(),
          "email" => "r@example.com",
          "name" => "R"
        })
        |> json_response(201)

      %{token: other} = logged_in_user()
      assert api(conn, other, :delete, "/api/v1/invites/#{id}").status == 204
      assert Repo.get!(Invite, id).status == "pending"
      assert api(conn, a.token, :delete, "/api/v1/invites/#{id}").status == 204
      assert Repo.get!(Invite, id).status == "revoked"
      assert api(conn, a.token, :delete, "/api/v1/invites/nope").status == 204
    end
  end

  describe "redemption (Keycloak first use)" do
    defp invite!(inviter_token, phone, email, name \\ "Invited Name") do
      api(build_conn(), inviter_token, :post, "/api/v1/invites", %{
        "phone" => phone,
        "email" => email,
        "name" => name
      })
      |> json_response(201)
    end

    test "creates the user from the oldest invite, accepts same-phone invites, befriends inviters",
         %{conn: conn, a: a} do
      %{user: b, token: bt} = logged_in_user(display_name: "Bob")
      phone = new_phone()
      invite!(a.token, phone, "new@example.com")
      invite!(bt, phone, "new@example.com")
      invite!(bt, new_phone(), "new@example.com")

      token = access_token("new@example.com", %{"name" => "Kamal From Token"})
      me = api(conn, token, :get, "/api/v1/me") |> json_response(200)

      assert me["user"]["phone"] == phone
      assert me["user"]["display_name"] == "Kamal From Token"
      assert me["user"]["company"] == ""
      assert me["user"]["vouched_by"] == %{"user_id" => a.user.id, "display_name" => "Alice"}

      friend_ids = friends(conn, token)["friends"] |> Enum.map(& &1["user_id"]) |> Enum.sort()
      assert friend_ids == Enum.sort([a.user.id, b.id])

      statuses =
        Repo.all(
          from i in Invite, where: i.email == "new@example.com", select: {i.phone, i.status}
        )

      assert Enum.sort(for {p, s} <- statuses, do: {p == phone, s}) == [
               {false, "expired"},
               {true, "accepted"},
               {true, "accepted"}
             ]

      # Name falls back to the invite's name when the token has none.
      invite!(a.token, new_phone(), "noname@example.com", "From Invite")

      assert %{"user" => %{"display_name" => "From Invite"}} =
               api(conn, access_token("noname@example.com", %{"name" => nil}), :get, "/api/v1/me")
               |> json_response(200)
    end

    test "409 when the phone already belongs to a user or is allowlisted", %{conn: conn, a: a} do
      %{user: existing} = logged_in_user()
      invite!(a.token, existing.phone, "taker@example.com")

      assert api(conn, access_token("taker@example.com"), :get, "/api/v1/me")
             |> json_response(409)

      entry = allowlist_entry()
      invite!(a.token, entry.phone, "taker2@example.com")

      assert api(conn, access_token("taker2@example.com"), :get, "/api/v1/me")
             |> json_response(409)
    end

    test "allowlisted users accept pending invites and befriend the inviters", %{conn: conn, a: a} do
      entry = allowlist_entry()
      invite!(a.token, entry.phone, entry.email)
      token = access_token(entry.email)
      assert [%{"user_id" => uid}] = friends(conn, token)["friends"]
      assert uid == a.user.id
    end

    test "membership: no allowlist and no invite → 403; disabled → 403 (JWT) and 401 (dev)", %{
      conn: conn,
      a: a
    } do
      assert api(conn, access_token("stranger@example.com"), :get, "/api/v1/me")
             |> json_response(403)

      invite!(a.token, new_phone(), "joiner@example.com")
      token = access_token("joiner@example.com")

      %{"user" => %{"phone" => phone}} =
        api(conn, token, :get, "/api/v1/me") |> json_response(200)

      :ok = RisiMe.Accounts.disable_user(phone)
      RisiMe.Auth.clear_cache()

      assert %{"error" => %{"code" => "not_allowlisted"}} =
               api(conn, token, :get, "/api/v1/me") |> json_response(403)

      :ok = RisiMe.Accounts.disable_user(a.user.phone)
      assert api(conn, a.token, :get, "/api/v1/me").status == 401
    end

    test "vouched_by clears once the phone is SMS-verified", %{conn: conn, a: a} do
      invite!(a.token, new_phone(), "vouch@example.com")
      token = access_token("vouch@example.com")

      %{"user" => %{"id" => id, "phone" => phone, "vouched_by" => %{}}} =
        api(conn, token, :get, "/api/v1/me") |> json_response(200)

      Repo.update_all(from(u in User, where: u.id == ^id), set: [phone_verified_for: phone])

      assert %{"user" => %{"vouched_by" => nil}} =
               api(conn, token, :get, "/api/v1/me") |> json_response(200)
    end
  end

  describe "friend requests" do
    test "always 202, whatever the target; 422 for a bad phone", %{conn: conn, a: a} do
      %{user: friend} = logged_in_user()
      befriend!(a, friend)
      %{user: blocker, token: blt} = logged_in_user()
      api(conn, blt, :post, "/api/v1/blocks", %{"user_id" => a.user.id})

      for phone <- [new_phone(), friend.phone, blocker.phone, a.user.phone] do
        resp = request(conn, a.token, phone)
        assert json_response(resp, 202) == %{"status" => "requested"}
      end

      assert friends(conn, blt)["incoming"] == []
      assert request(conn, a.token, "123") |> json_response(422)
    end

    test "incoming shows the requester; outgoing shows only the phone; accept makes friends both ways",
         %{conn: conn, a: a} do
      %{user: b, token: bt} = logged_in_user(display_name: "Bob", company: "Rise")
      request(conn, a.token, b.phone)

      assert [out] = friends(conn, a.token)["outgoing"]
      assert %{"phone" => phone, "user_id" => nil, "display_name" => nil, "company" => nil} = out
      assert phone == b.phone

      assert [%{"id" => id, "user_id" => uid, "display_name" => "Alice", "company" => "CodeGen"}] =
               friends(conn, bt)["incoming"]

      assert uid == a.user.id

      Phoenix.PubSub.subscribe(RisiMe.PubSub, RisiMe.Messaging.topic(a.user.id))

      assert %{"friend" => %{"user_id" => ^uid}} =
               api(conn, bt, :post, "/api/v1/friends/requests/#{id}/accept") |> json_response(200)

      assert_receive {:signal,
                      %{
                        kind: "friend",
                        data: %{"action" => "request_accepted", "request_id" => ^id}
                      }}

      assert [%{"user_id" => bid}] = friends(conn, a.token)["friends"]
      assert bid == b.id
      assert friends(conn, a.token)["outgoing"] == []
      assert api(conn, bt, :post, "/api/v1/friends/requests/#{id}/accept") |> json_response(404)
    end

    test "request_received signal to the target's channels", %{conn: conn, a: a} do
      %{user: b} = logged_in_user()
      Phoenix.PubSub.subscribe(RisiMe.PubSub, RisiMe.Messaging.topic(b.id))
      request(conn, a.token, b.phone)
      uid = a.user.id

      assert_receive {:signal,
                      %{
                        kind: "friend",
                        data: %{"action" => "request_received", "user" => %{"user_id" => ^uid}}
                      }}
    end

    test "a request to an unregistered phone appears when someone joins with it", %{
      conn: conn,
      a: a
    } do
      phone = new_phone()
      request(conn, a.token, phone)
      %{token: later} = logged_in_user(phone: phone)
      assert [%{"user_id" => uid}] = friends(conn, later)["incoming"]
      assert uid == a.user.id
    end

    test "crossing requests auto-accept", %{conn: conn, a: a} do
      %{user: b, token: bt} = logged_in_user()
      request(conn, a.token, b.phone)
      request(conn, bt, a.user.phone)
      assert [_] = friends(conn, a.token)["friends"]
      assert friends(conn, a.token)["incoming"] == [] and friends(conn, bt)["outgoing"] == []
    end

    test "decline is silent (still outgoing for the requester); repeats are no-ops; cancel; 404s",
         %{conn: conn, a: a} do
      %{user: b, token: bt} = logged_in_user()
      request(conn, a.token, b.phone)
      [%{"id" => id}] = friends(conn, bt)["incoming"]

      assert api(conn, bt, :post, "/api/v1/friends/requests/#{id}/decline").status == 204
      assert friends(conn, bt)["incoming"] == []
      assert [%{"id" => ^id}] = friends(conn, a.token)["outgoing"]

      request(conn, a.token, b.phone)
      assert friends(conn, bt)["incoming"] == []

      assert Repo.aggregate(from(r in FriendRequest, where: r.from_user_id == ^a.user.id), :count) ==
               1

      assert api(conn, bt, :delete, "/api/v1/friends/requests/#{id}") |> json_response(404)
      assert api(conn, a.token, :delete, "/api/v1/friends/requests/#{id}").status == 204
      assert friends(conn, a.token)["outgoing"] == []

      assert api(conn, a.token, :post, "/api/v1/friends/requests/#{Ecto.UUID.generate()}/decline")
             |> json_response(404)
    end

    test "rate limit: 30 per user per 24 h", %{conn: conn, a: a} do
      for _ <- 1..30, do: assert(request(conn, a.token, new_phone()).status == 202)
      resp = request(conn, a.token, new_phone())
      assert json_response(resp, 429)
      assert [_] = get_resp_header(resp, "retry-after")
    end
  end

  describe "unfriend and blocks" do
    test "unfriend is both ways and silent", %{conn: conn, a: a} do
      %{user: b, token: bt} = logged_in_user()
      befriend!(a, b)
      Phoenix.PubSub.subscribe(RisiMe.PubSub, RisiMe.Messaging.topic(b.id))
      assert api(conn, a.token, :delete, "/api/v1/friends/#{b.id}").status == 204
      assert friends(conn, bt)["friends"] == []
      aid = a.user.id
      assert_receive {:drop_watch, ^aid}
      refute_receive {:signal, %{kind: "friend"}}
    end

    test "block ends friendship and requests both ways; blocked requests are dropped; unblock restores nothing",
         %{conn: conn, a: a} do
      %{user: b, token: bt} = logged_in_user(display_name: "Bob")
      befriend!(a, b)
      request(conn, bt, new_phone())

      assert api(conn, a.token, :post, "/api/v1/blocks", %{"user_id" => b.id}).status == 204
      f = friends(conn, a.token)
      assert f["friends"] == []
      assert [%{"user_id" => bid, "display_name" => "Bob"}] = f["blocked"]
      assert bid == b.id

      # b's request to a is dropped; a's request to b too (block in either direction).
      assert request(conn, bt, a.user.phone).status == 202
      assert friends(conn, a.token)["incoming"] == []
      assert request(conn, a.token, b.phone).status == 202
      assert friends(conn, bt)["incoming"] == []

      assert api(conn, a.token, :delete, "/api/v1/blocks/#{b.id}").status == 204

      assert friends(conn, a.token) |> Map.take(["friends", "blocked"]) == %{
               "friends" => [],
               "blocked" => []
             }

      # Unknown ids are a silent no-op.
      assert api(conn, a.token, :post, "/api/v1/blocks", %{"user_id" => Ecto.UUID.generate()}).status ==
               204
    end
  end

  test "Oban expiry marks old invites and requests expired", %{a: a} do
    past = DateTime.add(DateTime.utc_now(), -1, :second)

    {:ok, i} =
      Social.create_invite(a.user, %{
        "phone" => new_phone(),
        "email" => "e@example.com",
        "name" => "E"
      })

    Repo.update_all(from(x in Invite, where: x.id == ^i.id), set: [expires_at: past])
    :ok = Social.request(a.user, new_phone())

    Repo.update_all(from(r in FriendRequest, where: r.from_user_id == ^a.user.id),
      set: [expires_at: past]
    )

    assert {1, 1} = Social.expire()
  end
end
