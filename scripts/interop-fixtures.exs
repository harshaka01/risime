# Fixtures for scripts/interop (run with `mix run` in server/, dev env, shared dev DB).
# Creates throwaway users (+9477000092x dev-token users, +9477000091x JWT users, all "ZZ Interop"),
# a stand-in OIDC issuer key + JWKS (served by scripts/interop on 127.0.0.1:4799), Keycloak-shaped
# tokens, and writes the config JSON the Android LiveInteropTest reads. `cleanup` removes it all.
alias RisiMe.{Accounts, Repo}
alias RisiMe.Accounts.{AllowlistEntry, User, UserToken}
import Ecto.Query

[mode, dir] = System.argv() |> Enum.take(2) |> then(fn a -> a ++ List.duplicate(nil, 2 - length(a)) end)
phones = ~w(+94770000921 +94770000922 +94770000911 +94770000912 +94770000913 +94770000914 +94770000915 +94770000916 +94770000931 +94770000932 +94770000933 +94770000941 +94770000942 +94770000943 +94770000944 +94770000945)

# Removes every interop fixture: users (cascading devices, tokens, friendships, invites...),
# allowlist rows, MLS group rows (keyed by conversation id, not linked to users), and v1.9
# `grp:` groups the users are in (their MLS rows, members, ops) plus their blobs (rows and files).
purge = fn ->
  ids = Repo.all(from x in User, where: x.phone in ^phones, select: x.id)
  pats = Enum.map(ids, &("%" <> &1 <> "%"))
  if pats != [] do
    for t <- ~w(mls_commits mls_group_devices mls_groups),
        do: Repo.query!("DELETE FROM #{t} WHERE conversation_id LIKE ANY($1)", [pats])
    uids = Enum.map(ids, &Ecto.UUID.dump!/1)
    %{rows: g} =
      Repo.query!("SELECT id FROM groups WHERE created_by = ANY($1) UNION SELECT group_id FROM group_members WHERE user_id = ANY($1)", [uids])
    gids = List.flatten(g)
    for t <- ~w(mls_commits mls_group_devices mls_groups),
        do: Repo.query!("DELETE FROM #{t} WHERE conversation_id = ANY($1)", [gids])
    Repo.query!("DELETE FROM groups WHERE id = ANY($1)", [gids])
    %{rows: b} = Repo.query!("SELECT id FROM blobs WHERE owner = ANY($1)", [uids])
    for [raw] <- b, id = Ecto.UUID.load!(raw), do: File.rm(Path.join([RisiMe.Blobs.blob_dir(), String.slice(id, 0, 2), id]))
    Repo.query!("DELETE FROM blobs WHERE owner = ANY($1)", [uids])
  end
  {u, _} = Repo.delete_all(from x in User, where: x.phone in ^phones)
  {a, _} = Repo.delete_all(from x in AllowlistEntry, where: x.phone in ^phones)
  {u, a}
end

