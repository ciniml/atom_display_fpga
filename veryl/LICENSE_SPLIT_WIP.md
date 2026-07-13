# ライセンス分離作業 (作業中) — WIP ログ

Veryl 移植のうち、fpga_samples 由来モジュール(BSL-1.0)と ATOM Display 固有モジュール
(GPL-3.0-or-later)を分離する作業の途中経過。案A(BSL 群を別 Veryl プロジェクト
`fpga_lib` に分離、path 依存で参照)で進行中。

## 目的

- atom_display 固有: GPL-3.0-or-later
- fpga_samples 由来(汎用 FPGA 論理ライブラリ): BSL-1.0(GPL に巻き込まない)
- author はユーザーのみなのでライセンスは自由。fpga_samples 相当は BSL のまま再利用可能に保つ。

## 選定した方式(案A)

- BSL 群を独立 Veryl プロジェクト `veryl/fpga_lib/`(name = "fpga_lib")に分離。
- atom_display(`veryl/`)は `Veryl.toml` の `[dependencies]` で `fpga_lib = { path = "fpga_lib" }` を参照。
- 依存プロジェクトのシンボル参照はプレフィックス必須(`fpga_lib::stream_if::<...>`)。
  実験で確認: プレフィックスなし参照・`import` によるプレフィックス省略はいずれも不可。
- 依存プロジェクト名は `fpga_lib`(FPGA 向け論理回路ライブラリ、ユーザー承認済み)。

## 完了した作業

1. **SPDX 訂正(下準備)**
   - `sdrc_if.veryl` / `sdrc_bridge.veryl`: 移植元が `gowin/sdram.scala`(BSL)なので GPL→BSL に訂正。
   - `tb_sim_sdrc.veryl`(SimSDRC 由来): GPL→BSL に訂正。
   - `tb/test_sdrc_if.veryl` / `tb/test_sdrc_bridge.veryl`: GPL→BSL に訂正。

2. **video_pkg 分割(下準備)**
   - `src/video/video_pkg.veryl`(BSL): 汎用の型・定数・`config_value`・`total_counts` を残す。
   - `src/command/video_presets_pkg.veryl`(GPL, 新規): ATOM Display 固有のプリセット
     (`preset_720p` … `preset_xreal_air`, `preset_default`, `DEFAULT_*`)を切り出し。
     `m5stackhdmi.scala` の PresetVideoParams / defaultVideoParams 由来。
   - `command_processor.veryl` / `m5stack_hdmi.veryl` の `video_pkg::preset_default()` を
     `video_presets_pkg::preset_default()` に変更。
   - テストも分割: `test_video_pkg.veryl`(BSL, 型+config_value/total_counts) と
     `test_video_presets.veryl`(GPL, プリセット)。
   - この時点で 21 テスト全パス。

3. **fpga_lib プロジェクト分離**
   - `veryl/fpga_lib/{src,tb}` を作成し、BSL 群を `git mv` で移動:
     - src: `util/`, `axi/`, `spi/`, `sdram/`, `video/`(全部 BSL)
     - tb: BSL テスト(tb_axi4_memory, tb_sim_sdrc, test_async_fifo, test_axi4_*,
       test_frame_buffer_reader, test_graycode, test_irrevocable_reg_slice, test_sdrc_*,
       test_spi_slave, test_stream_*, test_sync_fifo, test_video_pkg, test_video_signal_generator)
   - `veryl/src` に残った GPL: `command/`(command_pkg, command_processor, video_presets_pkg), `system/`
   - `veryl/tb` に残った GPL テスト: test_command_pkg, test_command_processor,
     test_m5stack_hdmi, test_video_presets
   - `veryl/fpga_lib/LICENSE`: `fpga_samples/LICENSE`(BSL-1.0 全文)をコピー。
   - `veryl/fpga_lib/Veryl.toml`(name=fpga_lib, reset_type=async_high, sources=[src,tb]), `.gitignore` 作成。
   - **fpga_lib 単独で 17 テスト全パス**(BSL 群が GPL に一切依存しないことを実証)。

