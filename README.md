# afc-sds

SDS（安全データシート／安全管理シート、JIS Z 7253準拠）をAI-OCR（Claude API）で読み取り、
JIS Z 7253の標準16項目構成に沿った構造化JSONを返すAPIアプリケーション。

Web UIやCLIは提供せず、他システムから呼び出されるHTTP API専用アプリです。

## 構成

- Spring Boot (Java 21) + Maven
- Anthropic Claude API（公式 Java SDK。既定 `claude-opus-4-8`、`MODEL_ID` で変更可） — PDF/画像のネイティブ入力 + Structured Outputs (`output_config.format`)
- 認証: `X-API-Key` ヘッダによる簡易認証

> 本アプリはPython (FastAPI) 実装からの移植です。APIの外部仕様（エンドポイント・
> リクエスト/レスポンス形式・エラー形式・環境変数）は従来と互換です。

### アーキテクチャ概要

`POST /v1/sds/extract` のリクエストは次の順に処理されます（パッケージは `jp.co.ajs.afcsds`）。

1. `web/RequestIdFilter` — `request_id` を採番（レスポンスの `X-Request-ID` として返却、
   全ログ・エラー本文に伝播）し、例外は `web/GlobalExceptionHandler` で統一形式に変換します。
2. `web/ApiKeyInterceptor` — `X-API-Key` の定時間比較による認証（`/v1/**` 全ルート）。
3. `validation/FileValidation` — サイズ/MIME/PDFページ数の検証と、`pages`
   フォームフィールドによるページ切り出し（Apache PDFBox）。
4. `service/ExtractionService` + `service/AnthropicClaudeGateway` — Claude呼び出し、
   JSON Schemaによる厳密検証、検証失敗時の1回だけの自動再試行。
5. `service/PostValidation` — CAS番号やGHSピクトグラム等のドメイン固有チェック
   （`warnings` として通知、拒否はしない）。
6. `schema/SdsSchema` + `schema/SdsDocument` — 出力の型定義とJSON Schema
   （`src/main/resources/sds_json_schema.json`）。

より実装レベルの詳細はエージェント向けの `CLAUDE.md` を参照してください。

### 出力スキーマ (schema_version 2.1)

抽出結果 (`data`) はJIS Z 7253の16項目構成のJSON Schemaに準拠します。
第1〜3項（製品・会社情報 / 危険有害性の要約 / 組成・成分情報）に加え、
第8項（ばく露限界値・保護具）、第9項（物理化学的性質）、第14項（国連番号・容器等級）、
第15項（適用法令）に専用の構造化フィールドがあります。全項目が原文を保持する
`content_markdown` を持ち、数値系フィールドは範囲・単位の原文表記を保つため文字列型です。

スキーマ本体は `GET /v1/sds/schema` で取得できます（下記API参照）。

> **schema_version 2.0 → 2.1 の変更（追加のみ・後方互換）**: 1つのファイルに複数のSDSが
> 含まれる場合に2件目以降の所在を報告する `additional_documents`
> （`{product_name, start_page, end_page}` の配列、通常は空）が追加されました。
> 詳細は下記「複数SDSを含むファイルの扱い」参照。
>
> **schema_version 1.0 からの破壊的変更**: 第8/9/14/15項が汎用の
> `{section_number, section_title_ja, content_markdown}` 形式から構造化フィールド付きの
> 形式に変わりました（既存フィールドは保持、フィールド追加のみ）。

### 出力の強制方法

出力のスキーマ準拠は次の方式で保証されます。

1. **プロンプト埋め込み + JSON Schema事後検証**（既定） — スキーマをシステムプロンプトに
   埋め込み、レスポンスをJSON Schemaで厳密検証（`additionalProperties: false` を含む）してから
   型付きドキュメントへマッピングして返却します。