case mode do
  "cleanup" ->
    {u, a} = purge.()
    IO.puts("interop cleanup: users=#{u} allowlist=#{a}")

  "keys" ->
    # Before the server starts: stand-in issuer key + JWKS (the server fetches it at boot).
    jwk = JOSE.JWK.generate_key({:rsa, 2048}) |> JOSE.JWK.merge(%{"kid" => "itest-1", "use" => "sig", "alg" => "RS256"})
    {_, pub} = jwk |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()
    {_, priv} = JOSE.JWK.to_map(jwk)
    certs = Path.join(dir, "www/realms/itest/protocol/openid-connect/certs")
    File.mkdir_p!(Path.dirname(certs))
    File.write!(certs, Jason.encode!(%{"keys" => [pub]}))
    File.write!(Path.join(dir, "key.json"), Jason.encode!(priv))
    File.chmod!(Path.join(dir, "key.json"), 0o600)
    IO.puts("interop keys: ok")

  "setup" ->
    # A killed earlier run skips its cleanup; never reuse its users (stale MLS groups, friendships).
    {su, sa} = purge.()
    if su + sa > 0, do: IO.puts("interop setup: purged leftovers users=#{su} allowlist=#{sa}")
    iss = "http://127.0.0.1:4799/realms/itest"
    jwk = dir |> Path.join("key.json") |> File.read!() |> Jason.decode!() |> JOSE.JWK.from_map()

    dev_user = fn phone, name ->
      {:ok, e} = Accounts.allow(%{phone: phone, email: "interop#{String.slice(phone, -2, 2)}@example.com", display_name: name, company: "CodeGen"})
      u = Repo.get_by(User, phone: phone) || Repo.insert!(%User{phone: e.phone, email: e.email, display_name: e.display_name, company: e.company})
      t = :crypto.strong_rand_bytes(32) |> Base.url_encode64(padding: false)
      Repo.insert!(%UserToken{user_id: u.id, token_hash: :crypto.hash(:sha256, t), device_name: "interop"})
      %{"id" => u.id, "token" => t}
    end

    a = dev_user.("+94770000921", "ZZ Interop A")
    b = dev_user.("+94770000922", "ZZ Interop B")
    {:ok, _} = Accounts.allow(%{phone: "+94770000911", email: "itest-a@example.com", display_name: "ZZ Interop JA", company: "CodeGen"})
    {:ok, _} = Accounts.allow(%{phone: "+94770000912", email: "itest-b@example.com", display_name: "ZZ Interop JB", company: "CodeGen"})

    now = System.system_time(:second)
    mint = fn sub, email, ttl ->
      claims = %{"iss" => iss, "sub" => sub, "azp" => "risime", "aud" => "account", "typ" => "Bearer",
                 "email" => email, "email_verified" => true, "iat" => now, "exp" => now + ttl,
                 "scope" => "openid email profile offline_access"}
      {_, t} = JOSE.JWT.sign(jwk, %{"alg" => "RS256", "kid" => "itest-1", "typ" => "JWT"}, claims) |> JOSE.JWS.compact()
      t
    end

    cfg = %{
      "url" => "http://127.0.0.1:4100",
      "dev" => %{"a" => a, "b" => b},
      "jwt" => %{
        "A" => mint.("sub-itest-a", "itest-a@example.com", 900),
        "A2" => mint.("sub-itest-a", "itest-a@example.com", 1200),
        "B" => mint.("sub-itest-b", "itest-b@example.com", 900),
        "Bshort" => mint.("sub-itest-b", "itest-b@example.com", 60),
        "Z" => mint.("sub-itest-z", "itest-z@example.com", 900)
      },
      "bshort_exp" => now + 60
    }
    # v1.6 friends flow: A and C allowlisted (not friends), I invited (no allowlist), P_X unused.
    {:ok, _} = Accounts.allow(%{phone: "+94770000913", email: "itest-fa@example.com", display_name: "ZZ Interop FA", company: "CodeGen"})
    {:ok, _} = Accounts.allow(%{phone: "+94770000914", email: "itest-fc@example.com", display_name: "ZZ Interop FC", company: "CodeGen"})
    jwt_user = fn sub, email, phone ->
      t = mint.(sub, email, 900)
      # First-use mapping creates the user; we need its id for the test.
      {:ok, %{status: 200, body: %{"user" => u}}} =
        Req.get("http://127.0.0.1:4100/api/v1/me", headers: [{"authorization", "Bearer " <> t}], retry: false)
      %{"id" => u["id"], "jwt" => t, "phone" => phone}
    end
    friends = %{
      "A" => jwt_user.("sub-itest-fa", "itest-fa@example.com", "+94770000913"),
      "C" => jwt_user.("sub-itest-fc", "itest-fc@example.com", "+94770000914"),
      "I" => %{"jwt" => mint.("sub-itest-fi", "itest-fi@example.com", 900), "email" => "itest-fi@example.com",
               "phone" => "+94770000915", "name" => "Interop Invitee"},
      "P_X" => "+94770000916"
    }
    me_id = fn t ->
      {:ok, %{status: 200, body: %{"user" => u}}} =
        Req.get("http://127.0.0.1:4100/api/v1/me", headers: [{"authorization", "Bearer " <> t}], retry: false)
      u["id"]
    end
    # The base flow (chat, presence, typing, JWT path) runs between friends (v1.6).
    jwt_ids = %{"A" => me_id.(cfg["jwt"]["A"]), "B" => me_id.(cfg["jwt"]["B"])}
    RisiMe.Social.make_friends!(a["id"], b["id"])
    RisiMe.Social.make_friends!(jwt_ids["A"], jwt_ids["B"])
    # E2EE block (v1.7): fresh dev-token users; A–B and A–L friends; L stays a legacy client.
    ea = dev_user.("+94770000931", "ZZ Interop EA")
    eb = dev_user.("+94770000932", "ZZ Interop EB")
    el = dev_user.("+94770000933", "ZZ Interop EL")
    RisiMe.Social.make_friends!(ea["id"], eb["id"])
    RisiMe.Social.make_friends!(ea["id"], el["id"])
    # Groups block (v1.9): A is friends with B, C, D and L; B–C friends; D is not B's friend.
    # L also connects as a legacy app in the test (not group-ready).
    [ga, gb, gc, gd, gl] =
      for {p, n} <- [{"941", "GA"}, {"942", "GB"}, {"943", "GC"}, {"944", "GD"}, {"945", "GL"}],
          do: dev_user.("+94770000" <> p, "ZZ Interop " <> n)
    for {x, y} <- [{ga, gb}, {ga, gc}, {ga, gd}, {ga, gl}, {gb, gc}],
        do: RisiMe.Social.make_friends!(x["id"], y["id"])
    cfg =
      cfg
      |> Map.put("groups", %{"A" => ga, "B" => gb, "C" => gc, "D" => gd, "L" => gl})
      |> Map.put("e2ee", %{"A" => ea, "B" => eb, "L" => el})
      |> Map.put("friends", friends)
      |> Map.put("ids", jwt_ids)
    File.write!(Path.join(dir, "interop.json"), Jason.encode!(cfg))
    IO.puts("interop setup: #{Path.join(dir, "interop.json")}")
end
