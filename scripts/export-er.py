"""Regenerate standalone ER exports from schema.sql using Python's standard library."""
from pathlib import Path
import html
import re

ROOT = Path(__file__).resolve().parent.parent
schema = (ROOT / "src/main/resources/db/schema.sql").read_text(encoding="utf-8")
tables = {}
links = []
for name, body in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)\s*\((.*?)\) ENGINE", schema, re.S):
    columns = []
    primary = []
    nullable = set()
    match = re.search(r"PRIMARY KEY\s*\(([^)]+)\)", body)
    if match:
        primary = [c.strip() for c in match[1].split(",")]
    for line in body.splitlines():
        field = re.match(r"\s*(\w+)\s+(BIGINT|INT|VARCHAR\(\d+\)|CHAR\(\d+\)|BOOLEAN|TIMESTAMP(?:\(\d+\))?|DECIMAL\(\d+,\d+\)|DOUBLE|TEXT|MEDIUMTEXT|ENUM\([^)]+\))", line)
        if field:
            col, kind = field.groups()
            if "PRIMARY KEY" in line:
                primary.append(col)
            columns.append((col, "ENUM" if kind.startswith("ENUM") else kind))
            if "NOT NULL" not in line and col not in primary:
                nullable.add(col)
    foreign = set()
    for child, parent, target in re.findall(r"FOREIGN KEY\s*\(([^)]+)\) REFERENCES (\w+)\(([^)]+)\)", body):
        child_cols = [c.strip() for c in child.split(",")]
        foreign.update(child_cols)
        links.append((name, child_cols, parent, target, bool(primary) and set(primary).issubset(child_cols), any(c in nullable for c in child_cols)))
    tables[name] = (columns, set(primary), foreign)

layout = {
    "users": (0, 0), "categories": (1, 0), "managed_documents": (2, 0), "skills": (3, 0),
    "students": (0, 1), "companies": (1, 1), "cv_versions": (2, 1), "skill_aliases": (3, 1),
    "notifications": (0, 2), "jobs": (1, 2), "job_requirements": (2, 2), "cv_skills": (3, 2),
    "job_circulars": (0, 3), "applications": (1, 3), "cv_current": (2, 3), "cv_evidence": (3, 3),
    "job_circular_versions": (0, 4), "cv_certifications": (2, 4), "cv_evidence_skills": (3, 4),
    "scoring_policies": (0, 5), "scoring_policy_current": (1, 5), "job_catalog_lock": (2, 5), "cv_approvals": (1, 4),
}
assert set(layout) == set(tables), "Update the diagram layout for new/removed tables"
esc = html.escape
positions = {name: (60 + col * 540, 150 + row * 420) for name, (col, row) in layout.items()}
parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="2240" height="2700" viewBox="0 0 2240 2700">',
         '<title>SmartHire complete physical ER diagram</title>',
         '<defs><marker id="arrow" markerWidth="9" markerHeight="9" refX="8" refY="4" orient="auto"><path d="M0 0 L8 4 L0 8" fill="none" stroke="#5e7e94"/></marker></defs>',
         '<rect width="2240" height="2700" fill="#f5f8fb"/>',
         '<g font-family="Segoe UI,Arial,sans-serif" fill="#17324d">',
         '<text x="60" y="55" font-size="32" font-weight="bold">SmartHire | complete physical ER diagram</text>',
         f'<text x="60" y="87" font-size="18">{len(tables)} tables | {len(links)} foreign keys | PK = primary key; FK = foreign key | arrows: child to parent</text>',
         '<text x="60" y="116" font-size="17">All columns shown. Numbered relationships and composite keys are detailed in er-diagram.html. Zoom to read.</text>']
for index, (child, cols, parent, target, one, optional) in enumerate(links, 1):
    x, y = positions[child]
    px, py = positions[parent]
    # Curves run behind the cards; numbered relationship list is the authoritative key mapping.
    sx, sy = x + 230, y
    tx, ty = px + 230, py + 42 + 20 * len(tables[parent][0])
    bend = min(sy, ty) - 25 - index % 4 * 8
    parts.append(f'<path d="M{sx} {sy} C{sx} {bend},{tx} {bend},{tx} {ty}" fill="none" stroke="#8daabd" stroke-width="1.5" marker-end="url(#arrow)"/>')
    parts.append(f'<text x="{sx + 4}" y="{sy - 6 - index % 3 * 12}" font-size="12">#{index}</text>')
