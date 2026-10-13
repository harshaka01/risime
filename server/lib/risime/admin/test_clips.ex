defmodule RisiMe.Admin.TestClips do
  @moduledoc """
  The dev test clips of the Speech models page's "Test" button (decision 089): audio files
  directly inside `RISI_ADMIN_TEST_CLIPS_DIR` (e.g. the repo's `infra/whisper/testdata`).
  **Never customer voice notes:** the console reads only this directory, only regular files
  with an audio extension at its top level (no subdirectories, no symlinks), chosen by exact
  name from the listing, at most 25 MB each.
  """
  @exts ~w(.ogg .opus .wav .mp3 .m4a .flac .webm)
  @max_bytes 25 * 1024 * 1024

  @doc "The configured directory, or nil."
  def dir do
    case Application.get_env(:risime, :admin_test_clips_dir) do
      d when is_binary(d) and d != "" -> d
      _ -> nil
    end
  end

  @doc "The clip names (sorted), `[]` without a directory."
  def list do
    with d when is_binary(d) <- dir(),
         {:ok, names} <- File.ls(d) do
      names
      |> Enum.filter(&(String.downcase(Path.extname(&1)) in @exts))
      |> Enum.filter(&regular?(Path.join(d, &1)))
      |> Enum.sort()
      |> Enum.take(200)
    else
      _ -> []
    end
  end

  @doc "The bytes of clip `name` (must be in `list/0`)."
  def read(name) when is_binary(name) do
    if name in list() do
      path = Path.join(dir(), name)

      case File.stat(path) do
        {:ok, %{size: s}} when s <= @max_bytes -> File.read(path)
        {:ok, _} -> {:error, :too_large}
        e -> e
      end
    else
      {:error, :unknown_clip}
    end
  end

  def read(_), do: {:error, :unknown_clip}

  defp regular?(path) do
    match?({:ok, %File.Stat{type: :regular}}, File.lstat(path))
  end
end
