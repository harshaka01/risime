# Fixtures for scripts/interop (run with `mix run` in server/, dev env, shared dev DB).
# Creates throwaway users (+9477000092x dev-token users, +9477000091x JWT users, all "ZZ Interop"),
# a stand-in OIDC issuer key + JWKS (served by scripts/interop on 127.0.0.1:4799), Keycloak-shaped
# tokens, and writes the config JSON the Android LiveInteropTest reads. `cleanup` removes it all.
alias RisiMe.{Accounts, Repo}
alias RisiMe.Accounts.{AllowlistEntry, User, UserToken}
import Ecto.Query

[mode, dir] = System.argv() |> Enum.take(2) |> then(fn a -> a ++ List.duplicate(nil, 2 - length(a)) end)
phones = ~w(+94770000921 +94770000922 +94770000911 +94770000912)

case mode do
  "cleanup" ->
    {u, _} = Repo.delete_all(from x in User, where: x.phone in ^phones)
    {a, _} = Repo.delete_all(from x in AllowlistEntry, where: x.phone in ^phones)
    IO.puts("interop cleanup: users=#{u} allowlist=#{a}")

  "setup" ->
    iss = "http://127.0.0.1:4799/realms/itest"
    jwk = JOSE.JWK.generate_key({:rsa, 2048}) |> JOSE.JWK.merge(%{"kid" => "itest-1", "use" => "sig", "alg" => "RS256"})
    {_, pub} = jwk |> JOSE.JWK.to_public() |> JOSE.JWK.to_map()
    certs = Path.join(dir, "www/realms/itest/protocol/openid-connect/certs")
    File.mkdir_p!(Path.dirname(certs))
    File.write!(certs, Jason.encode!(%{"keys" => [pub]}))

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
    File.write!(Path.join(dir, "interop.json"), Jason.encode!(cfg))
    IO.puts("interop setup: #{Path.join(dir, "interop.json")}")
end
