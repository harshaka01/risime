defmodule RisiMe.AccountsPruneTest do
  use RisiMe.DataCase, async: true

  import RisiMe.Fixtures

  alias RisiMe.Accounts
  alias RisiMe.Accounts.{OtpChallenge, UserToken}

  defp challenge!(phone, inserted_at) do
    Repo.insert!(%OtpChallenge{
      phone: phone,
      code_hash: :crypto.strong_rand_bytes(32),
      expires_at: DateTime.add(inserted_at, 300, :second),
      inserted_at: inserted_at
    })
  end

  defp token!(user, revoked_at) do
    Repo.insert!(%UserToken{
      user_id: user.id,
      token_hash: :crypto.strong_rand_bytes(32),
      revoked_at: revoked_at
    })
  end

  describe "prune_otp_challenges/1" do
    test "deletes challenges older than 24 h and keeps newer ones" do
      now = DateTime.utc_now()
      phone = unique_phone()
      old = challenge!(phone, DateTime.add(now, -25, :hour))
      recent = challenge!(phone, DateTime.add(now, -23, :hour))
      fresh = challenge!(phone, now)

      assert Accounts.prune_otp_challenges(now) == 1
      refute Repo.get(OtpChallenge, old.id)
      assert Repo.get(OtpChallenge, recent.id)
      assert Repo.get(OtpChallenge, fresh.id)
    end
  end

  describe "prune_revoked_tokens/1" do
    test "deletes tokens revoked over 30 days ago; keeps live and recently revoked ones" do
      %{user: user, token: live_token} = logged_in_user()
      now = DateTime.utc_now()
      old = token!(user, DateTime.add(now, -31, :day))
      recent = token!(user, DateTime.add(now, -29, :day))

      assert Accounts.prune_revoked_tokens(now) == 1
      refute Repo.get(UserToken, old.id)
      assert Repo.get(UserToken, recent.id)
      assert {_, _} = Accounts.fetch_by_token(live_token)
    end
  end

  describe "prune_phone_challenges/1" do
    test "deletes phone challenges older than 48 h" do
      %{user: user} = logged_in_user()
      now = DateTime.utc_now()

      mk = fn at ->
        Repo.insert!(%RisiMe.Accounts.PhoneChallenge{
          user_id: user.id,
          phone: user.phone,
          code_hash: :crypto.strong_rand_bytes(32),
          expires_at: DateTime.add(at, 300, :second),
          inserted_at: at
        })
      end

      old = mk.(DateTime.add(now, -49, :hour))
      recent = mk.(DateTime.add(now, -47, :hour))

      assert Accounts.prune_phone_challenges(now) == 1
      refute Repo.get(RisiMe.Accounts.PhoneChallenge, old.id)
      assert Repo.get(RisiMe.Accounts.PhoneChallenge, recent.id)
    end
  end
end
