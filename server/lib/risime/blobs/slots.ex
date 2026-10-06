defmodule RisiMe.Blobs.Slots do
  @moduledoc """
  Upload and download concurrency per user (contract v1.11 §14.2, §14.8), counted in a
  duplicate-key `Registry` bound to the request process: a slot is an entry registered by that
  process, released explicitly after the response (HTTP/1.1 keep-alive reuses the process) and
  automatically when the process dies (a client gone mid-upload), so no counter can leak.

  An upload slot's value is its declared `Content-Length`, so `inflight_bytes/0` gives the
  bytes still to land on disk (the free-space guard subtracts them). One node, so this is enough.
  """

  @registry __MODULE__

  def child_spec(_opts), do: Registry.child_spec(keys: :duplicate, name: @registry)

  @doc "Takes a `kind` (`:up` | `:down`) slot for `user`, at most `limit` at once."
  def acquire(kind, user, limit, bytes \\ 0) do
    key = {kind, user}
    {:ok, _} = Registry.register(@registry, key, bytes)

    if Registry.count_match(@registry, key, :_) > limit do
      Registry.unregister(@registry, key)
      {:error, {:rate_limited, 2}}
    else
      :ok
    end
  end

  @doc "Releases this process's `kind` slots for `user`."
  def release(kind, user), do: Registry.unregister(@registry, {kind, user})

  @doc "Slots of `kind` held for `user` (tests, ops)."
  def count(kind, user), do: Registry.count_match(@registry, {kind, user}, :_)

  @doc "The declared bytes of every upload in flight."
  def inflight_bytes do
    @registry
    |> Registry.select([{{{:up, :_}, :_, :"$1"}, [], [:"$1"]}])
    |> Enum.sum()
  end
end
