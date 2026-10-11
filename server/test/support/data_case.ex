defmodule RisiMe.DataCase do
  @moduledoc """
  This module defines the setup for tests requiring
  access to the application's data layer.

  You may define functions here to be used as helpers in
  your tests.

  Finally, if the test case interacts with the database,
  we enable the SQL sandbox, so changes done to the database
  are reverted at the end of every test. If you are using
  PostgreSQL, you can even run database tests asynchronously
  by setting `use RisiMe.DataCase, async: true`, although
  this option is not recommended for other databases.
  """

  use ExUnit.CaseTemplate

  using do
    quote do
      alias RisiMe.Repo

      import Ecto
      import Ecto.Changeset
      import Ecto.Query
      import RisiMe.DataCase
    end
  end

  setup tags do
    RisiMe.DataCase.setup_sandbox(tags)
    :ok
  end

  @doc """
  Sets up the sandbox based on the test tags.
  """
  def setup_sandbox(tags) do
    pid = Ecto.Adapters.SQL.Sandbox.start_owner!(RisiMe.Repo, shared: not tags[:async])

    on_exit(fn ->
      # Fire-and-forget work (push sends, call jobs) is started under these supervisors and
      # reads the database. If the owner stops first, Postgrex drops the connection under the
      # task's in-flight query ("owner exited ... still using a connection"), which crashes the
      # task and can cancel a query that a following test's process shares. So let it finish.
      drain_tasks([RisiMe.Push.TaskSupervisor, RisiMe.Calls.TaskSupervisor])
      Ecto.Adapters.SQL.Sandbox.stop_owner(pid)
    end)
  end

  # Waits (bounded) until the supervisors have no running children.
  defp drain_tasks(sups, deadline \\ System.monotonic_time(:millisecond) + 2_000) do
    busy? = Enum.any?(sups, fn s -> Process.whereis(s) && Task.Supervisor.children(s) != [] end)

    if busy? and System.monotonic_time(:millisecond) < deadline do
      Process.sleep(10)
      drain_tasks(sups, deadline)
    else
      :ok
    end
  end

  @doc """
  A helper that transforms changeset errors into a map of messages.

      assert {:error, changeset} = Accounts.create_user(%{password: "short"})
      assert "password is too short" in errors_on(changeset).password
      assert %{password: ["password is too short"]} = errors_on(changeset)

  """
  def errors_on(changeset) do
    Ecto.Changeset.traverse_errors(changeset, fn {message, opts} ->
      Regex.replace(~r"%{(\w+)}", message, fn _, key ->
        opts |> Keyword.get(String.to_existing_atom(key), key) |> to_string()
      end)
    end)
  end
end
