# Requirements Document

## Project Description (Input)
GitHub issue #2 (labels: dx, enhancement, size: M, milestone: v1.0) の対応。 kolt の全コマンドに対し、 エラー / 警告 / 情報メッセージを読みやすく整形し、 ANSI カラーで提示し、 設定ファイル (kolt.toml) のエラーには行番号などのコンテキストを付け、 可能な箇所では actionable な提案 ("Did you mean...?", "Run `kolt update` to..." 等) を提示する。 `NO_COLOR` (https://no-color.org/) 環境変数に従う。 v1.0 に向けた Phase 5 (DX improvements) の一部。

## Introduction

kolt は現在、 ユーザー向けエラー / 警告 / 情報メッセージを `eprintln()` で平文 stderr に書き出している。 ANSI カラーは未使用、 ktoml が出すパース失敗の行 / 列情報は catch 時に捨てられ、 unknown command や typo に対する "Did you mean...?" の仕組みも存在しない。 この仕様では、 kolt 自身が生成するすべてのユーザー向けメッセージに対し、 一貫した severity ラベル / 整形 / カラー / コンテキスト / 修復ヒントを導入し、 v1.0 の DX bar (Cargo / Go の組み込みツール水準) を満たす。

## Boundary Context

### In scope
- kolt 自身が生成するエラー / 警告 / 情報メッセージの整形と着色
- `kolt.toml` パース / 検証エラーへのファイルパス + 行番号 (取得可能な場合) コンテキスト
- 依存解決失敗時の座標 / 親座標 / 候補リポジトリの提示
- 未知サブコマンド / 未知 global flag への "Did you mean...?" 提案と recovery command ヒント
- `NO_COLOR` / `--no-color` / TTY による色出力ポリシー
- Linux self-host (現在の唯一サポートプラットフォーム)、 将来 macOS が追加された場合も同じ規約で動作

### Out of scope
- 機械可読出力 (JSON / SARIF) — 別 spec で扱う
- `kotlinc` / `konanc` 自身のコンパイラ診断フォーマットの書き換え (kolt は pass-through に徹する)
- daemon socket protocol message の構造変更
- `kolt run` 等で起動する target program の stdout / stderr の装飾
- 進捗インジケータ / spinner / progress bar (本仕様の対象外、 別 issue で扱う)
- `FORCE_COLOR` 環境変数サポート (v1.0 後に追加検討)
- 既存の `Result<V, E>` ADT の構造変更 (renderer 層で対応するのが本仕様の前提)

### Adjacent expectations
- `--watch` ループの状態行は本仕様の severity / カラー規約を採用するが、 状態行の内容自体は本仕様外。
- daemon log 行は本仕様の stderr / severity prefix 規約を共有するが、 daemon 実装側の責務として個別 PR で揃える。
- `kolt fmt` / `kolt deps` 等の通常成功時のユーザー向け出力は stdout に書き続ける。 本仕様の severity prefix は stderr 専用。

## Requirements

### Requirement 1: Severity-tagged な統一エラー / 警告 / 情報出力

**Objective:** kolt ユーザーとして、 すべての kolt 生成メッセージが同じ severity ラベル / 整形規約で出力されてほしい、 ターミナル上で error / warning / note を視覚的に瞬時に区別するため。

#### Acceptance Criteria
1. The kolt CLI shall prefix every fatal failure message with `error:` written to stderr.
2. The kolt CLI shall prefix every non-fatal advisory message with `warning:` written to stderr.
3. The kolt CLI shall prefix every supplemental note with `note:` written to stderr.
4. When a kolt-originated message includes location or context lines (e.g., file path, coordinate, hint), the kolt CLI shall render each context line on its own line, indented beneath the severity-prefixed headline.
5. The kolt CLI shall continue to write user-requested output (e.g., `kolt run` target program output, `kolt info` results, `kolt deps` listings) to stdout untouched, distinct from the severity-prefixed channel.
6. While reformatting messages under this spec, the kolt CLI shall not change exit codes for any failure class — exit code contracts remain preserved.

### Requirement 2: ANSI カラーポリシー

**Objective:** kolt ユーザーとして、 端末では severity に応じた色を見たいが、 リダイレクト / `NO_COLOR` / 非 TTY 環境では色制御文字が混ざらないでほしい、 出力を grep / log capture / CI ログに通すため。

#### Acceptance Criteria
1. When the destination stream is a TTY and no opt-out is active, the kolt CLI shall emit red ANSI for the `error:` prefix, yellow ANSI for the `warning:` prefix, and a visually distinct third color for the `note:` prefix.
2. When the destination stream is not a TTY (pipe, file redirect), the kolt CLI shall not emit any ANSI escape sequences.
3. If the `NO_COLOR` environment variable is set to any non-empty value, the kolt CLI shall not emit any ANSI escape sequences regardless of TTY state.
4. When the user passes `--no-color` on the kolt invocation, the kolt CLI shall not emit any ANSI escape sequences regardless of TTY state or `NO_COLOR` value.
5. The kolt CLI shall evaluate TTY state independently for stdout and stderr — color is enabled only on streams whose own destination is a TTY.
6. While color is disabled, the kolt CLI shall ensure no information is conveyed solely by color — every severity is identifiable from the leading text token (`error:` / `warning:` / `note:`).

