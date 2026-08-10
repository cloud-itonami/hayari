# hayari

**hayari（流行）は、どの国のいま何が見られているかを観測し、その作品がどこの国で
いつ作られたものかを突き合わせる observatory である。** 名前が機能を示さないので
最初に名乗る（superproject `CLAUDE.md` の規約: メタファ名の repo は README 冒頭で
名乗る）。`cloud-itonami/hayari` は west project で、`orgs/cloud-itonami/hayari` に
展開される。

答えられる問いは 1 つ:

> **いま、ある国の公衆の注意はどの作品に向いていて、それはどこの国の、いつの時代の
> 作品か。**

## 測れるもの・測れないもの

この repo が存在する理由の半分は、後者を**データとして**書き出すことにある。

| 軸 | 出せるか | 出所 |
|---|---|---|
| **国** | ○ | Wikimedia の per-country pageview 集計（ISO 3166-1 alpha-2） |
| **地域** | ○ | 国 → UN M49 region / sub-region（`data/m49-regions.edn`） |
| **作品の種別** | ○ | Wikidata P31 → `data/kinds.edn`（anime / TV drama / film / manga / game / …） |
| **作品の年（1年単位）** | ○ | Wikidata P577 の**最初の**公開日。無ければ P571 → P1191 → P580 の順に落とす（どれが答えたかを `:hayari/dated-via` に残す） |
| **作品の年代（10年単位）** | ○ | 上の年から導出。年と年代の**両方**を出す |
| **原産国** | ○ | Wikidata P495（QID のまま。join key として保つ） |
| **視聴者の世代** | **×** | **どの source も持っていない** |

**視聴者の世代は `:uncomputable-until-measured` として毎回 coverage に書き出す。**
Wikimedia の per-country endpoint は集計済み・プライバシーフィルタ済みで閲覧者の
年齢を持たず、Wikidata は作品を記述するもので視聴者を記述しない。作品の公開年代を
バケットに分けて「世代」と呼ぶことはできるが、それは**別のものを測って世代と名付けた**
だけになる。superproject `CLAUDE.md` の system-dynamics 規則が禁じているのはこれで、
未計測の変換率を大きな pool に掛けて期待値を捏造しないのと同じ形をしている。

作品側の年代（`:hayari/work-era`）は出す。これは作品の性質であって、観測できる。

## カバレッジは「対象外」ではない

2026-08-10 の実測で、per-country endpoint の応答量は国によって桁が違う:

```
US 1000 articles   IN 1000   DE 654   FR 449   BR 215   ID 86   KR 30   NG 12   EG 404(データ無し)
```

これは Wikimedia のプライバシー閾値（一定回数を超えた記事だけが出る）の結果であって、
その国に文化が無いという意味ではない。したがって:

- **roster は既定で M49 の全 249 国**。国を選んで絞り込むことはできる（`--countries`）が、
  既定は絞らない。「どの entity も原理上排除しないモデルを作る」という規則の適用。
- **データが返らなかった国は `:countries/no-data` に名前で残す**。集計から消えない。
- **counts を引用する前に coverage entity（`:db/id -1`）を読む**。出力の 1 件目に
  置いてあるのはそのため。

## 出力

`data/hayari.datoms.edn` — workspace の datom 面（`manifest/edn-query.cljs`、
`:source/dataset "hayari"`）にそのまま載る tx-data。

```clojure
{:db/id -3
 :source/dataset            "hayari"
 :hayari/observed-on        "2026-08-07"
 :hayari/country-iso2       "JP"
 :hayari/region-m49         "142"   :hayari/region-name    "Asia"
 :hayari/subregion-m49      "030"   :hayari/subregion-name "Eastern Asia"
 :hayari/rank               3
 :hayari/views              98100
 :hayari/attention-share    0.0236
 :hayari/article            "借りぐらしのアリエッティ"
 :hayari/project            "ja.wikipedia"
 :hayari/wikidata-qid       "Q699835"
 :hayari/kind               :anime/film
 :hayari/work-era           2010
 :hayari/origin-country-qid "Q17"
 :hayari/cross-country-lift 1.0
 :hayari/observed-in-countries 1}
```

