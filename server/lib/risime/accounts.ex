defmodule RisiMe.Accounts do
  @moduledoc """
  Allowlist, users, email OTP challenges and bearer tokens.
  """
  import Ecto.Query

  alias RisiMe.{RateLimiter, Repo}

  alias RisiMe.Accounts.{
    AllowlistEntry,
    OtpChallenge,
    OtpSender,
    PhoneChallenge,
    User,
    UserToken,
    Validate
  }

  @otp_ttl_seconds 300
  @otp_max_attempts 5
  @otp_requests_per_window 3
  @otp_request_window :timer.minutes(15)
  @otp_challenge_retention_hours 24
  @revoked_token_retention_days 30

  def otp_ttl_seconds, do: @otp_ttl_seconds

  ## Allowlist

  @doc "Inserts or updates an allowlist entry, keyed by phone."
  def allow(attrs) do
    attrs = Map.new(attrs)

    entry =
      case attrs[:phone] && Repo.get_by(AllowlistEntry, phone: attrs[:phone]) do
        nil -> %AllowlistEntry{}
        entry -> entry
      end

    entry
    |> AllowlistEntry.changeset(attrs)
    |> Repo.insert_or_update()
  end

  ## Keycloak identity mapping (contract v1.3 §6.1)

  @doc """
  Maps a verified Keycloak identity (`sub`, lowercased `email`) to a RisiMe user:

    1. a user bound to `sub` → that user, if their phone is still on the allowlist;
    2. else the allowlist entry for `email` (none → `:not_allowlisted`);
    3. that entry's phone: no user → create it bound to `sub`; an unbound user → bind atomically;
       a user bound to another `sub` → `:identity_conflict`.

  Once bound, `sub` wins over later email changes. Re-binding is admin-only (`rebind/1`).
  """
  @spec map_identity(%{sub: String.t(), email: String.t()}) ::
          {:ok, %User{}} | {:error, :not_allowlisted | :identity_conflict}
  def map_identity(%{sub: sub, email: email}) do
    case Repo.get_by(User, keycloak_sub: sub) do
      %User{} = user ->
        if Repo.exists?(from a in AllowlistEntry, where: a.phone == ^user.phone),
          do: {:ok, user},
          else: {:error, :not_allowlisted}

      nil ->
        case Repo.one(from a in AllowlistEntry, where: fragment("lower(?)", a.email) == ^email) do
          nil -> {:error, :not_allowlisted}
          entry -> bind(entry, sub)
        end
    end
  end

  defp bind(entry, sub) do
    case Repo.get_by(User, phone: entry.phone) do
      nil ->
        Repo.insert(
          %User{
            phone: entry.phone,
            email: entry.email,
            display_name: entry.display_name,
            company: entry.company,
            keycloak_sub: sub
          },
          on_conflict: :nothing
        )

        # A concurrent first request may have created or bound it; re-read either way.
        bound_user(entry.phone, sub)

      %User{keycloak_sub: nil} = user ->
        Repo.update_all(from(u in User, where: u.id == ^user.id and is_nil(u.keycloak_sub)),
          set: [keycloak_sub: sub]
        )

        bound_user(entry.phone, sub)

      %User{} ->
        bound_user(entry.phone, sub)
    end
  end

  defp bound_user(phone, sub) do
    case Repo.get_by(User, phone: phone) do
      %User{keycloak_sub: ^sub} = user -> {:ok, user}
      _ -> {:error, :identity_conflict}
    end
  end

  @doc """
  Clears the Keycloak binding of the user with `phone` (admin re-bind, contract v1.3 §6.1), so
  the next sign-in binds whichever account owns the allowlisted email.
  Returns `{:ok, previous_sub}` or `{:error, :not_found}`.
  """
  def rebind(phone) do
    case Repo.get_by(User, phone: phone) do
      nil ->
        {:error, :not_found}

      user ->
        # A new Keycloak account may be another person: they verify the phone again.
        Repo.update_all(from(u in User, where: u.id == ^user.id),
          set: [keycloak_sub: nil, phone_verified_for: nil]
        )

        {:ok, user.keycloak_sub}
    end
  end

  def list_allowlist do
    Repo.all(from a in AllowlistEntry, order_by: [asc: fragment("lower(?)", a.display_name)])
  end

  def get_allowlist_entry(phone), do: Repo.get_by(AllowlistEntry, phone: phone)

  ## OTP

  @doc """
  Starts a login. Well-formed input always gets `:ok`, so callers can't probe the allowlist;
  a code is created and emailed only when (phone, email) matches an allowlist entry.
  """
  @spec request_otp(term, term) ::
          :ok | {:error, :invalid_phone | :invalid_email | :rate_limited}
  def request_otp(phone, email) do
    cond do
      not Validate.phone?(phone) ->
        {:error, :invalid_phone}

      not Validate.email?(email) ->
        {:error, :invalid_email}

      true ->
        with :ok <-
               RateLimiter.hit(:otp_request, phone, @otp_requests_per_window, @otp_request_window) do
          email = Validate.normalize_email(email)

          case get_allowlist_entry(phone) do
            %AllowlistEntry{email: ^email} = entry -> create_and_send_challenge(entry)
            _ -> :ok
          end

          :ok
        end
    end
  end

  defp create_and_send_challenge(entry) do
    code = :crypto.strong_rand_bytes(4) |> :binary.decode_unsigned() |> rem(1_000_000)
    code = code |> Integer.to_string() |> String.pad_leading(6, "0")

    Repo.insert!(%OtpChallenge{
      phone: entry.phone,
      code_hash: code_hash(entry.phone, code),
      expires_at: DateTime.add(DateTime.utc_now(), @otp_ttl_seconds, :second)
    })

    message = %{
      subject: "Your RisiMe code: #{code}",
      code: code,
      body: """
      Hi #{entry.display_name},

      Your RisiMe login code is #{code}. It expires in 5 minutes.

      If you didn't ask for this code, you can ignore this email.
      """
    }

    OtpSender.deliver(:email, {entry.display_name, entry.email}, message, entry.phone)
  end

  @doc """
  Checks a code against the phone's latest challenge. On success, creates the user on first
  login (copying the allowlist entry) and issues a new bearer token.
  """
  @spec verify_otp(term, term, term) ::
          {:ok, String.t(), %User{}}
          | {:error, :invalid_code | :expired | :too_many_attempts}
  def verify_otp(phone, code, device_name) do
    challenge =
      Validate.phone?(phone) &&
        Repo.one(
          from c in OtpChallenge,
            where: c.phone == ^phone,
            order_by: [desc: c.inserted_at],
            limit: 1
        )

    cond do
      !challenge or challenge.consumed_at != nil ->
        {:error, :invalid_code}

      DateTime.compare(DateTime.utc_now(), challenge.expires_at) != :lt ->
        {:error, :expired}

      challenge.attempts >= @otp_max_attempts ->
        {:error, :too_many_attempts}

      not (is_binary(code) and
               Plug.Crypto.secure_compare(code_hash(phone, code), challenge.code_hash)) ->
        Repo.update_all(
          from(c in OtpChallenge,
            where: c.id == ^challenge.id and c.attempts < @otp_max_attempts
          ),
          inc: [attempts: 1]
        )

        {:error, :invalid_code}

      true ->
        consume_and_login(challenge, device_name)
    end
  end

  defp consume_and_login(challenge, device_name) do
    Repo.transaction(fn ->
      {consumed, _} =
        Repo.update_all(
          from(c in OtpChallenge,
            where:
              c.id == ^challenge.id and is_nil(c.consumed_at) and
                c.attempts < @otp_max_attempts
          ),
          set: [consumed_at: DateTime.utc_now()]
        )

      with 1 <- consumed,
           %AllowlistEntry{} = entry <- get_allowlist_entry(challenge.phone) do
        user = get_or_create_user!(entry)
        {create_token!(user, device_name), user}
      else
        _ -> Repo.rollback(:invalid_code)
      end
    end)
    |> case do
      {:ok, {token, user}} -> {:ok, token, user}
      {:error, reason} -> {:error, reason}
    end
  end

  defp code_hash(phone, code) do
    secret = Application.fetch_env!(:risime, RisiMeWeb.Endpoint)[:secret_key_base]
    :crypto.mac(:hmac, :sha256, secret, phone <> code)
  end

  ## Maintenance (run daily by RisiMe.Workers.PruneAccounts)

  @doc """
  Deletes OTP challenges created more than 24 hours before `now`. They expire after
  #{@otp_ttl_seconds} s, so nothing live is lost. Returns the number deleted.
  """
  @spec prune_otp_challenges(DateTime.t()) :: non_neg_integer
  def prune_otp_challenges(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@otp_challenge_retention_hours, :hour)
    {count, _} = Repo.delete_all(from c in OtpChallenge, where: c.inserted_at < ^cutoff)
    count
  end

  @doc "Deletes phone challenges created more than 48 hours before `now` (past every SMS budget window)."
  @spec prune_phone_challenges(DateTime.t()) :: non_neg_integer
  def prune_phone_challenges(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -48, :hour)
    {count, _} = Repo.delete_all(from c in PhoneChallenge, where: c.inserted_at < ^cutoff)
    count
  end

  @doc """
  Deletes user tokens revoked more than 30 days before `now`. Live tokens are never touched.
  Returns the number deleted.
  """
  @spec prune_revoked_tokens(DateTime.t()) :: non_neg_integer
  def prune_revoked_tokens(now \\ DateTime.utc_now()) do
    cutoff = DateTime.add(now, -@revoked_token_retention_days, :day)

    {count, _} =
      Repo.delete_all(
        from t in UserToken, where: not is_nil(t.revoked_at) and t.revoked_at < ^cutoff
      )

    count
  end

  ## Users

  def get_user(id) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> Repo.get(User, id)
      :error -> nil
    end
  end

  @doc "True if `id` is a registered user's id."
  def user_exists?(id) do
    case Ecto.UUID.cast(id) do
      {:ok, id} -> Repo.exists?(from u in User, where: u.id == ^id)
      :error -> false
    end
  end

  @doc """
  `%{user_id => last_seen_at | nil}` for the ids that are registered users. Other ids,
  including strings that aren't UUIDs, are left out.
  """
  @spec last_seen_by_id([String.t()]) :: %{String.t() => DateTime.t() | nil}
  def last_seen_by_id(ids) do
    ids = for id <- ids, {:ok, id} <- [Ecto.UUID.cast(id)], uniq: true, do: id

    from(u in User, where: u.id in ^ids, select: {u.id, u.last_seen_at})
    |> Repo.all()
    |> Map.new()
  end

  @doc "Sets the user's `last_seen_at` (presence, PROTOCOL.md §2.5)."
  def touch_last_seen(user_id, %DateTime{} = at) do
    Repo.update_all(from(u in User, where: u.id == ^user_id), set: [last_seen_at: at])
    :ok
  end

  defp get_or_create_user!(entry) do
    Repo.insert!(
      %User{
        phone: entry.phone,
        email: entry.email,
        display_name: entry.display_name,
        company: entry.company
      },
      on_conflict: :nothing,
      conflict_target: :phone
    )

    Repo.get_by!(User, phone: entry.phone)
  end

  def update_profile(%User{} = user, attrs) do
    user
    |> User.profile_changeset(attrs)
    |> Repo.update()
  end

  ## Tokens

  defp create_token!(user, device_name) do
    token = :crypto.strong_rand_bytes(32) |> Base.url_encode64(padding: false)

    device_name =
      if is_binary(device_name), do: device_name |> String.trim() |> String.slice(0, 64)

    Repo.insert!(%UserToken{
      user_id: user.id,
      token_hash: :crypto.hash(:sha256, token),
      device_name: device_name,
      last_seen_at: DateTime.utc_now()
    })

    token
  end

  @doc "Returns `{user, token_record}` for a live (unrevoked) token, or nil."
  def fetch_by_token(token) when is_binary(token) do
    Repo.one(
      from t in UserToken,
        join: u in assoc(t, :user),
        where: t.token_hash == ^:crypto.hash(:sha256, token) and is_nil(t.revoked_at),
        select: {u, t}
    )
  end

  def fetch_by_token(_), do: nil

  def touch_token(%UserToken{id: id}) do
    Repo.update_all(from(t in UserToken, where: t.id == ^id),
      set: [last_seen_at: DateTime.utc_now()]
    )

    :ok
  end

  def revoke_token(%UserToken{id: id}) do
    Repo.update_all(from(t in UserToken, where: t.id == ^id and is_nil(t.revoked_at)),
      set: [revoked_at: DateTime.utc_now()]
    )

    :ok
  end

  ## Contacts

  @doc "Every other allowlisted person, with their user id once they have logged in."
  def list_contacts(%User{phone: own_phone}) do
    from(a in AllowlistEntry,
      left_join: u in User,
      on: u.phone == a.phone,
      where: a.phone != ^own_phone,
      select: %{
        phone: a.phone,
        display_name: coalesce(u.display_name, a.display_name),
        company: a.company,
        user_id: u.id
      }
    )
    |> Repo.all()
    |> Enum.map(&Map.put(&1, :registered, &1.user_id != nil))
    |> Enum.sort_by(&{String.downcase(&1.display_name), &1.phone})
  end
end
