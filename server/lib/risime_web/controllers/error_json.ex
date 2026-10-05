defmodule RisiMeWeb.ErrorJSON do
  @moduledoc """
  Renders errors in the contract shape: `{"error": {"code": "...", "message": "..."}}`.
  Used by the endpoint for unhandled errors and by controllers via `RisiMeWeb.ApiError`.
  """

  def render(template, _assigns) do
    message = Phoenix.Controller.status_message_from_template(template)
    code = message |> String.downcase() |> String.replace(~r/[^a-z0-9]+/, "_")
    error(code, message)
  end

  def error(code, message), do: %{error: %{code: to_string(code), message: message}}
end