`:hayari/views` は Wikimedia が**プライバシーのため切り上げた**値（API の
`views_ceil`）であって正確な閲覧数ではない。`:hayari/attention-share` の分母は
**その国のその日の観測合計**で、世界合計ではない（そんな値はこの source に無いので
作らない）。

`:hayari/cross-country-lift` には必ず `:hayari/observed-in-countries` が付く。
2 か国から出した lift と 90 か国から出した lift は違う主張で、分母が見えない
consumer は同じものとして読んでしまう。

**`:countries/responded` と `:countries/with-rows` を混同しない。** 2026-08-08 の実測で
前者は 101、後者は 66 だった。差の 35 国は 200 を返したが、閾値を超えた記事が
navigation だけで観測行にならなかった —— **feed には居るが findings には居ない**。
reach を語るときは後者を引く。

## 観測は蓄積する（上書きしない）

実行のたびに `data/hayari.datoms.edn` へ**溶け込ませる**。同じ日を測り直せばその日を
置き換え、新しい日は足す。1 日のスナップショットからは変化が読めず、変化こそが
stock-flow モデルの主題なので、日が互いを消してはならない。

⚠ **この出力は .gitignore 済み**（observatory 族の既定、`:output-gitignored true`）。
つまり履歴は**走らせたマシンの中にしか無い**。年単位の恒久的な記録には置き場所の
決定が要る（DataLad dataset か `90-docs/observatory/` の投影か）。決まるまで
**年次の履歴は存在しない**——MATURITY.md にそう書いてある。

## XMILE — 注目の減衰を system dynamics として計算する

注目は stock である。溜まり、抜けていく。その**抜ける速さ**が作品について知りたい
ことで、`Spider-Man` と祝日は同じようには減衰しない。これは system dynamics の問いなので、
専用の計算式を作らず、system dynamics が既に持つ交換形式で書く。

```
Attention(0)   = v0
Decay          = Attention · decay_rate
d/dt Attention = −Decay
```

**シミュレータはこの repo に無い。** 方程式言語・検証器・Euler/RK4 積分器は
`kotoba-lang/org-oasis-open-xmile` が持っており、`dynamics.xmile` の docstring が
その再実装を名指しで禁じている。ここがやるのはモデルを組んで渡すことだけ。
当てはめ（`hayari.core/estimate-decay`）は ln V の最小二乗で、XMILE ライブラリに
依存しない純粋な算術なので、兄弟 checkout 無しでテストできる。

```
λ=0.5745  half-life=1.21d  r²=0.968  MAPE=9.9%  n=4  Abdul_El-Sayed [:person]
λ=-0.0713 half-life=growing r²=0.824 MAPE=3.3%  n=4  杀人者的购物中心 [:tv/series]
```

- **3 点未満は当てはめを拒否する。** 2 点なら直線は必ず完全に通り、r²=1 は
  持っていない確信のように読める。
- **λ が負なら注目は増えていた。** 負の half-life は数字に見えて意味が無いので
  出さない（`growing` と書く）。
- **MAPE は in-sample。** 当てはめた日を再現するかを言うだけで、予測の主張ではない。
  出力ファイルの冒頭にもそう書いてある。

## 走らせる

```bash
nbb src/hayari/collect.cljs                       # 既定: 2 日前・249 国・top 25・予算 480s
nbb src/hayari/collect.cljs --days 7              # 7 日ぶんを 1 回で（時系列を作る）
nbb src/hayari/simulate.cljs                      # 減衰を XMILE で当てはめて回す
nbb --classpath src:test test/hayari/core_test.cljs   # 決定核（外部依存なし）
nbb scripts/gen_regions.cljs                      # 地域表の再生成
```

