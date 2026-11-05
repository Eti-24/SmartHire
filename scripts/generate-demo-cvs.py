"""Optional artifact regeneration only: Python + ReportLab; not needed to run SmartHire."""
from pathlib import Path
import shutil
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "output" / "pdf"
FIXTURES = ROOT / "src" / "test" / "resources" / "demo"
OUTPUT.mkdir(parents=True, exist_ok=True)
FIXTURES.mkdir(parents=True, exist_ok=True)
SAMPLES = [
    ("mandatory-gap", "Tanvir Ahmed", "SQL, communication, teamwork", "SQL reporting dashboard", "SQL"),
    ("skill-gap", "Nusrat Jahan", "Java", "Java study planner", "Java"),
    ("eligible", "Arafat Hasan", "Java, SQL, communication, teamwork", "Java and SQL library system", "Java, SQL"),
]
for slug, name, skills, project, tags in SAMPLES:
    path = OUTPUT / f"demo-cv-{slug}.pdf"
    doc = canvas.Canvas(str(path), pagesize=A4, pageCompression=1, invariant=1)
    doc.setTitle(f"Fictional CV - {name}")
    doc.setAuthor("SmartHire fictional demonstration")
    doc.setFillColor(colors.HexColor("#17324d"))
    doc.rect(0, 690, A4[0], 152, fill=1, stroke=0)
    doc.setFillColor(colors.white)
    doc.setFont("Helvetica-Bold", 26)
    doc.drawString(46, 776, name)
    doc.setFont("Helvetica", 12)
    doc.drawString(46, 748, "Graduate developer | Fictional demonstration CV")
    doc.drawString(46, 724, f"{slug}@smarthire.example | Dhaka")
    y = 650
    for title, lines in [
        ("PROFILE", ["Graduate interested in practical software and team projects."]),
        ("SKILLS", [f"Skills: {skills}"]),
        ("EDUCATION", ["Bachelor in Computer Science"]),
        ("PROJECT", [f"Project: {project}", f"Project technologies: {tags}"]),
        ("AVAILABILITY", ["Available for a graduate role. No employment history claimed."]),
    ]:
        doc.setFillColor(colors.HexColor("#087f8c"))
        doc.setFont("Helvetica-Bold", 12)
        doc.drawString(46, y, title)
        y -= 26
        doc.setFillColor(colors.HexColor("#243746"))
        doc.setFont("Helvetica", 12)
        for line in lines:
            doc.drawString(46, y, line)
            y -= 21
        y -= 27
    doc.setStrokeColor(colors.HexColor("#d7e1e8"))
    doc.line(46, 86, A4[0] - 46, 86)
    doc.setFont("Helvetica", 10)
    doc.setFillColor(colors.HexColor("#526574"))
    doc.drawString(46, 65, "All names, qualifications and contact details are fictional.")
    doc.drawString(46, 48, "Review extracted qualifications in SmartHire before confirming. | Page 1")
    doc.save()
    shutil.copyfile(path, FIXTURES / path.name)
    print(path.relative_to(ROOT))
