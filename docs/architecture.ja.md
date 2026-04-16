# アーキテクチャ

kolt は単一の Kotlin/Native バイナリとして配布される Kotlin ビルドツールです。宣言的な `kolt.toml` を読み込み、Maven リポジトリから依存を解決し、Unix ソケット経由のウォーム JVM コンパイラデーモンでコンパイルします。デーモンが利用できない場合はサブプロセス呼び出しにフォールバックします。

## コンポーネント

```
┌─────────────────────────────────────────────┐
│              kolt (Kotlin/Native)            │
│                                             │
│  cli/        コマンドディスパッチ、--watch   │
│  config/     TOML パース、KoltPaths         │
│  build/      コンパイルパイプライン、キャッシュ│
│  resolve/    Maven/POM/Gradle メタデータ     │
│  daemon/     Unix ソケットクライアント        │
│  tool/       ツールチェイン管理(kotlinc/JDK) │
│  infra/      プロセス、ファイルシステム       │
└────────────────┬────────────────────────────┘
                 │ Unix ソケット（長さプレフィクス付き JSON）
┌────────────────▼────────────────────────────┐
│       kolt-compiler-daemon (JVM)            │
│                                             │
│  server/     ソケットリスナー、ライフサイクル │
│  protocol/   ワイヤ型、フレームコーデック     │
│  ic/         BTA インクリメンタルコンパイラ   │
│  reaper/     古い IC 状態のクリーンアップ     │
└─────────────────────────────────────────────┘
```

### ネイティブクライアント (`src/nativeMain/kotlin/kolt/`)

| パッケージ | 責務 |
|-----------|------|
| `cli` | 引数をパースし、コマンドをディスパッチ（`build`、`run`、`test`、`check`、`init`、`clean`、`fmt`、`deps`、`daemon`、`toolchain`）。ウォッチモードループ。 |
| `config` | `kolt.toml` を `KoltConfig` にパース。`~/.kolt/` パスの管理。 |
| `build` | JVM および Native ターゲット向けのコンパイラコマンド構築。デーモンとサブプロセスの実装を持つ `CompilerBackend` 抽象化。ビルドキャッシュ（mtime）。テストコンパイルと JUnit Platform 実行。 |
| `resolve` | POM/Gradle モジュールメタデータによる推移的依存解決。ロックファイル（v2、SHA-256 ハッシュ）。プラグイン JAR の取得。 |
| `daemon` | `DaemonCompilerBackend` — 指数バックオフ付きの接続またはスポーン。フレームコーデック（4 バイト長 + JSON）。デーモン JAR および BTA impl JAR の解決。ブートストラップ JDK のプロビジョニング。 |
| `tool` | `~/.kolt/toolchains/` 配下で kotlinc、konanc、JDK をダウンロード・管理。 |
| `infra` | `fork`/`execvp` プロセス実行、`spawnDetached`（デーモン用ダブルフォーク）、Unix ソケットクライアント、inotify、SHA-256、HTTP ダウンロード（libcurl cinterop）。 |

### コンパイラデーモン (`kolt-compiler-daemon/`)

ビルド間でウォーム状態を維持する JVM プロセス。ネイティブクライアントからダブルフォークで起動され、Unix ドメインソケットで通信します。

| パッケージ | 責務 |
|-----------|------|
| `server` | ソケット接続を受け付け、`Compile` → `CompileResult` をディスパッチ。ライフサイクル管理（アイドルタイムアウト、最大コンパイル数、ヒープウォーターマーク）。 |
| `protocol` | ネイティブ側とミラーするワイヤ型。Java NIO `SocketChannel` 用フレームコーデック。kotlinc stderr の診断パーサー。 |
| `ic` | `BtaIncrementalCompiler` — kotlin-build-tools-api 上のアダプター。プロジェクトごとのファイルロック、クラスパススナップショットキャッシュ、プラグイン変換。`SelfHealingIncrementalCompiler` が内部エラー時にキャッシュワイプ＆リトライでラップ。 |
| `reaper` | アクティブでなくなったプロジェクトの古い IC 状態を削除するバックグラウンドスレッド。 |

