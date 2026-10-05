defmodule RisiMe.VersionTest do
  use ExUnit.Case, async: true

  test "the app version comes from the repo VERSION file" do
    expected = Path.expand("../../../VERSION", __DIR__) |> File.read!() |> String.trim()
    assert RisiMe.Application.version() == expected
  end
end
