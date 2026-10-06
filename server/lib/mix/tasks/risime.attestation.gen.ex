defmodule Mix.Tasks.Risime.Attestation.Gen do
  @shortdoc "Creates the E2EE attestation key file (once; refuses to overwrite)"
  @moduledoc """
      mix risime.attestation.gen [path]   # default: ATTESTATION_KEY_FILE or ~/risime-keys/attestation_ed25519.jwk

  Writes an Ed25519 private JWK with mode 600 and prints only its kid. Creating it turns E2EE on
  for a server that reads it (decision 034); root does this at rollout.
  """
  use Mix.Task

  @impl true
  def run(args) do
    Mix.Task.run("app.config")

    path =
      List.first(args) || Application.get_env(:risime, :attestation_key_file) ||
        Path.expand("~/risime-keys/attestation_ed25519.jwk")

    case RisiMe.MLS.Attestation.generate(path) do
      {:ok, kid} -> Mix.shell().info("attestation key created (kid #{kid}) at #{path}")
      {:error, :exists} -> Mix.raise("#{path} exists; refusing to overwrite")
    end
  end
end
