import Config

# Dev and test read secrets from marketplace/.env (gitignored). Variables already set in the
# environment win. Prod gets its environment from the systemd unit's EnvironmentFile.
if config_env() in [:dev, :test] do
  env_file = Path.expand("../.env", __DIR__)

  if File.exists?(env_file) do
    env_file
    |> File.read!()
    |> String.split("\n")
    |> Enum.each(fn line ->
      line = String.trim(line)

      with false <- line == "" or String.starts_with?(line, "#"),
           [key, value] <- String.split(line, "=", parts: 2) do
        key = key |> String.trim() |> String.trim_leading("export ")
        value = value |> String.trim() |> String.trim("\"") |> String.trim("'")
        if System.get_env(key) == nil, do: System.put_env(key, value)
      end
    end)
  end
end

config :risi_registry, RisiRegistry.Repo,
  username: System.get_env("REGISTRY_DB_USER", "risi_registry"),
  password: System.get_env("REGISTRY_DB_PASSWORD")

# The first allow-list entry comes only from the environment, never from code or docs.
config :risi_registry, :allow_list_seed, System.get_env("ALLOW_LIST_SEED")

oidc_env =
  [
    issuer: System.get_env("OIDC_ISSUER"),
    client_id: System.get_env("OIDC_CLIENT_ID"),
    redirect_uri: System.get_env("OIDC_REDIRECT_URI")
  ]
  |> Enum.reject(fn {_k, v} -> v in [nil, ""] end)

if config_env() != :test and oidc_env != [] do
  config :risi_registry,
         :oidc,
         Keyword.merge(Application.get_env(:risi_registry, :oidc), oidc_env)
end

if config_env() == :dev do
  config :risi_registry, RisiRegistryWeb.Endpoint,
    secret_key_base:
      System.get_env("SECRET_KEY_BASE") ||
        raise("SECRET_KEY_BASE is missing from marketplace/.env (mix phx.gen.secret)")
end

if config_env() == :prod do
  config :risi_registry, RisiRegistry.Repo,
    hostname: System.get_env("REGISTRY_DB_HOST", "127.0.0.1"),
    database: System.get_env("REGISTRY_DB_NAME", "risi_registry_prod"),
    pool_size: String.to_integer(System.get_env("POOL_SIZE", "5"))

  config :risi_registry, RisiRegistryWeb.Endpoint,
    # Always loopback; a reverse proxy (or a LAN-only listener agreed in STATUS.md) sits in front.
    http: [ip: {127, 0, 0, 1}, port: String.to_integer(System.get_env("PORT", "4600"))],
    url: [host: System.get_env("PUBLIC_HOST", "risicloud.ai"), scheme: "https", port: 443],
    secret_key_base:
      System.get_env("SECRET_KEY_BASE") ||
        raise("SECRET_KEY_BASE is missing from the environment")
end
