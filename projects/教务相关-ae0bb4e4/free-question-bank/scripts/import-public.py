#!/usr/bin/env python3
"""Build a reproducible, attributed local question bank; no runtime API required."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import tarfile
import io
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = "6dbb24f8d8439041e5431c4c184a582182a6ce9c"
REPO = "OpenLMLab/GAOKAO-Bench"
RAW = f"https://raw.githubusercontent.com/{REPO}/{REVISION}/"
TAGS = {
    "数学": {"集合": ["集合", "\\cap", "\\cup"], "函数": ["函数"], "导数": ["导数", "导函数"], "数列": ["数列", "等差", "等比"], "三角函数": ["三角函数", "\\sin", "\\cos"], "概率统计": ["概率", "抽样", "频率", "方差"], "立体几何": ["三棱", "四棱", "空间", "几何体"], "解析几何": ["椭圆", "双曲线", "抛物线", "圆锥曲线"], "向量": ["向量"], "复数": ["复数", "虚数", "共轭"]},
    "物理": {"力学": ["加速度", "运动", "摩擦力", "动能"], "电磁学": ["电场", "电路", "磁场", "电流"], "光学": ["折射", "光线", "光学"], "热学": ["温度", "理想气体", "热量"]},
    "化学": {"化学反应": ["反应"], "有机化学": ["有机", "乙醇", "苯"], "化学实验": ["实验", "试管"], "离子": ["离子"]},
    "生物": {"细胞": ["细胞"], "遗传": ["遗传", "基因"], "生态": ["生态", "种群"]},
    "语文": {"古诗文": ["诗", "文言"], "语言运用": ["成语", "病句", "语病"], "阅读理解": ["阅读", "文章"]},
}

def read(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Siyue-Free-Question-Bank/1.0"}), timeout=45) as response:
        return response.read()

def subject(path):
    for token, name in [("Math_", "数学"), ("English_", "英语"), ("Chinese_", "语文"), ("Physics_", "物理"), ("Chemistry_", "化学"), ("Biology_", "生物"), ("Geography_", "地理"), ("History_", "历史"), ("Political_Science_", "政治")]:
        if token in path:
            return name
    raise ValueError(path)

def convert(path):
    raw = read(RAW + path)
    rows = json.loads(raw)["example"]
    result = []
    for index, row in enumerate(rows):
        stem = str(row.get("question", "")).strip()
        if not stem:
            continue
        answer = row.get("answer", "")
        answer = "、".join(str(v) for v in answer) if isinstance(answer, list) else str(answer or "")
        sub = subject(path)
        kind = "综合题" if any(t in path for t in ["Reading", "Cloze", "Modern_Lit"]) else "选择题" if "MCQs" in path else "填空题" if "Fill-in" in path or "Fill_in" in path or "Dictation" in path else "解答题"
        flags = []
        if re.search(r"如图|下图|图中|图示|图甲|图乙|图丙|图丁|图\s*[一二三四五六七八九十\d]|见图|如右图|[!\[]\([^)]*\.(?:png|jpg)", stem):
            flags.append("题干可能依赖未附图形")
        if not answer.strip():
            flags.append("缺少参考答案")
        if len(re.findall(r"(?<!\\)\$", stem)) % 2:
            flags.append("公式分隔符需校对")
        if "\\begin{tabular}" in stem:
            flags.append("表格格式需校对")
        if re.search(r"上述|上文|前文|根据材料|根据上述|这首诗|这篇文章", stem) and len(stem) < 220:
            flags.append("可能缺少前置材料")
        tags = [tag for tag, words in TAGS.get(sub, {}).items() if any(word in stem for word in words)]
        result.append({"id": "gk-" + hashlib.sha256(f"{path}:{index}".encode()).hexdigest()[:20], "subject": sub,
            "stage": "高中", "grade": "高中（原始数据未细分）", "type": kind, "year": str(row.get("year", "")),
            "category": str(row.get("category", "")), "stem": stem, "answer": answer.strip(),
            "analysis": str(row.get("analysis", "") or "").strip(), "score": row.get("score", 5),
            "difficulty": "未标注", "tags": tags, "tagMethod": "关键词规则提取，未经教研确认", "issues": flags,
            "source": "GAOKAO-Bench", "sourceUrl": f"https://github.com/{REPO}/blob/{REVISION}/{path}",
            "sourceIndex": index, "license": "仓库声明 Apache-2.0；原始试题权利单独保留", "reviewed": False})
    return result, {"path": path, "sha256": hashlib.sha256(raw).hexdigest(), "count": len(result)}

def main():
    tree = json.loads(read(f"https://api.github.com/repos/{REPO}/git/trees/{REVISION}?recursive=1"))
    paths = sorted(x["path"] for x in tree["tree"] if x["path"].startswith("Data/") and x["path"].endswith(".json"))
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        batches = list(pool.map(convert, paths))
    questions = [q for qs, _ in batches for q in qs]
    # Exact normalized-stem duplicates share one canonical question; keep all provenance.
    unique = {}
    for q in questions:
        key = (q["subject"], re.sub(r"\s+", "", q["stem"]))
        if key in unique:
            unique[key].setdefault("alternateSources", []).append({"url": q["sourceUrl"], "index": q["sourceIndex"]})
        else:
            unique[key] = q
    bank = list(unique.values())
    manifest = {"source": REPO, "revision": REVISION, "fetchedOn": "2026-11-02", "rawCount": len(questions),
        "count": len(bank), "deduplicated": len(questions) - len(bank), "flaggedCount": sum(bool(q["issues"]) for q in bank),
        "coverage": "2010–2022 年高中高考题；不代表小学、初中教材同步题库", "files": [meta for _, meta in batches]}
    out = ROOT / "dist" / "data"
    out.mkdir(parents=True, exist_ok=True)
    (out / "questions.json").write_text(json.dumps(bank, ensure_ascii=False, separators=(",", ":")))
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    (out / "GAOKAO-Bench-LICENSE.txt").write_bytes(read(RAW + "LICENSE"))
    vendor = ROOT / "dist" / "vendor" / "katex"
    vendor.mkdir(parents=True, exist_ok=True)
    version = "0.19.1"
    package = read(f"https://registry.npmjs.org/katex/-/katex-{version}.tgz")
    with tarfile.open(fileobj=io.BytesIO(package), mode="r:gz") as archive:
        for member in archive.getmembers():
            p = member.name
            if not member.isfile():
                continue
            if p in ["package/dist/katex.min.js", "package/dist/katex.min.css", "package/dist/contrib/auto-render.min.js", "package/LICENSE"] or p.startswith("package/dist/fonts/") and p.endswith(".woff2"):
                rel = p.removeprefix("package/dist/") if p.startswith("package/dist/") else "LICENSE.txt"
                dest = vendor / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(archive.extractfile(member).read())
    print(json.dumps({k: v for k, v in manifest.items() if k != "files"}, ensure_ascii=False))

if __name__ == "__main__":
    main()
