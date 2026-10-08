defmodule RisiMe.Calls.LiveKit do
  @moduledoc """
  LiveKit for group calls (contract v1.19 §20.2, decision 056): configuration, room names,
  participant access tokens and the server's own API tokens.

  Configuration (`config :risime, :livekit`, from `config/runtime.exs`):

    * `url` — `LIVEKIT_URL`, the public `wss://` URL clients connect to
      (`wss://risime.risicloud.ai/livekit`);
    * `api_url` — `LIVEKIT_API_URL`, LiveKit's server API on loopback (default
      `http://127.0.0.1:7880`);
    * `api_key`, `api_secret` — `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`.

  Any of them missing → `{:error, :calls_unavailable}` (`503`). The secret signs every JWT and
  derives the room-name key; it is never logged.

  Tokens are HS256 JWTs (LiveKit's access-token format), hand-rolled here (no dependency): a
  participant token lives 600 s and grants exactly join + subscribe + publish (microphone, and
  camera for video); the server's API tokens live 60 s with only the grant the call needs.
  """

  @token_ttl_s 600
  @api_token_ttl_s 60
  @caps %{"audio" => 32, "video" => 8}

  @doc "Participant caps (§20.9): 32 for a voice call, 8 for a video call."
  def max_participants(media), do: Map.fetch!(@caps, media)

  @doc "Participant token lifetime in seconds."
  def token_ttl_s, do: @token_ttl_s

  @doc "`{:ok, %{url, api_url, api_key, api_secret}}` or `{:error, :calls_unavailable}`."
  def config do
    c = Application.get_env(:risime, :livekit, [])
    vals = for k <- [:url, :api_url, :api_key, :api_secret], into: %{}, do: {k, c[k]}

    if Enum.all?(vals, fn {_k, v} -> is_binary(v) and v != "" end),
      do: {:ok, vals},
      else: {:error, :calls_unavailable}
  end

  @doc "True if all four settings are present."
  def configured?, do: match?({:ok, _}, config())

  @doc """
  The room name (§20.2, server S2): the first 22 characters of
  `base64url(HMAC-SHA256(K_room, "risime-room-v1" ‖ conversation_id ‖ call_id ‖ media))`, no
  padding, with `K_room = HKDF-SHA256(salt = empty, IKM = secret, info = "risime-livekit-room-v1",
  L = 32)`.
  """
  def room_name(secret, conversation_id, call_id, media) do
    k_room = hkdf_sha256(secret, "risime-livekit-room-v1", 32)

    :crypto.mac(:hmac, :sha256, k_room, ["risime-room-v1", conversation_id, call_id, media])
    |> Base.url_encode64(padding: false)
    |> binary_part(0, 22)
  end

  @doc false
  # RFC 5869 with an empty salt (HMAC pads it to the same zero block as HashLen zeros).
  def hkdf_sha256(ikm, info, len) when len <= 32 do
    prk = :crypto.mac(:hmac, :sha256, "", ikm)
    t1 = :crypto.mac(:hmac, :sha256, prk, [info, 1])
    binary_part(t1, 0, len)
  end

  @doc "The participant identity `<user_id>/<device_id>` (the MLS leaf identity, §20.6)."
  def identity(user_id, device_id), do: user_id <> "/" <> device_id

  @doc """
  The claims of a participant token (`livekit_token_claims.json`): `iss` the API key, `sub` the
  identity, `name` empty, no metadata, `nbf` now, `exp` now + 600 s, and the `video` grant.
  """
  def participant_claims(api_key, identity, room, media, now) do
    sources = if media == "video", do: ["microphone", "camera"], else: ["microphone"]

    %{
      "iss" => api_key,
      "sub" => identity,
      "name" => "",
      "nbf" => now,
      "exp" => now + @token_ttl_s,
      "video" => %{
        "room" => room,
        "roomJoin" => true,
        "canSubscribe" => true,
        "canPublish" => true,
        "canPublishSources" => sources,
        "canPublishData" => false,
        "canUpdateOwnMetadata" => false
      }
    }
  end

  @doc "A signed participant token. Returns `{token, exp_unix}`."
  def participant_token(%{api_key: key, api_secret: secret}, identity, room, media, now) do
    claims = participant_claims(key, identity, room, media, now)
    {sign(claims, secret), claims["exp"]}
  end

  @doc """
  A 60-s token for one server API call with only `grant` (e.g. `%{"roomCreate" => true}`,
  `%{"roomList" => true}`, `%{"roomAdmin" => true, "room" => name}`).
  """
  def api_token(%{api_key: key, api_secret: secret}, grant, now \\ System.os_time(:second)) do
    sign(
      %{
        "iss" => key,
        "sub" => "risime-server",
        "nbf" => now,
        "exp" => now + @api_token_ttl_s,
        "video" => grant
      },
      secret
    )
  end

  @doc "An HS256 JWT over `claims`."
  def sign(claims, secret) do
    header = b64url(Jason.encode!(%{"alg" => "HS256", "typ" => "JWT"}))
    payload = b64url(Jason.encode!(claims))
    input = header <> "." <> payload
    input <> "." <> b64url(:crypto.mac(:hmac, :sha256, secret, input))
  end

  @doc "Verifies an HS256 JWT and returns its claims (tests and the integration check)."
  def verify(token, secret) do
    with [h, p, s] <- String.split(token, "."),
         {:ok, sig} <- Base.url_decode64(s, padding: false),
         true <- :crypto.hash_equals(sig, :crypto.mac(:hmac, :sha256, secret, h <> "." <> p)),
         {:ok, header} <- Base.url_decode64(h, padding: false),
         %{"alg" => "HS256"} <- Jason.decode!(header),
         {:ok, json} <- Base.url_decode64(p, padding: false) do
      {:ok, Jason.decode!(json)}
    else
      _ -> :error
    end
  end

  defp b64url(bin), do: Base.url_encode64(bin, padding: false)

  @doc "The configured server API client (a behaviour; a fake in tests)."
  def api, do: Application.get_env(:risime, :livekit_api, RisiMe.Calls.LiveKit.Twirp)
end
