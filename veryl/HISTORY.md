# ATOM Display FPGA — Chisel → Veryl 移植作業履歴

M5Stack ATOM Display / Display Module 内蔵 FPGA(GOWIN GW1NR-LV9QN88C6I5)向けデザインを
Chisel(Scala)から Veryl へ移植した作業の記録。RTL 移植・ネイティブシミュレータによる検証・
GOWIN EDA での合成(ビットストリーム生成・タイミングクロージャ)まで完了。

- 作業期間: 2026-07-11 〜 2026-07-17
- ブランチ: feature/veryl-port(着手時は feature/xreal-air)
- 移植元: `atom_display/`(本体, Chisel 3.5.4)+ `fpga_samples/chisel`(共有ライブラリ, サブモジュール)
- 移植先: `veryl/`
- 合成プロジェクト: `eda/atomdisplay_veryl/`

---

## 1. 目的とスコープ

SPI 接続の ESP32 から描画命令を受け取り、FPGA 内蔵 SDRAM 上のフレームバッファに描画し、
その内容を外部 HDMI トランスミッタへ RGB ビデオ信号として出力するデザイン。

移植スコープはトップモジュール `M5StackHDMI` から到達可能なモジュールのみ
(fpga_samples 全体の Ethernet / I2S / HUB75 等は対象外)。

データフロー:

```
ESP32 ─SPI→ SPISlave → Queue → CommandProcessor
                                   ├→ StreamWriter ─┐
                                   └→ StreamReader ─┤ (COPYRECT)
FrameBufferReader ───────────────────────────────────┼→ AXI4PriorityDemux
                                                     → SDRCBridge → GOWIN SDRAM コントローラ → 内蔵 SDRAM
FrameBufferReader → AsyncFIFO(65MHz→ビデオクロック)→ VideoSignalGenerator → top.sv(ODDR)→ 外部 HDMI Tx
```

クロックドメインは 2 つ: メインクロック(50MHz→sdram_rpll→65MHz)とビデオクロック
(74.25MHz→dvi_rpll、コマンドで分周比を動的変更可)。境界は AsyncFIFO。

---

## 2. 作業の流れ

### フェーズ 0: 構成把握とテスト方針決定
- リポジトリ構成(sbt マルチプロジェクト + GOWIN EDA + ESP32 テストコード)を把握。
- Veryl 0.20.2 のネイティブテスト機能(`#[test]` + `$tb::clock_gen`/`$tb::reset_gen`、
  Verilator 不要)をスモークテストで確認。
- 参考として Veryl 開発者の RV64GC CPU 実装 **Heliodor**(github.com/dalance/heliodor)を調査。
  Linux ブートまでネイティブシミュレータで検証している実績から、テストは `tb/` 分離 +
  ハーネスパターン + `$readmemh` データ駆動 という流儀を採用。

### フェーズ 1: 型定義
`stream_if`(valid/ready ハンドシェイク), `video_pkg`(VideoConfig/VideoSignal/VideoIO/
プリセット), `axi4_if`(構造体ペイロード + read/write modport), `spi_if`, `sdrc_if`,
`command_pkg`(VideoClockConfig + コマンドオペコード)。

### フェーズ 2: util 層
`graycode_pkg`, `irrevocable_reg_slice`, `irrevocable_gate`, `sync_fifo`, `async_fifo`。
async_fifo は 2 クロックドメインを `$tb::clock_gen` 2 個で独立駆動して CDC 検証。

### フェーズ 3: AXI 層
`axi4_gate`, `axi4_reg_slice`, `axi4_priority_demux`(低インデックス優先アービタ)。
テスト用スレーブモデル `tb_axi4_memory`。

### フェーズ 4: SPISlave(3 クロックドメイン)
SCK posedge / negedge / メインの 3 ドメイン、CS を非同期リセットとして使用。
信号駆動クロックのネイティブシミュレータ制約の切り分けに最も時間を要した(下記制約参照)。

### フェーズ 5: SDRCBridge + SimSDRC
AXI4 → GOWIN SDRAM コントローラのコマンド変換。実波形由来のリードレイテンシ・busy_n 遅延を
再現した `tb_sim_sdrc` モデルと結合テスト。

