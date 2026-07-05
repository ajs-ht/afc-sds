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

出力のスキーマ準拠は次の方式で保証されます。

1. **プロンプト埋め込み + Pydantic事後検証**（既定） — スキーマをシステムプロンプトに
   埋め込み、レスポンスをPydanticで厳密検証（`additionalProperties: false` 相当）してから
   返却します。
2. **Structured Outputs** (`output_config.format`) — `USE_STRUCTURED_OUTPUTS=true` でオプトイン。
   APIレベルの制約付きデコードになりますが、**現行のClaude APIではSDSスキーマが
   コンパイル済みグラマーの複雑度上限を超えるため利用できません**（2026-07に実APIで検証。
   Optionalフィールド20個のフラットなスキーマですら拒否されるため、Optional中心のSDS
   スキーマは載りません。詳細は `app/services/prompts.py` のdocstring参照）。
   有効化した場合もグラマー超過を検知すると方式1へ自動フォールバックし、`warnings` に
   `structured_outputs_unavailable` を付けて通知するため、API側の上限緩和後に安全に
   再テストできます。

どちらの経路でもレスポンスはPydanticで検証されてから返却されます。
Pydantic検証に失敗した場合(かつmax_tokens打ち切りでない場合)は自動で1回だけ再抽出
してから確定させます(再試行時は `warnings` に `retried_invalid_response` を付与、
`usage` は全呼び出しの合算)。
なお `temperature` 等のサンプリングパラメータは現行モデル(Opus 4.7以降)ではAPIから
削除されており指定できないため、抽出の一貫性はプロンプト(逐語転記の指示)で担保しています。

### ドメイン後処理検証 (warnings)

スキーマ検証の後、抽出内容に対する機械的な整合性チェックを行い、疑わしい値を
`warnings` で通知します(結果自体は全量返却されます。人手レビューへの振り分け用)。

| warning | 意味 |
|---|---|
| `invalid_cas_number:<値>` | CAS番号の形式またはチェックディジットが不正 |
| `unknown_pictogram:<値>` | ピクトグラムが標準語彙 (GHS01〜GHS09) 外 |
| `invalid_ghs_code:<値>` | 危険有害性情報/注意書きの先頭がHコード/Pコード様だが形式不正 |
| `invalid_un_number:<値>` | 国連番号が4桁形式 (`1230` / `UN1230`) でない |
| `retried_invalid_response` | 初回応答がスキーマ検証に失敗し、1回の自動再試行で回復 |
| `output_truncated_max_tokens` | 出力がmax_tokensで打ち切られた(JSON自体は有効) |
| `structured_outputs_unavailable` | Structured Outputs要求がグラマー上限超過でフォールバック |

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
