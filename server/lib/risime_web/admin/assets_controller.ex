defmodule RisiMeWeb.Admin.AssetsController do
  @moduledoc """
  The admin console's one script (decision 089): Phoenix's and LiveView's prebuilt bundles
  (from the release's own `phoenix` and `phoenix_live_view` apps, no asset pipeline) plus the
  few lines that connect the LiveView socket at /admin/live. Built once per node.
  """
  use RisiMeWeb, :controller

  @init """
  ;(function(){
    var meta = document.querySelector("meta[name='csrf-token']");
    if (!meta) return;
    var liveSocket = new LiveView.LiveSocket("/admin/live", Phoenix.Socket, {
      params: {_csrf_token: meta.getAttribute("content")}
    });
    liveSocket.connect();
    window.liveSocket = liveSocket;
  })();
  """

  def app_js(conn, _params) do
    conn
    |> put_resp_content_type("text/javascript")
    |> put_resp_header("cache-control", "public, max-age=3600")
    |> put_resp_header("x-content-type-options", "nosniff")
    |> send_resp(200, bundle())
  end

  defp bundle do
    case :persistent_term.get({__MODULE__, :js}, nil) do
      nil ->
        js =
          [
            File.read!(Application.app_dir(:phoenix, "priv/static/phoenix.min.js")),
            "\n",
            File.read!(
              Application.app_dir(:phoenix_live_view, "priv/static/phoenix_live_view.min.js")
            ),
            "\n",
            @init
          ]
          |> IO.iodata_to_binary()

        :persistent_term.put({__MODULE__, :js}, js)
        js

      js ->
        js
    end
  end
end