### フェーズ 6: video 層
`line_reader`/`line_writer`(バイトリアライメント), `stream_reader`/`stream_writer`
(矩形・後方転送), `video_signal_generator`(H/V タイミング + 8x8 スケーリング),
`frame_buffer_reader`(フレーム読み出し + X/Y スケーリング)。

### フェーズ 7: CommandProcessor(最大モジュール)
SPI 描画コマンドの全機能(FILLRECT / DRAW_PIXEL / WRITE_RAW(RGB332/565/888) / COPYRECT /
CA_SET / RA_SET / READ_ID / SET_SCREEN_SCALE / SET_SCREEN_ORIGIN / SET_RESOLUTION /
SET_VIDEO_CLOCK)+ BUSY/IDLE/結果ステータスストリーム。依存部品 `byte_packer`,
`packet_queue`, `irrevocable_unsafe_switch` も移植。

### フェーズ 8: トップ統合とシステムテスト
`m5stack_hdmi_core`(全モジュール結線)+ `m5stack_hdmi`(spi_slave 込み合成用)。
システムテスト `test_m5stack_hdmi` は SPI バイト列 → CommandProcessor → StreamWriter →
AXI 調停 → SDRCBridge → SDRAM モデル → FrameBufferReader → AsyncFIFO → VSG(別クロック
ドメイン)の全経路でフレームのピクセル一致まで検証。

### フェーズ 9: GOWIN 合成
合成用平ポートラッパー `m5stack_hdmi_flat` + 改変版 top.sv + project.tcl。
ビットストリーム生成・タイミングクロージャ達成まで(下記「合成」参照)。

---

## 3. 判明した Veryl 0.20.2 の制約と対処(重要)

移植中に確認したツールチェーンの制約。詳細は個別メモリにも記録済み。

### 言語・コンパイラ
- **ジェネリックパッケージ(`package p::<N>` / `alias package`)を参照するテストは
  `veryl test` がクラッシュ**(ネイティブシミュレータの panic、`--sim verilator` でも回避不可)。
  → video_pkg は Maximum 固定サイジング + プリセット関数の構成に再設計。
  ジェネリック**構造体・インターフェース・関数**は問題なし。
- ジェネリック引数は**識別子/リテラルのみ、式は不可**(`GS::<W*8>` はパースエラー)。
  → 幅が相互依存するモジュールは独立パラメータを冗長に渡す(sdrc_bridge の 5 パラメータ)。
- ポート宣言位置のインターフェースジェネリック引数に**ネストジェネリック不可**、かつ
  `T: type` パラメータの変数はメンバアクセス不可。→ パラメータ化構造体ペイロードのコマンドは
  valid/ready/payload の平ポート 3 本組で受ける(line_reader 等)。
- `config` は SV 予約語で識別子に使えない(`cfg` を使用)。
- `as $clog2(...)` のようにキャスト先に式は書けない → 幅は const に束ねる。
- 三項演算子は `if cond ? a : b`(`cond ? a : b` は不可)。
- インターフェース内 `const` は外部(インスタンス経由)から参照不可 → `$bits(inst.signal)` で代用。
- 警告(unassign_variable 等)でも `veryl check`/`veryl test` は exit 1 → `#[allow(...)]` で許可。

### ネイティブシミュレータの $tb バインディング(SPISlave で判明・症状は「always_ff が無言で動かない」)
- `logic`→`clock` 系のキャストは不可(check 時 invalid_cast)。信号をクロックに使うポートは
  最初から `clock`/`clock_negedge` 型で宣言(インターフェース変数に `var sck: clock;` 可)。
- `logic`→`reset` 系のキャストはコンパイルは通るが**シミュレーションでは無視される**(合成専用)。
- **クロック/リセットのキャスト接続はテスト階層で実質 1 つしか $tb ジェネレータにバインドされず、
  かつ `let` 束縛でないと効かない**(インラインキャストは死ぬ)。
- **`#(param)` 付きモジュールにキャスト接続するとバインディングが全滅**
  → キャスト接続を受けるモジュールは param なし(定数はモジュール内 const)。
- **クロック/リセットポートはデータポートより前に宣言する**。
- 対策として**プロジェクト全体の `reset_type` を `async_high` に変更**(CS 非同期リセットと整合、
  リセットポートを plain `reset` にして reset_gen 直結。top.sv は同期デアサートなので安全)。

