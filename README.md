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
| **作品の種別** | ○ | Wikidata P31 → `data/kinds.edn`（91 種。anime / TV / film / manga / game / 化学 / 医学 / 天文 / スポーツ …） |
| **ジャンル** | ○ | Wikidata P136 → ラベル文字列（`drama television series` / `adventure anime and manga` / `K-pop` …） |
| **人物の職業** | ○ | Wikidata P106 → ラベル文字列（`actor` / `seiyū` / `tarento` / `basketball player` …） |
| **領域（roll-up）** | ○ | 種別 → `data/domains.edn`（culture / person / science / sport / event / organisation / place） |
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

## ドラマとアニメは P31 では区別できない

これは実測で分かったことで、設計上の要点である。2026-08-10 に実際の作品を引いた:

```
進撃の巨人   P31: manga series        P136: adventure anime and manga, dark fantasy anime and manga
鬼滅の刃     P31: manga series        P136: dark fantasy, adventure anime and manga
ひよっこ     P31: television series   P136: drama television series
半沢直樹     P31: television series   P136: drama television series
愛の不時着   P31: television series   P136: romantic comedy, drama
新世紀エヴァ P31: anime television series  P136: drama anime, mecha, science fantasy
```

**`P31` は「どういう形式で世に出たか」しか言わない。** ドラマ / アニメ / ジャンルを
分けているのは **P136** で、だから `:hayari/genres` を出す。

同じ理由で **人物の P31 は必ず `Q5`（human）** である。俳優と政治家とアスリートを
分けるのは **P106（occupation）**だけなので、`:hayari/occupations` を出す。

ジャンルと職業のラベルは**収集時に引く**（表を手で持たない）。職業は数千種あり、
手入力の対応表は必ず、しかも気付かれずに陳腐化する。`data/kinds.edn` が手書きで
いられるのは頭が短くて実測されているからで、この 2 つはそうではない。

## 1900 年からの 1 年ごとの軸 — 埋まっている年と、空の年

出力には毎回 `:hayari.era-coverage/*` エンティティが入り、**1900 年から今年までの
全ての年**を、0 の年も含めて持つ:

```clojure
{:hayari.era-coverage/from 1900  :hayari.era-coverage/to 2026
 :hayari.era-coverage/years-populated 43   :hayari.era-coverage/years-empty 84
 :hayari.era-coverage/oldest-year 1903     :hayari.era-coverage/works-total 86
 :hayari.era-coverage/outside-range 7      ; 1900 より前に日付された作品
 :hayari.era-coverage/by-year "{1900 0, 1901 0, 1902 0, 1903 1, ...}"}
```

- **空の年を省略しない。** 省略すると「その年は訊かれなかった」のように読める。
- **数えるのは行ではなく distinct な作品。** 40 か国で見られた 1 本の映画は
  その年の 1 作品であって 40 ではない（そうしないと到達度を測って
  カタログの厚みと名付けることになる）。
- **1900 より前の作品は切り捨てず `outside-range` に数える。** 実際に 660 年代・
  1860 年代の作品が観測されている。

### 古い作品に届く梃子は「国数」ではなく「深さ」

実測（2026-08-08、JP 1 か国 1 日）:

| | 出た作品 |
|---|---|
| `--top 25` | ほぼ 2020 年代のみ |
| `--top 400` | 年つき 91 作品、**うち 1900–1999 が 34**（50年代 4 / 60年代 5 / 70年代 1 / 80年代 3 / 90年代 9）、最古は 660 年代 |

per-country endpoint は 1 か国あたり最大 1000 件返す。**古い作品は日々の注目の
長い尾に居る**ので、届くかどうかを決めるのは `--top` である。

**ただし深さは enrichment の費用に直結する**（distinct な記事名が増える）ので、
登録簿の日次 run は `--top 25` のままにしてある。日次 run の仕事は時系列であって
カタログの厚みではない。年代を厚くしたい時は深い run を別に回す:

```bash
nbb src/hayari/collect.cljs --top 300 --days 4 --budget-ms 1500000 \
    --countries JP,US,GB,FR,DE,IT,ES,KR,TW,BR,IN,MX,PL,NL,SE,RU,TR
```