2. **Structured Outputs** (`output_config.format`) — `USE_STRUCTURED_OUTPUTS=true` でオプトイン。
   APIレベルの制約付きデコードになりますが、**現行のClaude APIではSDSスキーマが
   コンパイル済みグラマーの複雑度上限を超えるため利用できません**（2026-07に `claude-opus-4-8`
   で実API検証。Optionalフィールド20個のフラットなスキーマですら拒否されるため、Optional中心の
   SDSスキーマは載りません。詳細は `service/Prompts.java` のドキュメント参照）。この上限は
   `MODEL_ID` を他モデルに変更した場合は未検証のため、切り替え時は再テストを推奨します。
   有効化した場合もグラマー超過を検知すると方式1へ自動フォールバックし、`warnings` に
   `structured_outputs_unavailable` を付けて通知するため、API側の上限緩和後に安全に
   再テストできます。

どちらの経路でもレスポンスはJSON Schemaで検証されてから返却されます。
検証に失敗した場合(かつmax_tokens打ち切りでない場合)は自動で1回だけ再試行
してから確定させます(再試行時は `warnings` に `retried_invalid_response` を付与、
`usage` は全呼び出しの合算)。再試行は単純な再実行ではなく、失敗した応答と
検証エラーの内容を会話履歴としてモデルに渡し「エラーを解消した完全なJSONを
出力し直す」よう依頼する修正リクエストです(応答にテキストが無い場合のみ
通常の再抽出にフォールバック)。システムプロンプトは変わらないため
プロンプトキャッシュはそのまま効きます。
なお `temperature` 等のサンプリングパラメータは現行モデル(Opus 4.7以降)ではAPIから
削除されており指定できないため、抽出の一貫性はプロンプト(逐語転記の指示)で担保しています。
この前提はOpus系列で確認したものであり、`MODEL_ID` を他モデルに切り替える場合は
サンプリングパラメータの扱いが異なる可能性があるため要再確認です。

### ドメイン後処理検証 (warnings)

スキーマ検証の後、抽出内容に対する機械的な整合性チェックを行い、疑わしい値を
`warnings` で通知します(結果自体は全量返却されます。人手レビューへの振り分け用)。

| warning | 意味 |
|---|---|
| `invalid_cas_number:<値>` | CAS番号の形式またはチェックディジットが不正 |
| `unknown_pictogram:<値>` | ピクトグラムが標準語彙 (GHS01〜GHS09) 外 |
| `invalid_ghs_code:<値>` | 危険有害性情報/注意書きの先頭がHコード/Pコード様だが形式不正 |
| `invalid_un_number:<値>` | 国連番号が4桁形式 (`1230` / `UN1230`) でない |
| `additional_sds_documents_detected` | ファイル内に2件目以降のSDSを検出（`data.additional_documents` 参照） |
| `retried_invalid_response` | 初回応答がスキーマ検証に失敗し、1回の自動再試行で回復 |
| `output_truncated_max_tokens` | 出力がmax_tokensで打ち切られた(JSON自体は有効) |
| `structured_outputs_unavailable` | Structured Outputs要求がグラマー上限超過でフォールバック |

原文に明記された欠落表記（「非該当」「該当しない」「非開示」「適用外」「〜なし」
「不明」、および「―」「-」等の記号のみのセル表記など）がCAS番号・国連番号欄に
転記されている場合は、忠実な転記として扱い `invalid_cas_number` / `invalid_un_number`
の警告対象**外**です。

## セットアップ

`.env` はClaude APIキーやアップロード制限などアプリの実行時設定を保持します。

```bash
cp .env.example .env
# .env を編集して ANTHROPIC_API_KEY / API_KEY を設定
```

### モデルの選択

`MODEL_ID` はデプロイ単位の設定で、値を変えるだけでコード変更なしに使用モデルを
切り替えられます（`service/AnthropicClaudeGateway.java` のリクエスト構築処理にモデル名
依存の分岐はありません）。

- `claude-opus-4-8`（既定） — 精度優先。コストは高め。
- `claude-sonnet-5` — コストパフォーマンス重視の代替。画像/PDFのネイティブ入力に対応する
  現行Claudeモデルであれば同様に利用可能です。

