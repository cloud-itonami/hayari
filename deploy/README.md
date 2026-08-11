# 常駐 — 収集は credential を持たない機械、着地は credential のある機械

```
asher (mac mini, 72日連続稼働)          laptop
  com.gftd.hayari-node    ← LaunchDaemon, KeepAlive
  com.gftd.hayari-collect ← 15分ごと            com.gftd.hayari-mirror ← 15分ごと
        │                                              │
        │ nbb bin/tick.cljs --mode collect             │ --mode mirror
        │ collect → commit → git push rad              │ rad/main → ff → GitHub
        ▼                                              │ raw を asher から取り寄せ
   Radicle seed gad (tailnet 100.82.98.110) ──────────▶│ → s3.kotobase.net へ custody
                                                        │ → west pin 前進
                                                        ▼
                                                   GitHub main
```

## なぜ分けたか

`cloud-itonami` は org 設定 **`deploy_keys_enabled_for_repositories: false`**
（2026-08-11 実測）で、repo admin を持っていても deploy key を作れない。org 設定の
変更には `admin:org` scope が要り、手元の token は `gist, read:org, repo` しか持たない。

**単一 repo の可用性のために org 全体の鍵ポリシーを緩めるのは、可用性のために
機密性を売る取引**なので採らなかった。汎用 token をノードへ複製するのも同じ理由で
採らない（blast radius が機械の数だけ増える）。

代わりに経路を割った。asher が持つのは**自己発行の Radicle 鍵だけ**で、それが与えるのは
「この repo を publish する権利」に限られる。**GitHub token の blast radius は増えない。**

Radicle は既にこの workspace の設備である —— 自前 seed `gad` に 623 repo が実登録されており、
hayari は `rad:z5GnP9asXmnYf2i9TG2LcV2hvC2W`。asher の identity
`did:key:z6MkpLHhLiKcjKxFf4g95LpgqBvrPwkoJoK8yaFV9nQMdYzm` を delegate に追加してある
（threshold は 1 のまま）。

## 書き手はちょうど 1 人

**asher だけが commit する。** mirror は ff しかしない。両方が collect すると同じ日を
取り合って分岐するので、laptop 側の `--mode full` 常駐は撤去した。

mirror が ff できない = GitHub 側に Radicle に無い commit がある = 書き手が 2 人になった、
という意味なので、その時は黙って進まず落ちる。

## LaunchAgent ではなく LaunchDaemon

asher には**ログインセッションが無い**（`who` が空）ので LaunchAgent は
`bootstrap gui/$UID` が `Domain does not support specified action` で通らない。
`crontab` も TCC に阻まれる（`Operation not permitted`）。したがって
`/Library/LaunchDaemons` に `UserName asher` で置く。

plist は world-readable なので値は書かない。`RAD_PASSPHRASE` は**空文字**であって
秘密ではない（鍵自体は `~/.radicle/keys`、asher のみ）。

## 監視

`com.gftd.hayari-alarm`（laptop、30分ごと）は GitHub 上の要約に載る health entity の
鮮度を見る。**collect が止まれば heartbeat が古くなり、mirror が止まれば GitHub が
進まなくなる** —— どちらも同じ警報で捕まる。

ただし警報自体は laptop にあり、**laptop が落ちれば警報も落ちる**。これは credential が
laptop にしか無いことの帰結で、鍵の置き場が変わるまで構造的に解けない。

## 手で回す

```bash
# asher（収集側）
ssh asher 'sudo launchctl kickstart -k system/com.gftd.hayari-collect'
# laptop（着地側）
launchctl start com.gftd.hayari-mirror && tail -f /tmp/hayari-mirror.log
```