for name, (columns, primary, foreign) in tables.items():
    x, y = positions[name]
    height = 48 + len(columns) * 20
    parts.append(f'<rect x="{x}" y="{y}" width="470" height="{height}" rx="8" fill="white" stroke="#a6becd"/>')
    parts.append(f'<path d="M{x+8} {y} H{x+462} Q{x+470} {y} {x+470} {y+8} V{y+36} H{x} V{y+8} Q{x} {y} {x+8} {y}" fill="#17324d"/>')
    parts.append(f'<text x="{x+14}" y="{y+25}" font-size="20" font-weight="bold" fill="white">{name}</text>')
    for i, (col, kind) in enumerate(columns):
        flags = ",".join(flag for flag, group in [("PK", primary), ("FK", foreign)] if col in group)
        parts.append(f'<text x="{x+14}" y="{y+58+i*20}" font-size="16">{esc(col)} <tspan fill="#53738a">{esc(kind)} {flags}</tspan></text>')
parts.append('</g></svg>')
svg = "\n".join(parts)
directory = ROOT / "docs" / "database"
directory.mkdir(parents=True, exist_ok=True)
(directory / "er-diagram.svg").write_text(svg, encoding="utf-8")
rows = []
mermaid = ["erDiagram"]
for child, cols, parent, target, one, optional in links:
    mermaid.append(f'    {parent} {"o|" if optional else "||"}--{"o|" if one else "o{"} {child} : references')
for name, (columns, primary, foreign) in tables.items():
    mermaid.append(f"    {name} {{")
    for col, kind in columns:
        flags = ",".join(flag for flag, group in [("PK", primary), ("FK", foreign)] if col in group)
        mermaid.append(f"        {kind.split('(')[0]} {col}" + (f" {flags}" if flags else ""))
    mermaid.append("    }")
for i, (child, cols, parent, target, one, optional) in enumerate(links, 1):
    rows.append(f'<tr><td>{i}</td><td>{child} ({", ".join(cols)})</td><td>{parent} ({esc(target)})</td><td>{"0..1" if optional else "1"}</td><td>{"0..1" if one else "0..many"}</td></tr>')
(directory / "er-diagram.mmd").write_text("\n".join(mermaid) + "\n", encoding="utf-8")
page = '''<!doctype html><html lang="en"><meta charset="utf-8"><title>SmartHire ER diagram</title>
<style>body{font:16px Segoe UI,Arial;margin:32px;color:#17324d}svg{width:100%;height:auto}table{border-collapse:collapse;width:100%}th,td{border:1px solid #cad9e3;padding:9px;text-align:left}th{background:#edf4f8}a{color:#087f8c}</style>
<h1>SmartHire database</h1><p>Generated from src/main/resources/db/schema.sql. No network or special editor required.
<a href="er-diagram.svg">Open the full SVG to zoom</a>. Arrows point from child to referenced parent.
The last two columns describe parents per child and children per parent, including nullable references.</p>'''
page += svg + '<h2>Exact foreign-key relationships</h2><table><tr><th>#</th><th>Child columns</th><th>Referenced columns</th><th>Parents per child</th><th>Children per parent</th></tr>' + "".join(rows) + '</table>'
page += '''<h2>Constraints beyond the arrows</h2><p>Applications are unique by student/job, including withdrawn records. Their composite student/CV key prevents using another student's review. Current CV pointers reference that student's immutable version. CV evidence skill keys include version/kind/evidence ordinal. Skill aliases are scoped by skill; the service rejects ambiguous normalized phrases. The current scoring policy is a singleton pointer; original application policy/score/explanation remain saved separately. Notifications are deleted only if their owning user is deleted by an explicit maintenance operation; the app only disables accounts. CV approval belongs to the exact confirmed version; new versions and legacy missing decisions are Pending. Reviewer is nullable before a decision and uses SET NULL only for an explicit maintenance deletion; account disablement preserves it.</p>
<p>job_catalog_lock is an independent singleton mutex. Original application policy is an immutable version label, not a database foreign key. Reviewed skills, evidence and certifications belong to CV versions; there is no persisted match cache or redundant mutable student-skill table.</p></html>'''
(directory / "er-diagram.html").write_text(page, encoding="utf-8")
print(f"Exported {len(tables)} tables and {len(links)} foreign keys to docs/database")
