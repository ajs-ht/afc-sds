# afc-sds

SDS（安全データシート／安全管理シート、JIS Z 7253準拠）をAI-OCR（Claude API）で読み取り、
JIS Z 7253の標準16項目構成に沿った構造化JSONを返すAPIアプリケーション。

Web UIやCLIは提供せず、他システムから呼び出されるHTTP API専用アプリです。

## 構成

- FastAPI (Python 3.12)
- Anthropic Claude API（既定 `claude-opus-4-8`、`MODEL_ID` で変更可） — PDF/画像のネイティブ入力 + Structured Outputs (`output_config.format`)
- 認証: `X-API-Key` ヘッダによる簡易認証

### アーキテクチャ概要

`POST /v1/sds/extract` のリクエストは次の順に処理されます。

1. `app/main.py` — ミドルウェアが `request_id` を採番（レスポンスの `X-Request-ID` として返却、
   全ログ・エラー本文に伝播）し、例外は一箇所の `AppError` ハンドラで統一形式に変換します。
2. `app/dependencies.py` — `X-API-Key` の定時間比較による認証。
3. `app/validation/file_validation.py` — サイズ/MIME/PDFページ数の検証と、`pages`
   フォームフィールドによるページ切り出し。
4. `app/services/extraction_service.py` — Claude呼び出し、Pydanticによるスキーマ検証、
   検証失敗時の1回だけの自動再試行。
5. `app/services/postvalidation.py` — CAS番号やGHSピクトグラム等のドメイン固有チェック
   （`warnings` として通知、拒否はしない）。
6. `app/schemas/sds.py` — 出力の型定義 (`SDSDocument`) とJSON Schema。

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

1. **プロンプト埋め込み + Pydantic事後検証**（既定） — スキーマをシステムプロンプトに
   埋め込み、レスポンスをPydanticで厳密検証（`additionalProperties: false` 相当）してから
   返却します。
2. **Structured Outputs** (`output_config.format`) — `USE_STRUCTURED_OUTPUTS=true` でオプトイン。
   APIレベルの制約付きデコードになりますが、**現行のClaude APIではSDSスキーマが
   コンパイル済みグラマーの複雑度上限を超えるため利用できません**（2026-07に `claude-opus-4-8`
   で実API検証。Optionalフィールド20個のフラットなスキーマですら拒否されるため、Optional中心の
   SDSスキーマは載りません。詳細は `app/services/prompts.py` のdocstring参照）。この上限は
   `MODEL_ID` を他モデルに変更した場合は未検証のため、切り替え時は再テストを推奨します。
   有効化した場合もグラマー超過を検知すると方式1へ自動フォールバックし、`warnings` に
   `structured_outputs_unavailable` を付けて通知するため、API側の上限緩和後に安全に
   再テストできます。

どちらの経路でもレスポンスはPydanticで検証されてから返却されます。
Pydantic検証に失敗した場合(かつmax_tokens打ち切りでない場合)は自動で1回だけ再抽出
してから確定させます(再試行時は `warnings` に `retried_invalid_response` を付与、
`usage` は全呼び出しの合算)。
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
切り替えられます（`app/services/extraction_service.py` のリクエスト構築処理にモデル名
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
| `API_KEY` | (必須) | クライアントが `X-API-Key` ヘッダで送る共有シークレット |
| `MAX_UPLOAD_MB` | `32` | アップロードファイルサイズ上限(MB) |
| `MAX_PDF_PAGES` | `50` | PDFの許容ページ数上限 |
| `MAX_OUTPUT_TOKENS` | `24000` | 1回の抽出でClaudeが生成できる最大トークン数 |
| `USE_STRUCTURED_OUTPUTS` | `false` | Structured Outputsへのオプトイン（上記「出力の強制方法」参照） |
| `LOG_LEVEL` | `INFO` | ログレベル |
| `LOG_FORMAT` | `text` | `text` または `json`（下記「ログ」参照） |

このほか、許可するアップロードのMIMEタイプ (`application/pdf` / `image/png` / `image/jpeg` /
`image/webp`) は `app/config.py` の `allowed_mime_types` にハードコードされており、対応する
環境変数はありません（変更する場合はコードを編集してください）。

### ローカル実行 (Docker)

```bash
docker compose up --build
```

Dockerを使わない場合は、依存関係インストール後にuvicornで直接起動できます。