### インターフェース配列
- **配列要素の構造体メンバ深掘り(`arr[1].payload.data`)は手続き代入・継続代入・ポート接続の
  いずれもネイティブシミュレータ未対応**(unsupported_description)。
  → `assign pay = arr[1].payload;`(全体コピー)してから `pay.data` を使う。

### その他
- `always_ff` の代入はノンブロッキング(Chisel の `:=` と同じ)。文順そのまま移植可。
- 未接続の出力ポートは `o_count: _` で OK。
- IDE(LSP)診断はファイル削除→再作成後に古いエラーを出し続けることがある。`veryl check` が正。

---

## 4. 検証

- 実行: `cd veryl && veryl test`(ネイティブシミュレータ、Verilator 不要、全テスト数秒)。
- テスト数: **20 本**(ユニット 19 + システム E2E 1)、全パス。
- システムテスト test_m5stack_hdmi はメイン:ビデオ = 3:1 の 2 クロックで、
  自動フレームバッファクリア → 全画面赤 FILL_RECT → 緑矩形 FILL_RECT → VSG 同期獲得 →
  1 フレーム 128 ピクセル全数の期待パターン一致、まで検証。
- 実装で SDRAM モデルのバグを 1 件発見・修正: `MemWords as AddressBits` が
  AddressBits=log2(MemWords) ちょうどのとき 0 に切り詰められ全 read/write が破棄されていた
  (ワイド比較に修正)。

---

## 5. 合成(GOWIN EDA)

### プロジェクト構成
- `veryl/Veryl.toml` に `omit_project_prefix = true` を設定し、モジュール名から
  `atom_display_` プレフィックスを除去(top.sv から `m5stack_hdmi` を参照可能に)。
- 合成用平ポートラッパー `src/system/m5stack_hdmi_flat.veryl` が Chisel 版 `M5StackHDMI` の
  `io_*` ポート互換を提供。
- `eda/atomdisplay_veryl/src/top.sv`(改変版、インスタンス化 1 箇所とデバッグプローブ削除のみ)。
- `eda/atomdisplay_veryl/project.tcl`(Veryl 出力 `target/*/*.sv` を glob。テストは target
  ルートに出るため自動的に除外。GOWIN IP・改変 top.sv・元の .cst/.sdc を追加)。

### 環境の要点
- `gw_sh`: `~/gowin/1.9.12/IDE/bin/gw_sh`。
- **ライセンス**: `~/gowin/1.9.12/IDE/bin/gwlicense.ini` の `lic=` をノードロックライセンス
  `~/gowin/license/2026/gowin_E_3495DB2B00CF_20260504.lic` に書き換え
  (デフォルトのサーバー 27020@45.33.107.56 はタイムアウトする)。
- **headless 実行に `QT_QPA_PLATFORM=offscreen` 必須**。
- テスト用型 `stream_test_pkg` は `src/util/` に配置(tb に置くとテスト由来のジェネリック
  具象化が src の SV に残り、合成で未定義エラー)。

実行例:
```
cd veryl && veryl build
cd eda/atomdisplay_veryl && mkdir -p build && cd build
QT_QPA_PLATFORM=offscreen ~/gowin/1.9.12/IDE/bin/gw_sh ../project.tcl
```

### BRAM 推論(最重要の設計変更)
当初 `sync_fifo` が非同期 read(`assign o_data.payload = mem[rp]`)だったため、大容量 FIFO
(2048/4096 エントリ)が全て FF に展開され **DFF 154,014 個 > デバイス上限 6,807 個**で失敗。
Chisel 版はこれらを BSRAM に置いていた(READMEで BSRAM 53%)。

対処: sync_fifo を**同期 read + FWFT スキッドレジスタ**構成に書き換え、合成ツールが
GOWIN SDPB ブロック RAM に推論できる形にした(総容量 DEPTH+1、o_count は総数)。
async_fifo は元から書き/読み別 always_ff の同期メモリで SDPB 化される。

### タイミングクロージャ
BRAM 化後の初回合成では clock_main が Fmax 68.7MHz(制約 70MHz、slack -0.271ns)で未達。
ワーストパスは sdrc_bridge の Wフィフォ o_count(wp-rp の減算)→ バースト発行判定 →
アドレス更新の組み合わせパス。sync_fifo の `o_count` をレジスタ化(count_q、1 サイクル遅延
= 「十分バッファされたか」判定では常に安全側)してこのパスを断ち、**clock_main を 71.7MHz に改善**。

