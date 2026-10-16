defmodule RisiRegistry.Cards.JCSTest do
  use ExUnit.Case, async: true
  alias RisiRegistry.Cards.JCS

  test "RFC 8785 section 3.2.2 example: numbers, strings, literals" do
    input =
      "{\"numbers\":[333333333.33333329,1E30,4.50,2e-3,0.000000000000000000000000001],\"string\":\"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\",\"literals\":[null,true,false]}"

    expected =
      ~S({"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],"string":"€$\u000f\nA'B\"\\\\\"/"})

    assert JCS.encode!(Jason.decode!(input)) == expected
  end

  test "RFC 8785 section 3.2.3: keys sort by UTF-16 code units" do
    input =
      ~S({"€":"Euro Sign","\r":"Carriage Return","דּ":"Hebrew Letter Dalet With Dagesh","1":"One","😀":"Emoji: Grinning Face","\u0080":"Control","ö":"Latin Small Letter O With Diaeresis"})

    values =
      input
      |> Jason.decode!()
      |> JCS.encode!()
      |> Jason.decode!(objects: :ordered_objects)
      |> Map.get(:values)
      |> Enum.map(fn {_k, v} -> v end)

    assert values == [
             "Carriage Return",
             "One",
             "Control",
             "Latin Small Letter O With Diaeresis",
             "Euro Sign",
             "Emoji: Grinning Face",
             "Hebrew Letter Dalet With Dagesh"
           ]
  end

  test "ECMAScript number formatting" do
    cases = [
      {0.0, "0"},
      {-0.0, "0"},
      {1.0, "1"},
      {-1.5, "-1.5"},
      {1.0e21, "1e+21"},
      {1.0e20, "100000000000000000000"},
      {1.0e-7, "1e-7"},
      {1.0e-6, "0.000001"},
      {123.456, "123.456"},
      {5.0e-324, "5e-324"},
      {1.7976931348623157e308, "1.7976931348623157e+308"},
      {0.1 + 0.2, "0.30000000000000004"},
      {9_007_199_254_740_992, "9007199254740992"},
      {42, "42"}
    ]

    for {value, text} <- cases do
      assert JCS.encode!(value) == text, "#{inspect(value)} should be #{text}"
    end
  end

  test "no whitespace, nested objects sorted, control characters escaped" do
    assert JCS.encode!(%{"b" => [1, %{"z" => 1, "a" => "x\ty"}], "a" => nil}) ==
             ~S({"a":null,"b":[1,{"a":"x\ty","z":1}]})

    assert JCS.encode!("\u0001") == ~S("\u0001")
  end

  test "unsupported values are refused" do
    assert {:error, _} = JCS.encode(%{"a" => {:tuple}})
  end
end
