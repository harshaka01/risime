defmodule RisiMe.Repo do
  use Ecto.Repo,
    otp_app: :risime,
    adapter: Ecto.Adapters.Postgres
end