ただし次の2点はOpus系列で確認した前提であり、他モデルに切り替える場合は再確認を推奨します
（上記「出力の強制方法」参照）:

- サンプリングパラメータ(`temperature`等)を送らない実装になっている点
- Structured Outputsのグラマーサイズ上限によりSDSスキーマが載らないと判断している点
  （`USE_STRUCTURED_OUTPUTS=true` で再テスト可能。自動フォールバックがあるため安全に試せます）

主な環境変数（既定値は `.env.example` 参照）:

| 変数 | 既定値 | 説明 |
|---|---|---|
| `ANTHROPIC_API_KEY` | (必須) | Claude呼び出しに使うAnthropic APIキー |
| `MODEL_ID` | `claude-opus-4-8` | 抽出に使うClaudeモデル。コスト重視なら `claude-sonnet-5` 等も指定可（上記「モデルの選択」参照） |
| `API_KEY` | (必須) | クライアントが `X-API-Key` ヘッダで送る共有シークレット。カンマ区切りで複数指定でき、キーローテーション中は新旧両方を受け付けられる |
| `MAX_UPLOAD_MB` | `32` | アップロードファイルサイズ上限(MB) |
| `MAX_PDF_PAGES` | `50` | PDFの許容ページ数上限 |
| `MAX_OUTPUT_TOKENS` | `24000` | 1回の抽出でClaudeが生成できる最大トークン数 |
| `MAX_CONCURRENT_EXTRACTIONS` | `8` | 同時に処理する抽出リクエスト数の上限。超過分はキューに入れず即時 `server_busy` (503, `Retry-After` 付き) で拒否 |
| `ANTHROPIC_MAX_RETRIES` | `2` | Anthropic SDK内蔵の自動リトライ回数(429/5xx/接続エラーが対象。`0` で無効) |
| `DAILY_TOKEN_BUDGET` | `0` | コスト監視: UTC日単位の合計トークン数がこの値を超えたらWARNログを1回出力(拒否はしない)。`0` で無効 |
| `USE_STRUCTURED_OUTPUTS` | `false` | Structured Outputsへのオプトイン（上記「出力の強制方法」参照） |
| `LOG_LEVEL` | `INFO` | ログレベル |
| `LOG_FORMAT` | `text` | `text` または `json`（下記「ログ」参照） |

このほか、許可するアップロードのMIMEタイプ (`application/pdf` / `image/png` / `image/jpeg` /
`image/webp`) は `config/AppSettings.java` の `ALLOWED_MIME_TYPES` にハードコードされており、
対応する環境変数はありません（変更する場合はコードを編集してください）。

### ローカル実行 (Docker)

```bash
docker compose up --build
```

SIGTERM受信時(再起動・デプロイ時)はグレースフルシャットダウンし、処理中の
抽出リクエストの完了を最大5分待ってから終了します(新規リクエストの受付は
即時停止)。

Dockerを使わない場合は、環境変数を設定してMavenで直接起動できます（要 Java 21）。

```bash
ANTHROPIC_API_KEY=sk-ant-... API_KEY=change-me mvn spring-boot:run
```

（`.env` の自動読み込みはDocker実行時のみです。ローカル起動では環境変数を
シェルから渡してください。）

### 動作確認

```bash
curl -X POST http://localhost:8000/v1/sds/extract \
  -H "X-API-Key: <API_KEY の値>" \
  -F "file=@sample_sds.pdf"
```

多SDS文書の2件目以降を `pages` 指定で再抽出する場合:

```bash
curl -X POST http://localhost:8000/v1/sds/extract \
  -H "X-API-Key: <API_KEY の値>" \
  -F "file=@sample_sds.pdf" \
  -F "pages=6-11"
```

ヘルスチェック（認証不要）:

```bash
curl http://localhost:8000/healthz
```

