# afc-sds

SDS（安全データシート／安全管理シート、JIS Z 7253準拠）をAI-OCR（Claude API）で読み取り、
JIS Z 7253の標準16項目構成に沿った構造化JSONを返すAPIアプリケーション。

Web UIやCLIは提供せず、他システムから呼び出されるHTTP API専用アプリです。

## 構成

- FastAPI (Python 3.12)
- Anthropic Claude API (`claude-opus-4-8`) — PDF/画像のネイティブ入力 + Structured Outputs (`output_config.format`)
- 認証: `X-API-Key` ヘッダによる簡易認証

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

エラー時は `{"error": {"type": "...", "message": "..."}}` 形式で返却されます。
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
