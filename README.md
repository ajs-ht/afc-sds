# afc-sds

SDS（安全データシート／安全管理シート、JIS Z 7253準拠）をAI-OCR（Claude API）で読み取り、
JIS Z 7253の標準16項目構成に沿った構造化JSONを返すAPIアプリケーション。

Web UIやCLIは提供せず、他システムから呼び出されるHTTP API専用アプリです。

## 構成

- FastAPI (Python 3.12)
- Anthropic Claude API (`claude-opus-4-8`) — PDF/画像のネイティブ入力 + Structured Outputs (`output_config.format`)
- 認証: `X-API-Key` ヘッダによる簡易認証

### 出力スキーマ (schema_version 2.0)

抽出結果 (`data`) はJIS Z 7253の16項目構成のJSON Schemaに準拠します。
第1〜3項（製品・会社情報 / 危険有害性の要約 / 組成・成分情報）に加え、
第8項（ばく露限界値・保護具）、第9項（物理化学的性質）、第14項（国連番号・容器等級）、
第15項（適用法令）に専用の構造化フィールドがあります。全項目が原文を保持する
`content_markdown` を持ち、数値系フィールドは範囲・単位の原文表記を保つため文字列型です。

スキーマ本体は `GET /v1/sds/schema` で取得できます（下記API参照）。

> **schema_version 1.0 からの破壊的変更**: 第8/9/14/15項が汎用の
> `{section_number, section_title_ja, content_markdown}` 形式から構造化フィールド付きの
> 形式に変わりました（既存フィールドは保持、フィールド追加のみ）。

### 出力の強制方法

出力のスキーマ準拠は二段構えで保証されます。

1. **Structured Outputs** (`output_config.format`) — APIレベルの制約付きデコード（既定で有効）。
2. **プロンプト埋め込み + Pydantic事後検証** — スキーマのコンパイル済みグラマーが
   APIのサイズ上限を超えた場合、自動でこちらへフォールバックし、レスポンスの
   `warnings` に `structured_outputs_unavailable` を付けて通知します。
   `USE_STRUCTURED_OUTPUTS=false` で最初からこちらの方式に固定できます。

どちらの経路でもレスポンスはPydanticで検証されてから返却されます。

## セットアップ

```bash
cp .env.example .env
# .env を編集して ANTHROPIC_API_KEY / API_KEY を設定
```

### ローカル実行 (Docker)

```bash
docker compose up --build
```

### 動作確認

```bash
curl -X POST http://localhost:8000/v1/sds/extract \
  -H "X-API-Key: <settings.api_key の値>" \
  -F "file=@sample_sds.pdf"
```

ヘルスチェック（認証不要）:

```bash
curl http://localhost:8000/healthz
```

## 開発

```bash
pip install -e ".[dev]"
pytest                              # ユニットテスト（実APIキー不要）
RUN_INTEGRATION=1 pytest -m integration   # 統合テスト（実ANTHROPIC_API_KEYが必要）
```

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

## API

### `POST /v1/sds/extract`

- ヘッダ: `X-API-Key: <shared secret>`
- リクエスト: `multipart/form-data`、`file` フィールドにPDFまたは画像（PNG/JPEG/WebP）
- レスポンス: JIS Z 7253 16項目の構造化JSON（`data`）+ `warnings` + `usage`（トークン使用量）

すべてのレスポンスに `X-Request-ID` ヘッダが付与されます。この値はサーバーログ
(アクセスログ・トークン使用量ログ)の `request_id` と一致するため、問い合わせ時の
突合に利用できます。

エラー時は `{"error": {"type": "...", "message": "...", "request_id": "..."}}` 形式で返却されます。
主なエラー種別:

| type | HTTP | 説明 |
|---|---|---|
| `unauthorized` | 401 | `X-API-Key` 不正/欠落 |
| `unsupported_file_type` | 400 | 非対応のファイル形式 |
| `file_too_large` | 400 | アップロードサイズ超過 |
| `too_many_pages` | 400 | PDFページ数超過 |
| `extraction_refused` | 422 | Claudeが安全上の理由で処理を拒否 |
| `extraction_truncated` | 502 | 出力がmax_tokensで途中終了しJSON検証に失敗 |
| `upstream_error` | 500/503 | Anthropic API側のエラー |

### `GET /v1/sds/schema`

- ヘッダ: `X-API-Key: <shared secret>`
- レスポンス: `{"schema_version": "2.0", "json_schema": {...}}`

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