## 開発

```bash
mvn test                            # ユニットテスト + カバレッジゲート（実APIキー不要）
mvn test -Dtest=FileValidationTest  # テストクラス単位の実行

# 統合テスト（実ANTHROPIC_API_KEYが必要。実APIを呼ぶため既定では自動スキップ）
RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... mvn test -Dtest=LiveExtractionTest
```

リンタ・フォーマッタは導入していません。

### CI

GitHub Actions（`.github/workflows/ci.yml`）が push / PR ごとにユニットテストを
実行し、JaCoCoの行カバレッジが 90% を下回ると失敗します（Anthropic SDKアダプタ
`AnthropicClaudeGateway` と起動クラスはゲート対象外。ライブAPIテストは環境変数が
無い限り自動スキップされるため、CI に API キーは不要です）。あわせて Docker
イメージのビルド検証も行います。

このほか:

- `.github/workflows/live-api-check.yml` — 週次（+手動実行可）の実APIドリフト
  検知。`USE_STRUCTURED_OUTPUTS=true` で実行するため、Anthropic側のグラマー
  サイズ上限が緩和されてStructured Outputsが利用可能になったタイミングを
  グリーン転化で検知できます。リポジトリシークレット `ANTHROPIC_API_KEY` が
  未設定なら自動スキップします。
- `.github/dependabot.yml` — Maven依存とGitHub Actionsの更新PRを週次で作成します。

### スキーマ変更時の手順

出力スキーマ（`src/main/resources/sds_json_schema.json` と `schema/SdsDocument.java`）は
バージョン付きの公開契約です。出力の形を変える変更は
`SdsSchemaTest.schemaChangeRequiresVersionBump` が検知して失敗します。その場合は
次の4点をセットで行ってください:

