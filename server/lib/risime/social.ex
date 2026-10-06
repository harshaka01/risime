defmodule RisiMe.Social do
  @moduledoc """
  Invites, friends and blocks (contract v1.6 §9, decision 030).

  * **Friendships** are undirected rows `(user_a, user_b)` with `user_a < user_b`.
  * **Friend requests** are stored against the phone that was entered, so a request to an
    unregistered number appears to whoever later joins with that phone. Every mutation between
    two parties runs in a transaction holding `pg_advisory_xact_lock` on the sorted phone pair,
    so crossing requests, accepts and blocks can't race.
  * **Blocks** are directional rows; a block in either direction ends friendship, chat, typing
    and presence between the two.
  * **Privacy:** replies never depend on whether a phone or email belongs to a member.
  """
  import Ecto.Query

  alias RisiMe.Accounts
  alias RisiMe.Accounts.{AllowlistEntry, User, Validate}
  alias RisiMe.{Messaging, RateLimiter, Repo}
  alias RisiMe.Social.{FriendRequest, Invite}

  @request_ttl_days 30
  @invite_ttl_days 30
  @requests_per_day 30
  @invites_per_day 10
  @invites_pending 50

  ## Friendships

  defp pair(a, b) when a < b, do: {a, b}
  defp pair(a, b), do: {b, a}

  @doc "True if `a` and `b` (user ids) are friends."
  def friends?(a, b) when is_binary(a) and is_binary(b) and a != b do
    {x, y} = pair(a, b)

    Repo.exists?(
      from f in "friendships",
        where: f.user_a == type(^x, :binary_id) and f.user_b == type(^y, :binary_id)
    )
  end

  def friends?(_, _), do: false

  @doc "Ids of the user's friends."
  def friend_ids(user_id) do
    Repo.all(
      from f in "friendships",
        where: f.user_a == type(^user_id, :binary_id) or f.user_b == type(^user_id, :binary_id),
        select:
          fragment(
            "CASE WHEN ? = ? THEN ? ELSE ? END",
            f.user_a,
            type(^user_id, :binary_id),
            f.user_b,
            f.user_a
          )
    )
    |> Enum.map(&Ecto.UUID.cast!/1)
  end

  @doc "Makes two users friends (idempotent). Also used by fixtures and the migration."
  def make_friends!(a, b) when a != b do
    {x, y} = pair(a, b)

    Repo.insert_all(
      "friendships",
      [%{user_a: dump(x), user_b: dump(y), inserted_at: DateTime.utc_now()}],
      on_conflict: :nothing
    )

    :ok
  end

  defp dump(id), do: Ecto.UUID.dump!(id)

  defp unfriend_rows(a, b) do
    {x, y} = pair(a, b)

    Repo.delete_all(
      from f in "friendships",
        where: f.user_a == type(^x, :binary_id) and f.user_b == type(^y, :binary_id)
    )
  end

  @doc "True if either user blocked the other."
  def blocked_between?(a, b) do
    Repo.exists?(
      from bl in "blocks",
        where:
          (bl.blocker_id == type(^a, :binary_id) and bl.blocked_id == type(^b, :binary_id)) or
            (bl.blocker_id == type(^b, :binary_id) and bl.blocked_id == type(^a, :binary_id))
    )
  end

  # Serialises every change between two parties (identified by phone, since one may not exist).
  defp lock_pair(phone_a, phone_b) do
    key = "risime_pair:" <> Enum.join(Enum.sort([phone_a, phone_b]), "|")
    Repo.query!("SELECT pg_advisory_xact_lock(hashtext($1))", [key])
    :ok
  end

  ## Requests

  @doc """
  `POST /friends/requests`. Always `:ok` for a valid phone, whatever happens; see §9.2.
  """
  @spec request(%User{}, term) :: :ok | {:error, :invalid_phone | {:rate_limited, pos_integer}}
  def request(%User{} = from, phone) do
    cond do
      not Validate.phone?(phone) ->
        {:error, :invalid_phone}

      RateLimiter.hit(:friend_request, from.id, @requests_per_day, :timer.hours(24)) != :ok ->
        {:error, {:rate_limited, RateLimiter.retry_after_s(:timer.hours(24))}}

      true ->
        do_request(from, phone)
        :ok
    end
  end

  # Shared by requests and invites to members. Same queries on every path.
  defp do_request(from, phone) do
    {:ok, signals} =
      Repo.transaction(fn ->
        lock_pair(from.phone, phone)
        target = Repo.get_by(User, phone: phone)
        crossing = target && pending_request(target.id, from.phone)

        cond do
          phone == from.phone ->
            []

          target && (blocked_between?(from.id, target.id) or friends?(from.id, target.id)) ->
            []

          crossing ->
            accept_locked(crossing, target, from)

          true ->
            case insert_request(from.id, phone) do
              {:ok, req} when not is_nil(target) ->
                [{target.id, signal("request_received", req.id, from)}]

              _ ->
                []
            end
        end
      end)

    for {user_id, sig} <- signals, do: Messaging.signal(user_id, sig)
    :ok
  end

  # A pending or declined request for the same pair is a silent no-op (partial unique index).
  defp insert_request(from_id, phone) do
    now = DateTime.utc_now()

    {count, rows} =
      Repo.insert_all(
        FriendRequest,
        [
          %{
            id: Ecto.UUID.generate(),
            from_user_id: from_id,
            to_phone: phone,
            status: "pending",
            expires_at: DateTime.add(now, @request_ttl_days, :day),
            inserted_at: now,
            updated_at: now
          }
        ],
        on_conflict: :nothing,
        conflict_target:
          {:unsafe_fragment,
           ~s|("from_user_id", "to_phone") WHERE status IN ('pending', 'declined')|},
        returning: [:id]
      )

    if count == 1, do: {:ok, hd(rows)}, else: :exists
  end

  defp pending_request(from_id, to_phone) do
    now = DateTime.utc_now()

    Repo.one(
      from r in FriendRequest,
        where:
          r.from_user_id == ^from_id and r.to_phone == ^to_phone and r.status == "pending" and
            r.expires_at > ^now,
        limit: 1
    )
  end

  # Accepts `req` (from `requester` to `accepter`), inside a locked transaction. Returns the
  # signals to send after commit.
  defp accept_locked(req, requester, accepter) do
    make_friends!(requester.id, accepter.id)

    Repo.update_all(
      from(r in FriendRequest,
        where:
          r.status in ["pending", "declined"] and
            ((r.from_user_id == ^requester.id and r.to_phone == ^accepter.phone) or
               (r.from_user_id == ^accepter.id and r.to_phone == ^requester.phone))
      ),
      set: [status: "accepted", updated_at: DateTime.utc_now()]
    )

    [
      {requester.id, signal("request_accepted", req.id, accepter)},
      {accepter.id, signal("request_accepted", req.id, requester)}
    ]
  end

  defp signal(action, request_id, %User{} = other) do
    %{
      kind: "friend",
      data: %{
        "action" => action,
        "request_id" => request_id,
        "user" => %{
          "user_id" => other.id,
          "phone" => other.phone,
          "display_name" => other.display_name,
          "company" => other.company
        }
      }
    }
  end

  @doc "`POST /friends/requests/{id}/accept`: `{:ok, friend}` or `{:error, :not_found}`."
  def accept(%User{} = me, id) do
    with {:ok, id} <- Ecto.UUID.cast(id),
         %FriendRequest{} = req <- incoming_request(me, id),
         %User{} = requester <- Repo.get(User, req.from_user_id) do
      result =
        Repo.transaction(fn ->
          lock_pair(me.phone, requester.phone)

          if incoming_request(me, id) && not blocked_between?(me.id, requester.id),
            do: accept_locked(req, requester, me),
            else: Repo.rollback(:not_found)
        end)

      case result do
        {:ok, signals} ->
          for {user_id, sig} <- signals, user_id != me.id, do: Messaging.signal(user_id, sig)
          {:ok, friend_json(requester, DateTime.utc_now())}

        {:error, :not_found} ->
          {:error, :not_found}
      end
    else
      _ -> {:error, :not_found}
    end
  end

  defp incoming_request(me, id) do
    now = DateTime.utc_now()

    Repo.one(
      from r in FriendRequest,
        where:
          r.id == ^id and r.to_phone == ^me.phone and r.status == "pending" and
            r.expires_at > ^now
    )
  end

  @doc "`POST /friends/requests/{id}/decline`: silent to the requester."
  def decline(%User{} = me, id) do
    with {:ok, id} <- Ecto.UUID.cast(id),
         {1, _} <-
           Repo.update_all(
             from(r in FriendRequest,
               where: r.id == ^id and r.to_phone == ^me.phone and r.status == "pending"
             ),
             set: [status: "declined", updated_at: DateTime.utc_now()]
           ) do
      :ok
    else
      _ -> {:error, :not_found}
    end
  end

  @doc "`DELETE /friends/requests/{id}`: cancels my own outgoing request."
  def cancel(%User{} = me, id) do
    with {:ok, id} <- Ecto.UUID.cast(id),
         {1, _} <-
           Repo.update_all(
             from(r in FriendRequest,
               where:
                 r.id == ^id and r.from_user_id == ^me.id and r.status in ["pending", "declined"]
             ),
             set: [status: "cancelled", updated_at: DateTime.utc_now()]
           ) do
      :ok
    else
      _ -> {:error, :not_found}
    end
  end

  @doc "`DELETE /friends/{user_id}`: unfriend both ways, silently."
  def unfriend(%User{} = me, other_id) do
    with {:ok, other_id} <- Ecto.UUID.cast(other_id),
         %User{} = other <- Repo.get(User, other_id) do
      Repo.transaction(fn ->
        lock_pair(me.phone, other.phone)
        unfriend_rows(me.id, other.id)
      end)

      drop_watches(me.id, other.id)
    end

    :ok
  end

  @doc "`POST /blocks`: ends friendship and pending requests both ways. Unknown ids are a no-op."
  def block(%User{} = me, other_id) do
    with {:ok, other_id} <- Ecto.UUID.cast(other_id),
         true <- other_id != me.id,
         %User{} = other <- Repo.get(User, other_id) do
      Repo.transaction(fn ->
        lock_pair(me.phone, other.phone)

        Repo.insert_all(
          "blocks",
          [
            %{
              blocker_id: dump(me.id),
              blocked_id: dump(other.id),
              inserted_at: DateTime.utc_now()
            }
          ],
          on_conflict: :nothing
        )

        unfriend_rows(me.id, other.id)

        Repo.update_all(
          from(r in FriendRequest,
            where:
              r.status in ["pending", "declined"] and
                ((r.from_user_id == ^me.id and r.to_phone == ^other.phone) or
                   (r.from_user_id == ^other.id and r.to_phone == ^me.phone))
          ),
          set: [status: "cancelled", updated_at: DateTime.utc_now()]
        )
      end)

      drop_watches(me.id, other.id)
    end

    :ok
  end

  @doc "`DELETE /blocks/{user_id}`: unblock (no friendship is restored)."
  def unblock(%User{} = me, other_id) do
    with {:ok, other_id} <- Ecto.UUID.cast(other_id) do
      Repo.delete_all(
        from bl in "blocks",
          where:
            bl.blocker_id == type(^me.id, :binary_id) and
              bl.blocked_id == type(^other_id, :binary_id)
      )
    end

    :ok
  end

  # Presence stops at once: each side's channels drop the other from their watch sets.
  defp drop_watches(a, b) do
    Phoenix.PubSub.broadcast(RisiMe.PubSub, Messaging.topic(a), {:drop_watch, b})
    Phoenix.PubSub.broadcast(RisiMe.PubSub, Messaging.topic(b), {:drop_watch, a})
  end

  ## Lists

  @doc "`GET /friends`."
  def list(%User{} = me) do
    now = DateTime.utc_now()

    friends =
      Repo.all(
        from f in "friendships",
          join: u in User,
          on:
            (f.user_a == type(^me.id, :binary_id) and u.id == f.user_b) or
              (f.user_b == type(^me.id, :binary_id) and u.id == f.user_a),
          order_by: [asc: fragment("lower(?)", u.display_name)],
          select: {u, type(f.inserted_at, :utc_datetime_usec)}
      )
      |> Enum.map(fn {u, since} -> friend_json(u, since) end)

    incoming =
      Repo.all(
        from r in FriendRequest,
          join: u in User,
          on: u.id == r.from_user_id,
          left_join: bl in "blocks",
          on:
            (bl.blocker_id == type(^me.id, :binary_id) and bl.blocked_id == u.id) or
              (bl.blocker_id == u.id and bl.blocked_id == type(^me.id, :binary_id)),
          where:
            r.to_phone == ^me.phone and r.status == "pending" and r.expires_at > ^now and
              is_nil(bl.blocker_id),
          order_by: [desc: r.inserted_at],
          select: {r, u}
      )
      |> Enum.map(fn {r, u} ->
        %{
          id: r.id,
          phone: u.phone,
          user_id: u.id,
          display_name: u.display_name,
          company: u.company,
          inserted_at: Messaging.iso(r.inserted_at)
        }
      end)

    outgoing =
      Repo.all(
        from r in FriendRequest,
          where:
            r.from_user_id == ^me.id and r.status in ["pending", "declined"] and
              r.expires_at > ^now,
          order_by: [desc: r.inserted_at]
      )
      |> Enum.map(fn r ->
        %{
          id: r.id,
          phone: r.to_phone,
          user_id: nil,
          display_name: nil,
          company: nil,
          inserted_at: Messaging.iso(r.inserted_at)
        }
      end)

    blocked =
      Repo.all(
        from bl in "blocks",
          join: u in User,
          on: u.id == bl.blocked_id,
          where: bl.blocker_id == type(^me.id, :binary_id),
          order_by: [asc: fragment("lower(?)", u.display_name)],
          select: u
      )
      |> Enum.map(&%{user_id: &1.id, phone: &1.phone, display_name: &1.display_name})

    %{friends: friends, incoming: incoming, outgoing: outgoing, blocked: blocked}
  end

  @doc "`Friend` JSON."
  def friend_json(%User{} = u, since) do
    %{
      user_id: u.id,
      phone: u.phone,
      display_name: u.display_name,
      company: u.company,
      vouched_by: vouched_by(u),
      since: Messaging.iso(since)
    }
  end

  @doc "`GET /contacts` (v1.6: friends only). `registered` = can be messaged."
  def contacts(%User{} = me) do
    gate_off? = not Accounts.phone_verification_required?()

    me
    |> list()
    |> Map.fetch!(:friends)
    |> Enum.map(fn f ->
      verified = gate_off? or Accounts.messageable?(f.user_id)

      %{
        phone: f.phone,
        display_name: f.display_name,
        company: f.company,
        user_id: f.user_id,
        registered: verified
      }
    end)
  end

  @doc """
  `vouched_by` (§9.1): the inviter, while the user joined by invite and their phone isn't
  SMS-verified (stored state).
  """
  def vouched_by(%User{invited_by_id: nil}), do: nil

  def vouched_by(%User{invited_by_id: inviter_id} = user) do
    if Accounts.phone_verified?(user) do
      nil
    else
      case Repo.get(User, inviter_id) do
        nil -> nil
        inviter -> %{user_id: inviter.id, display_name: inviter.display_name}
      end
    end
  end

  ## Invites

  @doc "Invite link and share text (`INVITE_LINK`, default https://risicloud.ai/app/risime/)."
  def invite_link,
    do: Application.get_env(:risime, :invite_link, "https://risicloud.ai/app/risime/")

  @doc "`POST /invites`. The reply is identical on every path (§9.1)."
  def create_invite(%User{} = inviter, params) do
    phone = params["phone"]
    email = if is_binary(params["email"]), do: Validate.normalize_email(params["email"])
    name = if is_binary(params["name"]), do: String.trim(params["name"])

    cond do
      not Validate.phone?(phone) -> {:error, :invalid_phone}
      not Validate.email?(email) -> {:error, :invalid_email}
      not (is_binary(name) and String.length(name) in 1..64) -> {:error, :invalid_name}
      true -> invite_within_limits(inviter, phone, email, name)
    end
  end

  defp invite_within_limits(inviter, phone, email, name) do
    now = DateTime.utc_now()
    day_ago = DateTime.add(now, -1, :day)

    {today, oldest_today} =
      Repo.one(
        from i in Invite,
          where: i.inviter_id == ^inviter.id and i.inserted_at > ^day_ago,
          select: {count(i.id), min(i.inserted_at)}
      )

    pending =
      Repo.aggregate(
        from(i in Invite,
          where: i.inviter_id == ^inviter.id and i.status == "pending" and i.expires_at > ^now
        ),
        :count
      )

    cond do
      today >= @invites_per_day ->
        {:error, {:rate_limited, max(DateTime.diff(DateTime.add(oldest_today, 1, :day), now), 1)}}

      pending >= @invites_pending ->
        {:error, {:rate_limited, 3600}}

      true ->
        {:ok, store_invite(inviter, phone, email, name, now)}
    end
  end

  defp store_invite(inviter, phone, email, name, now) do
    invite = %Invite{
      inviter_id: inviter.id,
      phone: phone,
      email: email,
      name: name,
      expires_at: DateTime.add(now, @invite_ttl_days, :day)
    }

    if phone == inviter.phone do
      # Your own phone: the same reply, nothing stored.
      %{invite | id: Ecto.UUID.generate(), inserted_at: now, updated_at: now}
    else
      invite = Repo.insert!(invite)

      # A member already holds this phone or email: befriend silently (a request to the phone).
      member = Repo.one(from u in User, where: u.phone == ^phone or u.email == ^email, limit: 1)
      if member, do: do_request(inviter, phone)

      invite
    end
  end

  @doc "`GET /invites`: my sent invites, newest first (expired ones shown as `expired`)."
  def list_invites(%User{} = inviter) do
    Repo.all(
      from i in Invite, where: i.inviter_id == ^inviter.id, order_by: [desc: i.inserted_at]
    )
  end

  @doc "`DELETE /invites/{id}`: revoke a pending invite (a no-op otherwise)."
  def revoke_invite(%User{} = inviter, id) do
    with {:ok, id} <- Ecto.UUID.cast(id) do
      Repo.update_all(
        from(i in Invite,
          where: i.id == ^id and i.inviter_id == ^inviter.id and i.status == "pending"
        ),
        set: [status: "revoked", updated_at: DateTime.utc_now()]
      )
    end

    :ok
  end

  @doc "`Invite` JSON."
  def invite_json(%Invite{} = i) do
    now = DateTime.utc_now()

    status =
      if i.status == "pending" and DateTime.compare(i.expires_at, now) != :gt,
        do: "expired",
        else: i.status

    link = invite_link()

    %{
      id: i.id,
      phone: i.phone,
      email: i.email,
      name: i.name,
      status: status,
      expires_at: Messaging.iso(i.expires_at),
      inserted_at: Messaging.iso(i.inserted_at),
      subject: "Join me on RisiMe",
      share_text: String.slice("Join me on RisiMe: #{link} — sign in with #{i.email}", 0, 300),
      link: link
    }
  end

  ## Redemption (called from Accounts.map_identity/1)

  @doc """
  The pending, unexpired invites for `email`, oldest first.
  """
  def pending_invites(email) do
    now = DateTime.utc_now()

    Repo.all(
      from i in Invite,
        where: i.email == ^email and i.status == "pending" and i.expires_at > ^now,
        order_by: [asc: i.inserted_at]
    )
  end

  @doc """
  Redeems invites for a new Keycloak identity (§9.1): creates the user with the oldest invite's
  phone, accepts every pending invite with that phone and befriends those inviters; invites for
  other phones expire. `409` if that phone is taken or allowlisted.
  """
  def redeem([oldest | _] = invites, %{sub: sub, email: email} = identity) do
    phone = oldest.phone

    taken? =
      Repo.exists?(from u in User, where: u.phone == ^phone) or
        Repo.exists?(from a in AllowlistEntry, where: a.phone == ^phone)

    if taken? do
      {:error, :identity_conflict}
    else
      name =
        case identity[:name] do
          n when is_binary(n) and n != "" -> String.slice(String.trim(n), 0, 64)
          _ -> oldest.name
        end

      Repo.transaction(fn ->
        user =
          Repo.insert!(%User{
            phone: phone,
            email: email,
            display_name: name,
            company: "",
            keycloak_sub: sub,
            invited_by_id: oldest.inviter_id
          })

        {same, other} = Enum.split_with(invites, &(&1.phone == phone))
        set_status(other, "expired")
        accept_invites(user, same)
        user
      end)
      |> case do
        {:ok, user} -> {:ok, user}
        {:error, _} -> {:error, :identity_conflict}
      end
    end
  rescue
    Ecto.ConstraintError -> {:error, :identity_conflict}
  end

  @doc "Accepts `invites` for `user` and befriends the inviters (signals after the caller commits)."
  def accept_invites(_user, []), do: :ok

  def accept_invites(%User{} = user, invites) do
    set_status(invites, "accepted")

    for i <- invites, i.inviter_id != user.id, not blocked_between?(user.id, i.inviter_id) do
      make_friends!(user.id, i.inviter_id)

      if inviter = Repo.get(User, i.inviter_id),
        do: Messaging.signal(inviter.id, signal("request_accepted", i.id, user))
    end

    :ok
  end

  defp set_status(invites, status) do
    ids = Enum.map(invites, & &1.id)

    Repo.update_all(from(i in Invite, where: i.id in ^ids),
      set: [status: status, updated_at: DateTime.utc_now()]
    )
  end

  ## Housekeeping

  @doc "Marks expired invites and friend requests (Oban, daily). Returns `{invites, requests}`."
  def expire(now \\ DateTime.utc_now()) do
    {i, _} =
      Repo.update_all(from(i in Invite, where: i.status == "pending" and i.expires_at <= ^now),
        set: [status: "expired", updated_at: now]
      )

    {r, _} =
      Repo.update_all(
        from(r in FriendRequest,
          where: r.status in ["pending", "declined"] and r.expires_at <= ^now
        ),
        set: [status: "expired", updated_at: now]
      )

    {i, r}
  end
end