なお SDC(`m5stack_display.sdc`)の `set_clock_groups -asynchronous` により clock_main ↔
clock_video / SPI_SCK は非同期扱い(CDC は false path)。これはクロック名ベースで、top.sv の
クロックネット名(clock, clock_video, BUS_SPI_SCK)は不変なのでそのまま有効。

---

## 6. 結果

### タイミング(全クロック制約達成)
| クロック | 制約 | 実測 Fmax | 判定 |
|---|---|---|---|
| clock_main | 70.0 MHz | 71.665 MHz | OK |
| clock_video | 80.0 MHz | 82.383 MHz | OK |
| SPI_SCK | 80.0 MHz | 137.540 MHz | OK |

### リソース使用量(Chisel 版との比較)
| 項目 | Veryl 版 | Chisel 版(README) |
|---|---|---|
| Logic | 4150 / 8640 (49%) | 4971 (57%) |
| LUT | 3090 | 3896 |
| ALU | 934 | 889 |
| SSRAM(RAM16) | 21 | 31 |
| Register(FF) | 4037 | 4078 |
| CLS | 3647 (85%) | 3824 (88%) |
| BSRAM | 21(SDPB 17 + SDPX9B 4, 81%) | 14(SDPB 7 + SDPX9B 7, 53%) |
| DSP(MULT18X18) | 1 | 1 |
| PLL | 2/2 | 1/2 |

LUT / レジスタ / CLS は Chisel 版と同等かやや少ない。BSRAM は多め(自作 FIFO を素直に
BRAM 化したため)だが上限内。

### 成果物
- ビットストリーム: `eda/atomdisplay_veryl/build/impl/pnr/atomdisplay_veryl.fs`(3.5MB、実機書き込み可)
- Veryl ソース: `veryl/src/`(型定義パッケージ + モジュール 26 個、約 7,400 行)
- テスト: `veryl/tb/`(20 本)+ シミュレーションモデル 2 個(tb_axi4_memory, tb_sim_sdrc)

---

## 7. Chisel 版との既知の差分(実機確認時の着目点)

いずれも最終的なハードウェア動作は等価だが、移植で設計判断が入った箇所:

- **byte_packer**: Chisel の WidthConverter は LSB ファーストでパックし消費側で SwapByteOrder
  していたが、本移植では MSB ファーストで直接パック(最終的なピクセル値は同一)。
- **リセット**: Chisel は同期リセット emit だったが、本移植は `reset_type = async_high`
  (CS 非同期リセットとの整合とネイティブシミュレータ制約のため)。top.sv がリセットを同期
  デアサートするので非同期アサート化は安全。

---

## 8. リソース最適化(合成後)

初回合成後の見直しで BSRAM を削減:

- **line_reader の R チャネル FIFO 深さ**: Chisel 踏襲の 2048 は過大。valid/ready
  バックプレッシャーがデータを落とさないため深さはスループットにのみ影響し、発行制御
  (issued_data_words_remaining)により滞留は高々 1〜2 バースト。パラメータ化(RFifoDepthBits)
  して 512 に低減。StreamReader / FrameBufferReader の 2 インスタンス分で **BSRAM を 6 個削減**
  (21 → 15、Chisel 版 14 個にほぼ一致)。実機(720p 全画面)で表示正常を確認。
- sdrc_bridge の AR/AW/B(2 エントリ)を sync_fifo から irrevocable_reg_slice に置換
  (2 エントリの sync_fifo は BRAM を使わないため BSRAM 削減効果はなかったが、FF が僅かに減少)。

最適化後: BSRAM 15/26(58%)、LUT 3106、Register 3962、CLS 84%、
clock_main 71.2MHz(全クロック制約達成を維持)。

## 9. 24bpp 対応(2026-07-17)

Chisel 版で断念していた 24bpp 構成を移植・実機動作確認。
16/24 の切替は 2 箇所: `fpga_lib/src/video/video_pkg.veryl` の `PIXEL_BITS` と
`eda/atomdisplay_veryl/src/top.sv` の `BITS_PER_PIXEL`(既定は 24)。