```bash
pip install -e ".[dev]"
uvicorn app.main:app
```

### 動作確認

```bash
curl -X POST http://localhost:8000/v1/sds/extract \
  -H "X-API-Key: <settings.api_key の値>" \
  -F "file=@sample_sds.pdf"
```

多SDS文書の2件目以降を `pages` 指定で再抽出する場合:

```bash
curl -X POST http://localhost:8000/v1/sds/extract \
  -H "X-API-Key: <settings.api_key の値>" \
  -F "file=@sample_sds.pdf" \
  -F "pages=6-11"
```

ヘルスチェック（認証不要）:

```bash
curl http://localhost:8000/healthz
```

## 開発

```bash
pip install -e ".[dev]"
pytest                              # ユニットテスト（実APIキー不要。デフォルトのpytest実行対象）
RUN_INTEGRATION=1 pytest -m integration   # 統合テスト（実ANTHROPIC_API_KEYが必要。実APIを呼ぶため既定では除外）
```

リンタ・フォーマッタは導入していません。

### 実世界のSDSサンプルによる検証

`tests/fixtures/real_world/` に実際のSDSファイル（PDF/PNG/JPEG/WebP）を置くと、
それぞれについて実際に `/v1/sds/extract` を呼び出しJSON生成を検証するテストが有効になります。

```bash
RUN_INTEGRATION=1 ANTHROPIC_API_KEY=sk-ant-... \
  pytest -m integration tests/integration/test_real_world_extraction.py -s
```

- 生成されたJSONは `tests/fixtures/real_world/_results/<ファイル名>.json` に書き出され、目視で確認できます。
- サンプルファイル自体・生成結果はいずれもgitignore対象で、コミットされません（SDSは配布元の著作物のため）。
- サンプルが1件も無い場合はテストが失敗ではなくスキップされるため、通常の開発/CIには影響しません。

#### フィールド単位の精度測定

サンプルの隣に正解データ `<ファイル名>.expected.json`（抽出結果 `data` と同じ形。
検証済みフィールドだけの部分ドキュメントで可）を置くと、構造化フィールドを
フラット化して突合したフィールド単位の一致率レポートが出力され、
`_results/<ファイル名>.accuracy.json` に書き出されます。原文保持用の
`content_markdown` / `raw_text` は表記揺れがあるため採点対象外です。
プロンプトやモデルの変更前後で精度を定量比較する用途を想定しています
（レポートのみで、一致率によってテストが失敗することはありません）。

## API

### `POST /v1/sds/extract`

- ヘッダ: `X-API-Key: <shared secret>`
- リクエスト: `multipart/form-data`、`file` フィールドにPDFまたは画像（PNG/JPEG/WebP）
- 任意フォームフィールド `pages`: PDFの抽出対象ページを1始まり・両端含む形式で指定
  （`"6"` または `"6-11"`）。指定ページのみを切り出してから抽出します。PDF以外への
  指定・形式不正・範囲外は `invalid_page_range` (400) になります。
- レスポンス: JIS Z 7253 16項目の構造化JSON（`data`）+ `warnings` + `usage`（トークン使用量）

すべてのレスポンスに `X-Request-ID` ヘッダが付与されます。この値はサーバーログ
(アクセスログ・トークン使用量ログ)の `request_id` と一致するため、問い合わせ時の
突合に利用できます。

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
| `upstream_error` | 500/503 | Anthropic API側のエラー |
| `internal_error` | 500 | 想定外のサーバーエラー |

### `GET /v1/sds/schema`

- ヘッダ: `X-API-Key: <shared secret>`
- レスポンス: `{"schema_version": "2.1", "json_schema": {...}}`

抽出結果 `data` のJSON Schemaをそのまま返します。連携先システムはこのスキーマを
実行時に取得して、受信JSONのバリデーションや型・クラスのコード生成に利用できます。
`schema_version` が契約のリビジョンを示します。

```bash
curl http://localhost:8000/v1/sds/schema -H "X-API-Key: <settings.api_key の値>"
```

## ログ

- アクセスログ: リクエストごとに `method / path / status / duration_ms / request_id` を1行出力
- トークン使用量ログ: 抽出ごとに入出力トークン数・キャッシュヒット数を `request_id` 付きで出力
- `LOG_FORMAT=json` を設定すると、全ログが1行1 JSONオブジェクトの構造化形式になります(ログ基盤への取り込み用)。デフォルトは `text`。