1. `src/main/resources/sds_json_schema.json` と `schema/SdsDocument.java` を同時に更新
2. `schema/SdsSchema.java` の `SCHEMA_VERSION` をバンプ
3. README の該当箇所（[出力スキーマ](#出力スキーマ-schema_version-21) など）を更新
4. スナップショット `src/test/resources/fixtures/schema_snapshot.json` を再生成
   （`{"schema_version": "<新バージョン>", "schema": <スキーマ本体>}` の形式）

## API

HTTP層の契約(エンドポイント・認証・エラー形式)は `docs/openapi.yaml` に
OpenAPI 3.1として記述しています。抽出結果 `data` の詳細スキーマの正本は
あくまで `GET /v1/sds/schema` が返すバージョン付きJSON Schemaです。

### `POST /v1/sds/extract`

- ヘッダ: `X-API-Key: <shared secret>`
- リクエスト: `multipart/form-data`、`file` フィールドにPDFまたは画像（PNG/JPEG/WebP）
- 任意フォームフィールド `pages`: PDFの抽出対象ページを1始まり・両端含む形式で指定
  （`"6"` または `"6-11"`）。指定ページのみを切り出してから抽出します。PDF以外への
  指定・形式不正・範囲外は `invalid_page_range` (400) になります。
- レスポンス: JIS Z 7253 16項目の構造化JSON（`data`）+ `warnings` + `usage`（トークン使用量）

すべてのレスポンスに `X-Request-ID` ヘッダが付与されます。この値はサーバーログ
(アクセスログ・トークン使用量ログ)の `request_id` と一致するため、問い合わせ時の
突合に利用できます。呼び出し元システムが自前の相関IDを持つ場合は、リクエストの
`X-Request-ID` ヘッダで渡せばそのまま採用されます(英数と `.`/`_`/`-` のみ・
64文字以内。形式外の値はUUIDに置き換え)。

同時に処理する抽出数は `MAX_CONCURRENT_EXTRACTIONS`(既定8)で制限されます。
上限到達中のリクエストはキューに入れず `server_busy` (503) を即時返却し、
`Retry-After` ヘッダで再試行までの待機秒数を通知します。クライアント側は
このヘッダに従ってリトライしてください。

#### 複数SDSを含むファイルの扱い

1つのPDFに複数製品のSDSが連結されている場合、抽出対象は**常に先頭の1件**です。
2件目以降は `data.additional_documents` に製品名とページ範囲
（`{product_name, start_page, end_page}`、1始まり・両端含む）が報告され、
`warnings` に `additional_sds_documents_detected` が付きます。

連携先システムは次のループで全SDSを取得できます:

1. `POST /v1/sds/extract` でファイルを送信（1件目の抽出結果を取得）
2. `warnings` に `additional_sds_documents_detected` があれば、
   `data.additional_documents` の各エントリについて
   `pages=<start_page>-<end_page>` を付けて同じファイルを再POST
3. すべてのエントリを処理したら完了

> ページ範囲はモデルが文書構造から推定した値のため、稀に±1ページ程度の誤差が
> あり得ます。再抽出結果の `extraction_notes` に前後の文書の混入が示唆される場合は
> 範囲を1ページ広げて再試行してください。

エラー時は `{"error": {"type": "...", "message": "...", "request_id": "..."}}` 形式で返却されます。
主なエラー種別:

| type | HTTP | 説明 |
|---|---|---|
| `unauthorized` | 401 | `X-API-Key` 不正/欠落 |
| `unsupported_file_type` | 400 | 非対応のファイル形式(申告されたContent-Typeと実バイト列の不一致を含む) |
| `file_too_large` | 400 | アップロードサイズ超過 |
| `too_many_pages` | 400 | PDFページ数超過 |
| `empty_file` | 400 | アップロードファイルが空 |
| `invalid_page_range` | 400 | `pages` の形式不正・範囲外・PDF以外への指定 |
| `invalid_document` | 400 | Anthropic APIがドキュメント内容自体を処理不能と判断 |
| `validation_error` | 422 | リクエストの検証エラー(例: `file` 未指定) |
| `extraction_refused` | 422 | Claudeが安全上の理由で処理を拒否 |
| `extraction_truncated` | 502 | 出力がmax_tokensで途中終了しJSON検証に失敗 |
| `extraction_invalid_response` | 502 | Claudeの出力がSDSスキーマに適合しない(再試行後も) |
| `server_busy` | 503 | 同時抽出数が `MAX_CONCURRENT_EXTRACTIONS` に達している。`Retry-After` ヘッダの秒数後に再試行 |
| `upstream_error` | 500/503 | Anthropic API側のエラー(レート制限起因の503には `Retry-After` ヘッダ付き) |
| `internal_error` | 500 | 想定外のサーバーエラー |

### `GET /v1/sds/schema`

- ヘッダ: `X-API-Key: <shared secret>`
- レスポンス: `{"schema_version": "2.1", "json_schema": {...}}`

抽出結果 `data` のJSON Schemaをそのまま返します。連携先システムはこのスキーマを
実行時に取得して、受信JSONのバリデーションや型・クラスのコード生成に利用できます。
`schema_version` が契約のリビジョンを示します。

```bash
curl http://localhost:8000/v1/sds/schema -H "X-API-Key: <API_KEY の値>"
```

## ログ

- アクセスログ: リクエストごとに `method / path / status / duration_ms / request_id` を1行出力
- トークン使用量ログ: 抽出ごとに入出力トークン数・キャッシュヒット数を `request_id` 付きで出力
- `LOG_FORMAT=json` を設定すると、全ログが1行1 JSONオブジェクトの構造化形式になります(ログ基盤への取り込み用)。デフォルトは `text`。JSON形式ではリクエスト処理中の全ログ行に `request_id` フィールドが付与されます(メッセージ本文のパース不要でリクエスト単位のフィルタが可能)。
- `DAILY_TOKEN_BUDGET` を設定すると、UTC日単位の合計消費トークンが予算を超えた時点でWARNログを出力します(コスト暴走の早期検知用。リクエスト拒否はしません)。