**`--classpath` は要らない。** 両エントリは `*file*` から自分の `src` を解決する
（`nbb.classpath/add-classpath`）。登録簿の runner は `nbb <main> <args>` の形で
起動し、`--classpath` はスクリプト名より前でないと効かないので、**引数では直せない**。

手順と実際の出力は [`docs/operator-quickstart.md`](docs/operator-quickstart.md)、
現在地は [`MATURITY.md`](MATURITY.md)。

source は 3 つとも公開・無認証（2026-08-10 実測）:

1. Wikimedia Analytics `top-per-country` — 国ごとの注目
2. MediaWiki `action=query&prop=pageprops` — 記事名 → Wikidata QID
3. Wikidata `action=wbgetentities&props=claims` — P31 / P577 / P495

**Wikidata の SPARQL endpoint は使っていない。** 実測で 502 を返した。落ちる endpoint に
依存した observatory は「静かに収集を止める」という、`manifest/observatories.edn` が
作られた原因そのものの故障をする。

## 構造

```
src/hayari/core.cljc      決定核（純粋・I/O 無し・外部依存なし）— 判断はここだけ
src/hayari/collect.cljs   effects（nbb）— network / clock / fs はここだけ
src/hayari/xmile.cljc     XMILE モデルの組み立て（org-oasis-open-xmile を呼ぶ）
src/hayari/simulate.cljs  当てはめ + 実行のエントリ
data/kinds.edn            P31 QID → 種別（44 種。label は API 実測値を pin）
data/m49-regions.edn      ISO2 → UN M49（生成物、手編集しない）
```

判断と effect を分ける形は `loop-system-dynamics` が `kotoba-lang/dynamics` に対して
持っているのと同じで、これがあるので判断はネットワーク無しでテストできる。

### Kotoba 移行の現在地

`core.cljc` は superproject `CLAUDE.md` の『移行の単位は決定核』の意味での決定核だが、
まだ `.kotoba` としてコンパイルしていない。2 つの制約が形を決めている:

- **例外を投げず `{:ok v}` / `{:error {...}}` を返す。** `explicit-errors` は
  `lang/surface-status.edn` で `:intentional-security-constraint` なので、これは
  **恒久**であって backend が追いついても変わらない。
- **map と vector を自由に使っている。** これらは `:implemented-partial`
  （`#{:compiler :kotoba-wasm :kotoba-cljs}`）で native には無い。これは
  **backend gap であって様式ではない** —— word 型スカラや handle に潰して native を
  追いかけない（`CLAUDE.md`『`.kotoba` で「書けない」は 2 種類ある』）。

## 隣接する repo との境界

- **`etzhayyim/com-etzhayyim-kawaraban`（瓦版）** は世界のニュース媒体の mirror で、
  *何が報じられたか*を運ぶ。hayari は*何が見られたか*を測る。報道と注目は別物で、
  片方から他方は出ない。
- **`cloud-itonami/yomi`（読み）** は瓦版を購読して intel 評価を著者として発行する
  SOURCE / VOICE。hayari は評価を書かない —— 観測と、観測の欠損だけを出す。
- **`cloud-itonami/animeka` / `mangaka` / `dougaka` / `com-etzhayyim-minidrama`** は
  作る側（production actor）。hayari は測る側で、作品を作らない。
- **`etzhayyim/com-etzhayyim-media-gamers`** はゲーム攻略データに限定した収集。
  hayari は媒体を限定せず、代わりに「注目」という 1 つの指標しか持たない。

## 非目標

- **国や作品の格付けをしない。** ランキングは Wikimedia が観測した閲覧の順位であって、
  質・重要性・正統性の順位ではない。
- **視聴者の属性を推定しない。** 上記のとおり、そこは測定していない。
- **欠損を埋めない。** QID が引けない記事、P31 が表に無い作品、公開日を持たない項目は
  それぞれ coverage に数えて残す。
- **人物に作品の年代を付けない。** 未判定行の多くは人物だが、人物は作品ではない。
  人物の年代軸が欲しくなったら、別の名前と別の正当化が要る。
