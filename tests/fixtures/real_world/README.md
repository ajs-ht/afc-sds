# real_world/

Local-only home for real-world SDS files used by
`tests/integration/test_real_world_extraction.py`.

- Drop real SDS PDFs/images here (`.pdf`, `.png`, `.jpg`, `.jpeg`, `.webp`).
- Everything you place here (and the `_results/` output it produces) is
  gitignored — real manufacturer SDS documents are third-party copyrighted
  material and must not be committed to this repository.
- If the directory is empty, the real-world test is skipped rather than
  failed, so this is safe to leave empty for normal development/CI.
- Optional accuracy scoring: put a ground-truth file next to a sample as
  `<name>.expected.json` (same shape as the extraction `data`; a partial
  document with only the fields you verified is fine). The test then writes
  a field-level match report to `_results/<name>.accuracy.json`. Expected
  files are gitignored along with everything else here.
