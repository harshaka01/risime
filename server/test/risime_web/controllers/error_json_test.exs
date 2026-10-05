defmodule RisiMeWeb.ErrorJSONTest do
  use RisiMeWeb.ConnCase, async: true

  test "renders 404 in the contract error shape" do
    assert RisiMeWeb.ErrorJSON.render("404.json", %{}) ==
             %{error: %{code: "not_found", message: "Not Found"}}
  end

  test "renders 500" do
    assert RisiMeWeb.ErrorJSON.render("500.json", %{}) ==
             %{error: %{code: "internal_server_error", message: "Internal Server Error"}}
  end
end