### Requirement 3: kolt.toml 設定エラーへのコンテキスト付与

**Objective:** kolt ユーザーとして、 `kolt.toml` のシンタックス / 検証エラーがどの行で起きたか即座に分かってほしい、 大きな `kolt.toml` を上から目視で追わずに済ませるため。

#### Acceptance Criteria
1. When `kolt.toml` parsing or validation fails, the kolt CLI shall include the absolute path of the offending file in the error output.
2. Where the underlying TOML decoder exposes a line and / or column for the parse failure, the kolt CLI shall include that location in the error output.
3. When a known `kolt.toml` key is given an unsupported value (e.g., wrong type, malformed coordinate, out-of-range integer), the kolt CLI shall name the offending key path (e.g., `[kotlin] compiler`, `[dependencies] foo`) in the error output.
4. Where `kolt.toml` decoding rejects an unknown top-level section name (e.g., `[koltn]` instead of `[kotlin]`), the kolt CLI shall include a "Did you mean...?" suggestion when a known top-level section is within a small edit distance of the unknown one. Did-you-mean for nested keys is out of scope for this spec.

### Requirement 4: 依存解決エラーへのコンテキスト付与

**Objective:** kolt ユーザーとして、 依存解決失敗時に「どの座標が」「どこから要求された」「どこを探した」かが分かってほしい、 transitive 解決のどこで詰まっているか特定するため。

#### Acceptance Criteria
1. When dependency resolution fails for a coordinate, the kolt CLI shall include the full Maven coordinate (group:artifact:version) in the error output.
2. When the failing coordinate is a transitive dependency, the kolt CLI shall include the requesting parent coordinate (or the originating `kolt.toml` declaration when first-level) in the error output.
3. If a requested coordinate cannot be found in any configured repository, the kolt CLI shall list the repositories that were probed.
4. When a checksum or signature verification fails on a downloaded artifact, the kolt CLI shall name the artifact, the expected value, and the observed value in the error output.

### Requirement 5: 修復行動を示す actionable ヒント

**Objective:** kolt ユーザーとして、 エラーごとに次に打つべきコマンドが提示されてほしい、 自分でドキュメントを引かずに修復路に入るため。

#### Acceptance Criteria
1. When a build failure has a known recovery action (e.g., missing or stale lockfile, missing toolchain artifact), the kolt CLI shall append a `note:` line naming the recovery command (e.g., `kolt fetch`, `kolt update`, `kolt toolchain install`).
2. If the user invokes an unknown subcommand, the kolt CLI shall list valid subcommands and, when a near-match exists, include a "Did you mean `<subcommand>`?" suggestion.
3. If the user supplies an unknown global flag, the kolt CLI shall identify the offending flag and, when a near-match exists among supported flags, include a "Did you mean `<flag>`?" suggestion.
4. The kolt CLI shall ensure suggestion strings are deterministic for a given input (no randomness in candidate selection or ordering).

### Requirement 6: 外部プロセス / daemon 出力との整合

**Objective:** kolt 開発者として、 `kotlinc` / `konanc` / daemon からの診断出力が二重装飾されたり色が消されたりしないでほしい、 kolt の整形と外部診断の境界が明確であるため。

#### Acceptance Criteria
1. While forwarding `kotlinc` / `konanc` / daemon stdout or stderr to the user, the kolt CLI shall not prepend its own `error:` / `warning:` / `note:` prefixes to forwarded lines.
2. When the kolt CLI's color policy is enabled and a forwarded subprocess emits ANSI color codes, the kolt CLI shall pass those codes through unaltered.
3. When the kolt CLI's color policy is disabled, the kolt CLI shall ensure forwarded subprocess output does not reach the user with ANSI color codes (either by signaling the subprocess via standard env vars or by stripping codes downstream).
4. When the kolt CLI wraps a subprocess failure with its own headline (e.g., "compilation failed"), the kolt CLI shall emit one severity-prefixed kolt headline followed by the forwarded subprocess output without re-prefixing each forwarded line.

### Requirement 7: 機械可読出力チャネルの保全

**Objective:** kolt CI ユーザーとして、 `kolt info --format=json` 等の構造化出力が本仕様で破壊されないでほしい、 既存パイプラインを書き換えずに済ませるため。

#### Acceptance Criteria
1. While the user requests structured output (e.g., `kolt info --format=json`), the kolt CLI shall not inject ANSI color codes into that structured output stream regardless of TTY state.
2. The kolt CLI shall continue to route structured output to stdout and severity-prefixed diagnostics to stderr — the two streams remain separately consumable by downstream tools.
