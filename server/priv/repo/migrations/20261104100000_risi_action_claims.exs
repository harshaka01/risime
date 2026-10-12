defmodule RisiMe.Repo.Migrations.RisiActionClaims do
  use Ecto.Migration

  # Contract v1.43 §38.9 point 1: an unowned promise/task/meeting is kept as a
  # risi_action_extras row until a member claims it ("It's mine"); the claim turns it into the
  # claimer's proposed ledger item. The row therefore keeps what the item needs then: its due
  # (and whether all-day), the due phrase (sealed like `text`, AAD
  # `risi_action_extras:<id>:due_text`), its counterparts and the model's confidence. Additive
  # only (hard rule 9); older rows have NULLs (an undated item: Risi asks for the date).
  def change do
    alter table(:risi_action_extras) do
      add :due, :utc_datetime_usec
      add :all_day, :boolean
      add :due_text_sealed, :binary
      add :counterpart_ids, {:array, :uuid}
      add :confidence, :float
    end
  end
end