デーモン内の ClassLoader 階層：

```
daemon classloader
  └─ SharedApiClassesClassLoader (org.jetbrains.kotlin.buildtools.api.*)
      └─ URLClassLoader (kotlin-build-tools-impl + プラグイン JAR)
```

## ビルドフロー

JVM ターゲットでの `kolt build`：

1. `kolt.toml` をパース → `KoltConfig`
2. ビルドキャッシュをチェック（ソースの mtime と前回ビルドの比較）
3. 依存を解決 — ロックファイルを読むか推移的解決を実行、JAR を `~/.kolt/cache/` にダウンロード
4. コンパイラバックエンドを選択 — デーモン（Unix ソケット）を試行、サブプロセスにフォールバック
5. ソースをコンパイル → `build/classes/`
6. パッケージング → `build/{name}.jar`（`-include-runtime` 付き fat JAR）

Native ターゲットでは、ステップ 5〜6 で konanc を 2 段階（ライブラリ → リンク）で使用します。これは konanc のプラグインに関する問題への回避策です（ADR 0014）。

## デーモンライフサイクル

- **スポーン**：ネイティブクライアントが `spawnDetached()` を呼び出し（ダブルフォーク + setsid）。デーモンは `~/.kolt/daemon/{projectHash}/daemon.sock` にバインド。
- **接続**：クライアントが指数バックオフでリトライ（10〜200 ms、3 秒バジェット）。
- **シャットダウン**：アイドルタイムアウト、最大コンパイル数到達、ヒープウォーターマーク超過、または明示的な `kolt daemon stop`。
- **古いデータのクリーンアップ**：`DaemonReaper` が孤立したソケットとディレクトリを削除。`IcReaper` が未使用の IC 状態を削除。

## 依存解決

- POM メタデータ上の BFS で推移的依存を解決
- Maven リポジトリ、Gradle モジュールメタデータ、バージョンインターバル、exclusions に対応
- グローバルキャッシュ：`~/.kolt/cache/`（Maven 互換レイアウト）
- `kolt.lock` がバージョンと SHA-256 ハッシュの単一の信頼ソース
- ハッシュ不一致 → ハードエラー（暗黙の再ダウンロードなし）

## エラー処理

すべての関数は `Result<V, E>`（kotlin-result）を返します。例外のスローは禁止されています。

コンパイルのフォールバックチェーン：デーモンバックエンド → サブプロセスバックエンド。デーモンの `SelfHealingIncrementalCompiler` は内部エラー時に IC 状態をワイプして 1 回リトライします。

## スコープ外

- **タスクランナー** — npm scripts スタイルのカスタムコマンド定義は行わない。ビルドライフサイクルフックは別の議論（[#119](https://github.com/snicmakino/kolt/issues/119)）。
- **プラグインシステム** — インプロセス拡張 API は提供しない。kolt は Kotlin/Native バイナリであり JVM プラグインをロードできない。[ADR 0021](adr/0021-no-plugin-system.md) を参照。
- **Kotlin Multiplatform (KMP)** — 単一プロジェクトで複数ターゲットを管理する機能。複雑性が高く、Gradle との差別化ポイントではない。
- **Android** — AGP の再実装は現実的ではない。
- **IDE 統合** — IntelliJ/VSCode プラグイン。ツール自体をまず成立させることが先。

## ADR

アーキテクチャ上の決定事項は `docs/adr/` に記録されています。主要なもの：

- [0001](adr/0001-result-type-error-handling.md) — Result 型エラー処理
- [0004](adr/0004-pure-io-separation.md) — リゾルバの Pure/IO 分離
- [0016](adr/0016-warm-jvm-compiler-daemon.md) — ウォーム JVM コンパイラデーモン
- [0019](adr/0019-incremental-build-kotlin-build-tools-api.md) — BTA によるインクリメンタルビルド
- [0021](adr/0021-no-plugin-system.md) — プラグインシステム不採用