4. **atom_display 側の依存化・プレフィックス付与**
   - `veryl/Veryl.toml` に `[dependencies] fpga_lib = { path = "fpga_lib" }` を追加。
   - GPL 側 9 ファイル(command/*, system/*, GPL テスト)の BSL シンボル参照に `fpga_lib::`
     プレフィックスを python スクリプトで一括付与(video_pkg, axi4_pkg, spi_pkg, stream_if,
     sync_fifo, irrevocable_*, byte_packer, packet_queue, axi4_*, spi_slave, sdrc_bridge,
     stream_*, frame_buffer_reader, video_signal_generator, line_*, tb_* など)。
   - **atom_display 側で 4 テスト全パス**(fpga_lib への path 依存で正しく参照)。
   - 合計 21 テスト(分割前と同数)、ライセンス境界がプロジェクト境界と一致。

## 合成の名前不整合の解決(2026-07-14)

### 問題と切り分けの経緯
1. 当初の `'fpga_lib_video_pkg' is not declared` は、`omit_project_prefix = true` が
   atom_display 側のみだったのが原因。**両方の Veryl.toml に omit を設定**したところ、
   パッケージ/モジュールの定義・参照は一致(依存 SV は `veryl/dependencies/fpga_lib/` に
   `fpga_lib_` プレフィックス付きで emit、atom_display 側は無プレフィックス)。
   ※ omit を両方から削除する案も試したが、次項の不整合は残るため、omit 両方設定
   (top.sv 変更不要)を採用。
2. しかし **ジェネリックインターフェースの型引数マングリングの非対称**が残った
   (Veryl 0.20.2 のクロスプロジェクト制約):
   - fpga_lib 内部の固定ポート `stream_if::<video_pkg::VideoSignal>` は
     `fpga_lib___stream_if__video_pkg_VideoSignal`(引数にプレフィックスなし)
   - atom_display 側のインスタンス宣言 `fpga_lib::stream_if::<fpga_lib::video_pkg::VideoSignal>` は
     `fpga_lib___stream_if__fpga_lib_video_pkg_VideoSignal`(引数にプレフィックスあり)
   - 両方の interface が emit され、EX3724(formal/actual 型不一致)で合成失敗。
   - 数値リテラル引数(`axi4_if::<24, 32>`)は両側同一マングルで問題なし。
   - ジェネリックモジュール(async_fifo 等)はインスタンス化サイトでモノモーフ化される
     ため定義・参照とも同じ綴りになり問題なし。
   - `alias interface` を fpga_lib に置く案は「依存プロジェクトの alias は不可視」
     (invisible_identifier)で不可。veryl 0.20.2 が最新(2026-07-14 時点)で上流修正なし。

### 採用した対処: 境界ポートの平ポート化
プロジェクト境界をまたいで接続される fpga_lib モジュールの固定 stream_if ポートを
valid/ready/payload の平ポート 3 本組に変更(line_reader の既存流儀と同じ):
- `stream_writer.i_data` / `stream_reader.o_data`: 内部に `inst data: stream_if::<...>` を
  立てて line_writer/line_reader へ橋渡し。
- `frame_buffer_reader.o_data`: 直接平ポートを駆動。
- `video_signal_generator.i_data`: 平ポート化(`i_data_valid`/`o_data_ready`/`i_data`)。
- `spi_slave`(外側ラッパーのみ)の `o_receive`/`i_send`: 内部 inst で spi_slave_core へ橋渡し。
  spi_slave_core など fpga_lib 内部で閉じる modport は変更不要。
- 接続側(m5stack_hdmi.veryl, fpga_lib の各テスト)は「入力はメンバ式直結、出力は
  ローカル var 経由 + assign」の既存流儀で更新。

## 現在の状態(2026-07-14)

- テスト: fpga_lib 17 + atom_display 4 = **21 全パス**。
- 合成: **成功**。`eda/atomdisplay_veryl/build/impl/pnr/atomdisplay_veryl.fs`(3.5MB)生成。
  - タイミング全達成: clock_main 71.198MHz(制約70)/ clock_video 84.171MHz(制約80)/
    SPI_SCK 132.501MHz(制約80)
  - リソース: Logic 4148/8640(48%)、Register 3964(59%)、**BSRAM 15/26(58%)**
    — 分離前(HISTORY.md §8)と同等。
- project.tcl: 依存 SV の glob(`veryl/dependencies/fpga_lib/src/*/*.sv`)を追加済み。
- 残作業: **実機確認**(ビットストリーム書き込み)→ コミット。

## 合成の前提(再掲・重要)
- `~/gowin/1.9.12/IDE/bin/gwlicense.ini` の `lic=` をノードロックライセンス
  `~/gowin/license/2026/gowin_E_3495DB2B00CF_20260504.lic` に設定済み。
- headless 合成は `QT_QPA_PLATFORM=offscreen ~/gowin/1.9.12/IDE/bin/gw_sh ../project.tcl`。
- project.tcl は `veryl/target/*/*.sv`(atom_display 分)+
  `veryl/dependencies/fpga_lib/src/*/*.sv`(依存分、tb は除外)を glob。
  依存 SV は atom_display の target ではなく `veryl/dependencies/fpga_lib/` に emit される。