### 変更内容
- **line_reader / line_writer**: リアラインバッファを一般化(4×PIXEL_BYTES バイト、
  24bpp は 12 バイトで非 2 のべき乗)。さらにポインタをバイト単位でなく
  **スロット単位(入力=ワード/出力=ピクセル)+周回パリティ**の分離レジスタで保持。
  バッファ読み書きのマックスが 12:1+アダーから 3:1/4:1 直接セレクトになり、
  タイミングが大幅に改善(下表)。`is_last_partial_write` も decode 時にレジスタ化。
- **色変換**: `command_pkg` に `rgb332/565/888_to_native`(16bpp: RGB565 /
  24bpp: BGR888)と BGR888 系変換を追加。command_processor・テストはこれを使用。
- **アドレス幅**: `video_pkg::ADDRESS_BITS`(24/25)・`SDRC_ADDRESS_BITS`(22/23)を
  ジェネリック引数として参照(パッケージスコープの const はジェネリック引数に使える。
  モジュール内 const は不可)。
- **バースト長(実機ハングの修正)**: Chisel の `maxBurstLength = maxBurstPixels(160)
  * pixelBytes / 4` は bpp 依存(16bpp: 80 / 24bpp: 120 ワード)。16bpp の値 80 を
  固定していたため、24bpp では stream writer/reader の 128px バースト = 96 ワードが
  上限超過、FBR の 106px = 79.5 ワード(非整数)でアドレス計算が破綻し実機でハング。
  シミュレーションの SDRAM モデルは上限を強制せず、テストは割り切れる小さいバースト値
  だったため未検出だった。`command_pkg::MAX_BURST_LENGTH / FBR_BURST_PIXELS /
  STREAM_BURST_PIXELS` として bpp 連動化。
- テストはプリフィル/期待値をバイト・ピクセル単位の計算に書き換えて bpp 非依存化。
  **16bpp・24bpp 両構成で 21 テスト全パス**。

### タイミング(24bpp、段階的改善)
| 段階 | clock_main (実 65MHz) | clock_video (実 74.25MHz) |
|---|---|---|
| 単純移植(バイトポインタ) | 62.28 MHz ✗ | 73.69 MHz ✗ |
| index+side レジスタ分離 | 63.46 MHz ✗ | 74.90 MHz △ |
| スロット符号化 | 68.79 MHz ✓ | 80.26 MHz ✓ |
| バースト長修正 | 67.81 MHz ✓ | 77.42 MHz ✓ |
| **async_fifo full 判定レジスタ化(最終)** | **70.12 MHz ✓** | **80.13 MHz ✓** |

最終構成は SDC 制約(main 70MHz / video 80MHz、マージン付き)も含め全達成。
async_fifo の write 側は同期済みリードグレイポインタの gray→binary XOR フォールドが
full/half_full 判定に直結しておりクリティカルパスだったため、変換結果をレジスタ化
(1 サイクル古い読み出し位置を見るのは full 判定として安全側)。この修正は 16bpp 構成の
タイミングも改善する(62.5 → 65.3 MHz、実クロック 65MHz を回復)。
リソース: Logic 57%、BSRAM 19/26(74%)。
実機で R/G/B/グレーのグラデーション表示により 24bpp 動作を確認
(16bpp だと 32/64 階調の縞になる)。

### 実機書き込みの注意
FPGA はウォームブートで再コンフィグできない仕様のため、ファームウェア
(ビットストリーム埋め込み)書き込み後は **USB 抜き差し(電源断)が必須**。
ウォームリセット時の `Waiting for FPGA idle timed out` は仕様どおりの動作。

## 10. 残タスク

- さらなる BSRAM 削減余地: async_fifo(4096)、packet_queue / SPI 受信キュー(各 2048)、
  VSG ラインバッファ(2048)。ただし機能上の必要深さの検証が要る。
- Chisel 版とのサイクル一致(ロックステップ)等価性検証。
- フルサイズ(2048px 幅 / 720p)でのシミュレーション。
- 16bpp 構成のタイミングマージン拡大(async_fifo 修正後 65.3MHz / 74.8MHz で実クロックは
  満たすがマージン僅少。残ワーストは half_full_r → axi4_gate → line_writer の
  複数モジュール貫通 ready/valid チェーンで、reg slice 挿入が候補)。
