defmodule RisiMeWeb.ApiError do
  @moduledoc "Sends a contract-shaped REST error."
  import Plug.Conn
  import Phoenix.Controller, only: [json: 2]

  @messages %{
    invalid_phone: "Phone must be in E.164 format, e.g. +94771234567",
    invalid_email: "Email address is invalid",
    rate_limited: "Too many requests, try again later",
    invalid_code: "The code is incorrect",
    expired: "The code has expired, request a new one",
    too_many_attempts: "Too many attempts, request a new code",
    invalid_token: "Missing, invalid or expired token",
    not_allowlisted: "This email is not on the RisiMe allowlist",
    identity_conflict: "This phone number is linked to another RisiCloud account",
    not_found: "Not found",
    phone_unverified: "Confirm your phone number to continue",
    already_verified: "Your phone number is already confirmed",
    invalid_name: "Name must be 1-64 characters",
    bad_request: "Bad request",
    mls_unavailable: "End-to-end encryption isn't available on this server yet",
    epoch_conflict: "The group moved on; catch up and retry",
    not_ready: "Not every device can use end-to-end encryption yet",
    not_friends: "You can only do this with friends",
    invalid_device: "Device registration is invalid",
    sms_unavailable: "Couldn't send the SMS right now, try again shortly",
    invalid_display_name: "Display name must be 1-64 characters",
    not_member: "You're not a member of this group",
    not_admin: "Only group admins can do that",
    too_many_members: "A group can have at most 256 members",
    too_many_devices: "A group can have at most 768 devices",
    last_admin: "Make another member an admin before you leave",
    invalid_role: "That member can't have this role",
    generation_conflict: "The group was already reset",
    rejoin_pending: "This device is waiting to be re-added",
    backup_unavailable: "Server backups are turned off",
    no_backup_key: "No backup key has been set up",
    backup_key_conflict: "Your backups use a different backup key",
    backup_device_mismatch: "Another phone is backing up this account",
    log_expired: "The commit log no longer reaches that epoch; reset or rejoin the group",
    too_large: "The upload is too large",
    quota_exceeded: "Blob storage quota exceeded",
    bad_media_type: "Blobs must be application/octet-stream",
    not_e2ee: "This chat isn't end-to-end encrypted yet",
    storage_full: "The server is low on storage; try again later",
    calls_unavailable: "Calls aren't available on this server yet",
    call_ended: "This call has ended",
    call_full: "This call is full",
    signup_required: "Create your RisiMe account to continue",
    signup_closed: "RisiMe sign-up is by invitation only right now",
    phone_taken: "This phone number can't be used for a new RisiMe account"
  }

  @doc """
  Sends `{"error": {"code", "message", ...extra}}`. Every 429 carries `Retry-After`
  (contract v1.4 §7.1): pass `retry_after: seconds` in `opts` (default 60).
  """
  def send_error(conn, status, code, opts \\ []) do
    message = Keyword.get(opts, :message) || Map.fetch!(@messages, code)
    %{error: error} = RisiMeWeb.ErrorJSON.error(code, message)
    error = Map.merge(error, Map.new(Keyword.get(opts, :extra, [])))

    conn =
      if status == 429,
        do:
          put_resp_header(
            conn,
            "retry-after",
            Integer.to_string(Keyword.get(opts, :retry_after, 60))
          ),
        else: conn

    conn
    |> put_status(status)
    |> json(%{error: error})
    |> halt()
  end
end
