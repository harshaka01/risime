defmodule RisiMe.Repo.Migrations.RisiToolCallsSources do
  use Ecto.Migration

  # v1.31 §31.4: a `calendar_check` may ask for `args.sources`; the result's `sources` must be
  # exactly these. Source names only (no args, no calendars). Additive.
  def change do
    alter table(:risi_tool_calls) do
      add :sources, {:array, :text}
    end
  end
end