## コンテンツと entity を実際に取得する

観測は「ある国がこれを見た」までしか言わない。`corpus.cljs` は**それが何なのか**を
取りに行く —— Wikipedia の冒頭抜粋と、Wikidata の entity レコード。

```bash
nbb src/hayari/corpus.cljs                       # 既定: 記事 2000 / entity 4000、予算 900s
nbb src/hayari/corpus.cljs --content-limit 600 --entity-limit 2000
```

出力は 2 つ、**ライセンスが違うので分けてある**:

| ファイル | 中身 | ライセンス |
|---|---|---|
| `data/hayari-content.edn` | 冒頭抜粋・説明・正規 URL・サムネ URL・リビジョン | **CC BY-SA 4.0**（帰属 + 継承） |
| `data/hayari-entities.edn` | ラベル(en/ja)・説明(en/ja)・sitelink 数 | **CC0-1.0** |

**ライセンスは 1 レコードごとに刻む。** README に 1 回書くだけでは、抜粋された時点で
条件が失われる。`:hayari.content/license` `/license-url` `/attribution` が全行に付く。

### 取らないと決めたもの

- **全文は取らない。** summary endpoint が返すのは冒頭段落で、実測 167 文字（長編映画）。
  全文を持つと **git checkout の中に Wikipedia の鏡**ができるうえ、抜粋で答えられない
  問いが増えるわけでもない。
- **画像の実体は取らない。** サムネの **URL** だけ。画像はファイルごとに別ライセンスで、
  CC BY-SA とは限らない —— bytes を持つとその問題をこの corpus に取り込むことになる。
- **claims を再取得しない。** `props=claims` は 1 entity 109,873 バイト（実測）に対し
  `labels|descriptions` を en|ja に絞れば 438 バイト。しかも必要な claim
  （P31/P136/P106/P577/P495）は収集時に既に抽出済みで、**250 倍を払って手元にある
  データを取り直すことになる**。
- **sitelink は数だけ残す。** 300 言語のリストは 1 entity 5 KB あるが、ここで問いを
  立てているのは「どれだけ多くの言語版を持つか」だけ。

### 取得順は注目の大きい順

予算で打ち切られた run が**世界が実際に見ていたもの**を持っている状態になる。
打ち切った分は `:content/skipped-budget` に数えて申告する（順序が良いことは、
欠けていないことを意味しない）。

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
λ=0.1698  half-life=4.08d   r²=0.998  MAPE=0.6%  ひよっこ_(テレビドラマ) [:tv/series]
λ=-0.0713 half-life=growing r²=0.824  MAPE=3.3%  杀人者的购物中心 [:tv/series]
```

**`--by era` は年代ごとの集約に当てる。**「1960 年代の作品への注目は、今年の作品への
注目と違う速さで抜けるのか」を問える。日付を持たない行（大半は人物）は捨てずに
`undated` として独立した系列にする —— 除くと、日付のある年代が観測の全体像のように
見えてしまう。

**`--by domain` で領域ごとの集約にも同じモデルを当てる。**「この国の注目は、
文化と出来事とで違う速さで抜けていくのか」を 1 コマンドで問える:

```
$ nbb src/hayari/simulate.cljs --by domain
  λ=0.1947  half-life=3.56d   r²=0.897  person
  λ=-0.0595 half-life=growing r²=0.808  culture
  λ=-0.1658 half-life=growing r²=0.600  event
```

領域が付かなかった行は捨てずに `unmapped` として集約に残す —— 表が取りこぼした
分を黙って除くと、合計が実際より小さいのに全体を表しているように読める。

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
src/hayari/corpus.cljs    本文抜粋 + entity の取得（ライセンスを行ごとに刻む）
src/hayari/xmile.cljc     XMILE モデルの組み立て（org-oasis-open-xmile を呼ぶ）
src/hayari/simulate.cljs  当てはめ + 実行のエントリ
data/kinds.edn            P31 QID → 種別（91 種。label は API 実測値を pin、
                          [MEASURED]（実測で出た）と [BREADTH]（未観測の語彙）を明示）
data/domains.edn          種別 → 領域の roll-up（手書き語彙）
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
