defmodule RisiMe.Risi do
  @moduledoc """
  The Risi agent's identity and switches (contract v1.24 §24.11, §24.15; decisions 065, 066).

  * `RISI` (`on`/`off`, default off): while off, every chat reports `agent_unavailable` and
    `POST …/official` answers `503 agent_unavailable`.
  * `RISI_USER_ID` / `RISI_DEVICE_ID`: the fixed UUIDs of the agent user and its one device
    (`risi-1`). Clients recognise Risi by `Member.kind`, never by these ids.
  * `TABS` (`on`/`off`, default off): `GET /auth/config` `tabs` (§24.15).

  `seed/0` (run by `RisiMe.Release.migrate/0`, idempotent) inserts the agent user (`kind:
  "agent"`, display name "Risi") and its device row (capabilities `groups`, `tabs`) only while
  `RISI` is on. The device's MLS signature key and key packages come from the agent tree.
  """
  import Ecto.Query

  alias RisiMe.Accounts.User
  alias RisiMe.Devices.Device
  alias RisiMe.Repo

  @default_user "9e1f0000-0000-4000-8000-000000000001"
  @default_device "9e1f0000-0000-4000-8000-000000000002"
  @caps ["groups", "tabs"]

  @doc "True while `RISI=on`."
  def enabled?, do: Application.get_env(:risime, :risi, false) == true

  @doc "True while `TABS=on` (`/auth/config` `tabs`)."
  def tabs_on?, do: Application.get_env(:risime, :tabs, false) == true

  @doc "v1.25 §25.8: true while `RISI_TOOLS=on` (`/auth/config` `risi_tools`, default off)."
  def tools_on?, do: Application.get_env(:risime, :risi_tools, false) == true

  @doc "v1.26 §26.9: true while `RISI_SKILLS=on` (`/auth/config` `risi_skills`, default off)."
  def skills_switch?, do: Application.get_env(:risime, :risi_skills, false) == true

  @doc "v1.27 §27.10: true while `RISI_LEDGER=on` (`/auth/config` `risi_ledger`, default off)."
  def ledger_on?, do: Application.get_env(:risime, :risi_ledger, false) == true

  @doc """
  v1.27 §27.10: true while `RISI_TRANSCRIBE=on`, the ledger is on and the speech model is
  healthy (`/auth/config` `risi_transcribe`, default off). Until the listener and the speech model
  are wired (S25–S27), "healthy" is the `:risi_speech_ready` setting (default false), so the
  switch alone never offers transcription.
  """
  def transcribe_on? do
    Application.get_env(:risime, :risi_transcribe, false) == true and ledger_on?() and
      Application.get_env(:risime, :risi_speech_ready, false) == true
  end

  def user_id, do: Application.get_env(:risime, :risi_user_id, @default_user)
  def device_id, do: Application.get_env(:risime, :risi_device_id, @default_device)

  @doc "The capabilities Risi's device advertises (§24.7)."
  def capabilities, do: @caps

  @doc "True if `user_id` is an agent user (`users.kind = 'agent'`)."
  def agent?(user_id) when is_binary(user_id),
    do: Repo.exists?(from u in User, where: u.id == ^user_id and u.kind == "agent")

  def agent?(_), do: false

  @doc "The agent users among `user_ids`."
  def agents([]), do: []

  def agents(user_ids),
    do: Repo.all(from u in User, where: u.id in ^user_ids and u.kind == "agent", select: u.id)

  @doc """
  True when Risi can join a conversation now (§24.2): `RISI` on, its agent tree
  (`RisiMe.Agent.Supervisor`, S5) running, its user and device exist, the device has an MLS key,
  and it has a key package to claim. (`:risi_agent_check` false skips the tree check: only the
  REST tests, which fake Risi's device.)
  """
  def available? do
    enabled?() and
      (Application.get_env(:risime, :risi_agent_check, true) == false or
         RisiMe.Agent.running?()) and
      Repo.exists?(
        from d in Device,
          join: u in User,
          on: u.id == d.user_id,
          join: k in "mls_key_packages",
          on: k.device_ref == d.id,
          where:
            d.user_id == ^user_id() and d.device_id == ^device_id() and u.kind == "agent" and
              not is_nil(d.mls_signature_key)
      )
  end

  @doc "Seeds the agent user and device while `RISI` is on. Returns `:ok` or `:off`."
  def seed do
    if enabled?() do
      now = DateTime.utc_now()

      Repo.insert_all(
        User,
        [
          %{
            id: user_id(),
            # users.phone/email are not null and unique; an agent has neither (never shown:
            # a Member's phone is visible to friends only and Risi has none).
            phone: "agent:" <> user_id(),
            email: "risi@agent.invalid",
            display_name: "Risi",
            company: "RisiMe",
            kind: "agent",
            inserted_at: now,
            updated_at: now
          }
        ],
        on_conflict: [set: [kind: "agent", display_name: "Risi", updated_at: now]],
        conflict_target: [:id]
      )

      case Repo.get_by(Device, user_id: user_id(), device_id: device_id()) do
        nil ->
          Repo.insert!(%Device{
            user_id: user_id(),
            device_id: device_id(),
            platform: "agent",
            capabilities: @caps,
            last_seen_at: now
          })

        d ->
          d |> Ecto.Changeset.change(capabilities: @caps, last_seen_at: now) |> Repo.update!()
      end

      :ok
    else
      :off
    end
  end
end
