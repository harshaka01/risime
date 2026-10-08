defmodule RisiMe.Repo.Migrations.UsersSignupSource do
  use Ecto.Migration

  # Contract v1.20 §21 (decision 058): how a user joined. `open` for open sign-up (a
  # self-asserted phone); null for every earlier user (allowlist or invite). Additive. The index
  # serves the global 24-hour sign-up cap.
  def change do
    alter table(:users) do
      add :signup_source, :string
    end

    create index(:users, [:inserted_at],
             where: "signup_source = 'open'",
             name: :users_open_signup_inserted_at_index
           )
  end
end
