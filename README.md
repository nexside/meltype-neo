<img width="1200" height="480" alt="OBK5Y" src="https://github.com/user-attachments/assets/3e379a69-b135-4d6c-8ba6-9a91edc14bf2" />

# Meltype for Android

[Meltype](https://github.com/yksr-melt/Meltype) を Android の物理キーボードで使えるようにした、**非公式の移植版**です。

> **雪解けのように、半角/全角の壁を溶かす日本語入力。**
> ローマ字で打つだけ。日本語か英語かは Meltype が見分けて、その場で切り替えます！
>
> — [Meltype](https://github.com/yksr-melt/Meltype) の README より

```
kyouhagoogledekensaku    →  今日はgoogleで検索
ashitanomeetingwotsuika  →  明日のmeetingを追加
```

## 本家 Meltype について

Meltype は **雪代 / Yukishiro さん ([@yksr-melt](https://github.com/yksr-melt))** が作っている Windows 向けの日本語入力です (Mac / Linux 版もあります)。
ローマ字の打鍵から英語と日本語を自動で見分ける、という発想と、その判定ロジック、判定に使う辞書づくりは本家の仕事です (辞書の一部は JMdict などの公開データをもとにしています。くわしくは本家の [THIRD-PARTY-NOTICES.md](https://github.com/yksr-melt/Meltype/blob/main/THIRD-PARTY-NOTICES.md))。この Android 版は、その仕組みを Android で動かすための「つなぎ」を書いただけです。

- 本家: https://github.com/yksr-melt/Meltype
- 本家の使い方: https://github.com/yksr-melt/Meltype/blob/main/docs/USAGE.md
- 本家を応援する: https://github.com/sponsors/yksr-melt

このリポジトリは本家の作者とは関係なく、本家から承認を受けたものでもありません。
**Android 版の不具合は本家ではなく、[このリポジトリの Issue](https://github.com/nexside/meltype-android/issues) に報告してください。**

本家のコードは `upstream/` に git submodule として取り込み、**一切変更せずに**使っています。Android で必要な関数 (学習を止める `meltype_set_private` など) は、`core-native/` に別のファイルとして足しています。

## なぜ作ったか

作者 ([@_horotter](https://x.com/_horotter)) は、BlackBerry のような物理 QWERTY キーボードの付いた Android 端末 **Zinwa Q25** を毎日使っています。
Q25 の小さなキーボードで 半角/全角 を切り替えながら日本語と英語を打つのはつらく、本家 Meltype の「切り替えなくていい」入力を Q25 でも使いたくて作りました。
そのため、Q25 での使い勝手を最優先にしています (キーの実測: [docs/q25-keys.md](docs/q25-keys.md))。

## 仕組み

| 部分 | 中身 |
|---|---|
| 英語 / 日本語の判定 | 本家の C# の Core (`upstream/src/Meltype.Core`) を .NET NativeAOT で Android 向けにビルド (`libMeltypeNative.so`) |
| かな漢字変換 | 本家が Mac / Linux 版で使っている [Mozc](https://github.com/google/mozc) を Android 向けにビルド (`libmozc_helper.so`, `libmozc_data.so`) |
| IME | Kotlin の `InputMethodService`。キーを Core に渡し、結果を入力欄と候補バーに反映 |

- AI モデルは使っていません。本家の判定ルールと辞書、Mozc の辞書と統計だけで動きます。
- キー入力の判定と変換は端末の中で動き、アプリ自身はネットワーク権限を持ちません。音声入力は Android の音声認識サービスを使うため、サービスの設定によって音声が外部に送信される場合があります。
- パスワード欄では Meltype を通しません。「学習しない」指定の入力欄 (シークレットモードなど) では、変換はしますが覚えません (本家 Windows 版と同じ考え方)。

## 使い方

対象: Android 10 以降 (arm64)。今の版は**物理キーボード専用**です (Zinwa Q25 の内蔵キーボード、Bluetooth / USB キーボード)。文字入力用の画面キーボードは内蔵していません。物理キーボードがない端末では以前の入力方法へ戻ります。戻す相手がない場合は、表示される「画面キーボードに切り替える」から Gboard などを選んでください。

1. [Releases](https://github.com/345ml/meltype-android/releases) から APK を入れる (下の「ビルド」で自分で作ることもできます)。
2. ランチャーの「Meltype」を開き、「はじめに」の 1〜3 を順に行う (有効にする → 選ぶ)。
3. ローマ字で打つと、英語はそのまま、日本語はかな漢字になります。Space で変換、Enter で確定。

変換中は、カーソルの近くに候補の窓が浮かびます。「あ」(日本語・自動判定あり) と「A」(直接入力) の表示は、切り替えたときだけカーソルのそばに出ます。ふだんは「あ」のまま英語も日本語も打ってください。

| 操作 | 外付けキーボード | Zinwa Q25 |
|---|---|---|
| あ / A の切り替え | 半角/全角, Ctrl+Space | Shift+Space (設定でオフにできる) |
| 候補を選ぶ | ↑↓ / Space | トラックパッド / Space |
| 確定 | Enter | Enter / トラックパッドを押す |
| カタカナ / 英字にする | F7 / F10 | 候補の窓の「カナ」「英数」 |
| 一時停止 | Ctrl+半角/全角 | (設定画面から) |
| 数字・記号 | | Alt+文字 (Q25 の刻印どおり) |

- JIS キーボードは、Android の設定の「物理キーボード」で配列を「日本語」にしてください。
- 本家の Windows 版にある「英数状態でもローマ字を検知」など、Windows 側で作られている機能はありません。
- 日本語と英語以外の言語には対応していません。他の言語は Gboard などに切り替えて使ってください。

## このフォーク (nexside) の追加機能

- **候補はカーソルの近くに浮かべる**: 画面の下にバーは出しません。変換中の文字の上に候補の窓が浮かび、選んでいる文節は反転します。「あ」/「A」の切り替えも、カーソルのそばに一瞬出るだけです。
- **絵文字**: `Ctrl+.` (`Ctrl+;`・`Win+.` も。Ctrl の無い Q25 は `Alt+Enter`) で一覧を開きます。矢印 (Q25 はトラックパッド) で選び、Enter で入力、Tab / PageUp・PageDown でカテゴリ、Esc で閉じます。「最近使った絵文字」が先頭のタブに出ます。「えがお」「ねこ」「えもじ」など読みで変換しても、候補の最後に絵文字が出ます (本家の辞書)。候補の窓の「絵文字」ボタンで絵文字だけに絞り込めます。
- **ユーザー辞書の一括登録**: 設定 > ユーザー辞書を編集する、で、テキストを貼り付けるかファイルを読み込みます。「読み[Tab]単語」「読み,単語」「読み 単語」と、Microsoft IME・Google 日本語入力の書き出し (UTF-8 / UTF-16) を読めます。
- **その場で単語登録**: 変換中の候補の窓の「登録」ボタンで、読みと単語を確かめて辞書に足します。辞書は次に入力欄を開いたときに読み直します。
- **かっこの中にカーソル**: 確定した文字が `()` `「」` などのかっこの組だったら、カーソルを間に置きます (設定でオフにできます)。
- **候補の配色**: 設定で、システムに合わせる / ライト / ダーク (黒地に白文字) を選べます。
- **数字の一行**: 暗証番号・数字・電話の欄だけ、画面の一番下に Q25 の物理キーと同じ黒いキーキャップが並びます。1〜0 と削除。電話番号は 0 の長押しで +、小数の欄には . も出ます。文章の入力では出ません。
- **Alt の刻印**: Q25 の Alt か Sym を押しているあいだ、画面の下にその面の記号がキーキャップで出ます。離すと消えます。
- **0 の長押し**: Q25 のスペース左の 0 は、短く押すと 0 です。押しているあいだは、人が話している大きいアイコンを出して音声入力し、離すとそこまでの文が入ります。最初だけマイクの許可が要ります。パスワード欄では始まりません。
- **確定を1つ戻す**: 変換を確定した直後の Backspace だけ、その語を確定前の読みに戻します。そのあとの Backspace は普通に消します。
- **疑問の「？」**: 確定した文が「か」「かな」「だろう」で終わっていて、まだ記号が無いときだけ、候補に「？」を出します。Space / Enter / タップ (Q25 はトラックパッドを押す) で入れます。次の文字を打つと消えます。自動では入れません。
- **ジャンル別の辞書**: 設定の「ジャンル別の辞書」で、IT・医療・法律・金融・科学・ビジネス文書の用語をオン / オフできます。自分のジャンルを作り、テキストで一括登録もできます。

## 安定性と診断 (0.0.2)

- 診断ログは標準でオフ。設定で有効にした場合も入力文字は記録せず、書き込みをまとめてバックグラウンドで行います。旧形式の診断ログは更新後に消去します。
- 辞書は一時ファイルへの書き込みを完了してから置換します。保存に失敗した場合は元のファイルを保持し、別画面で更新された辞書を古い編集内容で上書きしません。
- 辞書の取り込み・保存・ジャンル一覧の集計はバックグラウンドで行い、処理中は重複操作を防ぎます。

## ビルド

必要なもの: Docker、JDK 17、Android SDK (`ANDROID_HOME`)、NDK 27.1.12297006。

    git submodule update --init --recursive
    docker build --platform linux/amd64 -t meltype-build docker
    docker/run.sh core-native/build.sh
    docker/run.sh mozc/build.sh
    ./gradlew :app:assembleDebug

テスト: `./gradlew :app:testDebugUnitTest` (JVM)、`tools/e2e.sh` (つないだ端末で実際に打って確かめる)。Q25 での確認項目は [docs/q25-checklist.md](docs/q25-checklist.md)。

## ライセンス

GPL-3.0-or-later (本家と同じ)。[LICENSE](LICENSE)

```
Meltype
Copyright (C) 2026 雪代 / Yukishiro (@yksr_melt / @yksr-melt)

Meltype for Android
Copyright (C) 2026 Keisuke Horota (@345ml / @_horotter)
```

同梱しているもの (本家 Meltype・Mozc・.NET ランタイム) とそのライセンスは [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) にまとめています。APK の設定画面の「ライセンス」でも読めます。

素晴らしい入力方式を GPL で公開してくださっている雪代さんに感謝します。
