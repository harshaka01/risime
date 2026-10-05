defmodule RisiMe.Accounts.Validate do
  @moduledoc "Phone (E.164) and email checks shared by the allowlist, the API and mix tasks."
  import Ecto.Changeset

  @phone ~r/^\+[1-9]\d{6,14}$/
  @email ~r/^[^\s@]+@[^\s@]+\.[^\s@]+$/

  def phone?(phone), do: is_binary(phone) and Regex.match?(@phone, phone)

  def email?(email),
    do: is_binary(email) and String.length(email) <= 254 and Regex.match?(@email, email)

  def normalize_email(email) when is_binary(email),
    do: email |> String.trim() |> String.downcase()

  def normalize_email(email), do: email

  def phone(changeset, field),
    do: validate_format(changeset, field, @phone, message: "must be E.164, e.g. +94771234567")

  def email(changeset, field),
    do:
      validate_change(changeset, field, fn _, v ->
        if email?(v), do: [], else: [{field, "is invalid"}]
      end)
end
