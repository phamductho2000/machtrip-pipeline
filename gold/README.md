# Gold labels for `extract-eval` / `extract-compare`

`extract_gold.jsonl` is hand-labeled ground truth: one JSON object per line, one line per video.

```json
{"video_id": "7692740401693854983", "places": ["Sương Mù Coffee", "Đồi Mộng Mơ"]}
```

How to label a video:

1. Open the video (or read `extract-report` output for it: caption, transcript, comments).
2. List every **specific, named place or business** that the video (caption, speech or comments) actually names.
   - Write the name the way a person would write it; matching ignores case and diacritics, but not spelling.
   - Cities, districts and provinces (e.g. "Đà Lạt") are NOT places here.
   - If the video names no place ("quán này", "chỗ này"), use an empty list: `"places": []`. That is a valid and
     important label: it tests that the model does not invent a place.
3. Label only videos you have fully checked. Use the 5-10 hardest and 5-10 easiest videos first.

`extract-eval` scores `kind=place` mentions with a non-null `name_raw` against `places`:
precision = correct predicted names / predicted names, recall = correct gold names / gold names
(names compared after lowercasing and stripping diacritics, per video, as sets).
The file is empty on purpose: it is a template for you to fill.
