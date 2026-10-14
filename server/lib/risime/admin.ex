defmodule RisiMe.Admin do
  @moduledoc """
  The admin console's data (decision 089): who is an admin, and the append-only audit log.

  * **Admins live in the database** (`admins`), never in code. `admin?/1` is checked on every
    console request and LiveView mount, so a removal takes effect at once.
  * **One-time seed** (`seed/1`): while no admin exists at all, the RisiMe account whose email
    matches `RISIME_ADMIN_SEED_EMAIL` becomes the first admin (audit-logged). Once any admin
    row exists (even a removed one) the setting does nothing.
  * **Every admin action is audit-logged** (`audit/5`, table `admin_audit_log`, append-only by a
    trigger). Summaries are short labels (a setting's value, a model alias, a clip's file name),
    never message content.

  Adding or removing admins has no console page yet ("Users and admins" comes later):

      scripts/risi-rpc 'RisiMe.Admin.add_admin("<user id>", "<admin user id>")'
  """
  import Ecto.Query

  alias RisiMe.Accounts.User
  alias RisiMe.Admin.{AdminUser, AuditEntry}
  alias RisiMe.Repo

  require Logger

  @seed_lock 0x41444D49

  @doc "True when `user_id` is an active admin (and the user exists and isn't disabled)."
  def admin?(user_id) when is_binary(user_id) do
    Repo.exists?(
      from a in AdminUser,
        join: u in User,
        on: u.id == a.user_id,
        where: a.user_id == ^user_id and is_nil(a.removed_at) and is_nil(u.disabled_at)
    )
  rescue
    Ecto.Query.CastError -> false
  end

  def admin?(_), do: false

  @doc "Active admins with their user, oldest first."
  def list_admins do
    Repo.all(
      from a in AdminUser,
        join: u in User,
        on: u.id == a.user_id,
        where: is_nil(a.removed_at),
        order_by: a.added_at,
        select: %{user_id: u.id, display_name: u.display_name, added_at: a.added_at}
    )
  end

  @doc "True once any admin row exists (active or removed): the seed setting is then ignored."
  def any_admin_ever?, do: Repo.exists?(AdminUser)

  @doc "Makes `user_id` an admin (audit-logged as `admin_add` by `by`)."
  def add_admin(user_id, by) when is_binary(user_id) and is_binary(by) do
    Repo.transaction(fn ->
      cond do
        not admin?(by) ->
          Repo.rollback(:forbidden)

        admin?(user_id) ->
          Repo.rollback(:already_admin)

        is_nil(Repo.get(User, user_id)) ->
          Repo.rollback(:no_user)

        true ->
          insert_admin!(user_id, by)
          audit!(by, "admin_add", "user:" <> user_id, "not admin", "admin")
          :ok
      end
    end)
    |> unwrap()
  end

  @doc "Removes `user_id`'s admin role (sets `removed_at`; audit-logged). Never the last admin."
  def remove_admin(user_id, by) when is_binary(user_id) and is_binary(by) do
    Repo.transaction(fn ->
      active = Repo.aggregate(from(a in AdminUser, where: is_nil(a.removed_at)), :count)

      cond do
        not admin?(by) ->
          Repo.rollback(:forbidden)

        not admin?(user_id) ->
          Repo.rollback(:not_admin)

        active <= 1 ->
          Repo.rollback(:last_admin)

        true ->
          now = DateTime.utc_now()

          Repo.update_all(
            from(a in AdminUser, where: a.user_id == ^user_id and is_nil(a.removed_at)),
            set: [removed_at: now, removed_by: by]
          )

          audit!(by, "admin_remove", "user:" <> user_id, "admin", "not admin")
          :ok
      end
    end)
    |> unwrap()
  end

  @doc """
  The one-time seed (decision 089). With `email` set and no admin row at all, the account with
  that email (case-insensitive, not disabled, not an agent) becomes the first admin and the
  action is audit-logged (`admin_seed`, actor nil = the server). Returns `{:ok, user_id}`,
  `:noop` (no setting, an admin already exists, or no matching account yet).

  Called at boot (`RisiMe.Admin.Seed`) and at console sign-in (`seed_signin/2`).
  """
  def seed(email) when is_binary(email) and email != "" do
    email = email |> String.trim() |> String.downcase()

    Repo.transaction(fn ->
      # Two nodes (or a boot and a sign-in) never both seed.
      Repo.query!("SELECT pg_advisory_xact_lock($1)", [@seed_lock])

      if any_admin_ever?() do
        :noop
      else
        case Repo.one(
               from u in User,
                 where:
                   fragment("lower(?)", u.email) == ^email and is_nil(u.disabled_at) and
                     u.kind == "user",
                 order_by: u.inserted_at,
                 limit: 1
             ) do
          nil ->
            :noop

          %User{id: id} ->
            insert_admin!(id, nil)
            audit!(nil, "admin_seed", "user:" <> id, "no admins", "admin (one-time seed)")
            {:ok, id}
        end
      end
    end)
    |> case do
      {:ok, result} -> result
      {:error, _} -> :noop
    end
  end

  def seed(_), do: :noop

  @doc """
  The seed at sign-in: `user` signed in with a verified Keycloak `email`; while no admin exists
  and it equals the seed setting, `user` becomes the first admin.
  """
  def seed_signin(%User{id: id}, email) when is_binary(email) do
    case seed_email() do
      seed when is_binary(seed) and seed != "" ->
        if String.downcase(String.trim(seed)) == String.downcase(email) and
             not any_admin_ever?() do
          Repo.transaction(fn ->
            Repo.query!("SELECT pg_advisory_xact_lock($1)", [@seed_lock])

            if not any_admin_ever?() do
              insert_admin!(id, nil)
              audit!(nil, "admin_seed", "user:" <> id, "no admins", "admin (one-time seed)")
            end
          end)
        end

        :ok

      _ ->
        :ok
    end
  end

  @doc "The one-time seed setting (`RISIME_ADMIN_SEED_EMAIL`), or nil."
  def seed_email, do: Application.get_env(:risime, :admin_seed_email)

  defp insert_admin!(user_id, by) do
    Repo.insert!(%AdminUser{user_id: user_id, added_by: by, added_at: DateTime.utc_now()})
  end

  @doc """
  Appends one audit entry. `actor` is the admin's user id (nil = the server). `before` and
  `after` are short summaries (truncated to 500 characters). Returns `:ok`.
  """
  def audit(actor, action, target, before \\ nil, after_ \\ nil) do
    audit!(actor, action, target, before, after_)
    :ok
  end

  defp audit!(actor, action, target, before, after_) do
    Repo.insert!(%AuditEntry{
      actor_id: actor,
      action: action,
      target: summary(target),
      before: summary(before),
      after: summary(after_),
      at: DateTime.utc_now()
    })
  end

  defp summary(nil), do: nil
  defp summary(s) when is_binary(s), do: String.slice(s, 0, 500)
  defp summary(other), do: other |> inspect() |> String.slice(0, 500)

  @doc "The latest audit entries (newest first), with the actor's display name."
  def recent_audit(limit \\ 50) do
    Repo.all(
      from e in AuditEntry,
        left_join: u in User,
        on: u.id == e.actor_id,
        order_by: [desc: e.at, desc: e.id],
        limit: ^limit,
        select: %{
          at: e.at,
          actor: coalesce(u.display_name, "server"),
          action: e.action,
          target: e.target,
          before: e.before,
          after: e.after
        }
    )
  end

  @doc "The admin's user (for the console header), or nil."
  def get_user(id) when is_binary(id), do: Repo.get(User, id)
  def get_user(_), do: nil

  defp unwrap({:ok, v}), do: v
  defp unwrap({:error, reason}), do: {:error, reason}
end
