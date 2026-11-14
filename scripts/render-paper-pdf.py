"""Render the verified manuscript, generated evidence and scientific appendices."""
import os
import subprocess
from pathlib import Path
from urllib.parse import urlsplit
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape, quoteattr

import reportlab
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import (CondPageBreak, Image, LongTable, Paragraph, Preformatted,
                                SimpleDocTemplate, Spacer, TableStyle)

INK = colors.HexColor("#25303c")
ACCENT = colors.HexColor("#17566b")
WIDTH = A4[0] - 96
SECTIONS = ("paper/manuscript.md", "paper/generated/public-corpus-splits.md",
            "paper/generated/public-controller-results.md", "paper/limitations.md",
            "paper/claims-and-evidence.md", "paper/artifact-appendix.md")
WIDTHS = {
    "Challenge": (.38, .13, .17, .13, .19),
    "ID": (.065, .32, .39, .225),
    "Full-batch outputs": (.16, .11, .08, .12, .08, .09, .11, .25),
    "Stage": (.13, .18, .24, .12, .11, .1, .12),
}


def style(name, font="Vera", size=9.5, leading=14.6, **kw):
    return ParagraphStyle(name, fontName=font, fontSize=size, leading=leading, textColor=INK, **kw)


BODY = style("body", spaceAfter=7)
CELL = style("cell", size=7.3, leading=9.8)
HEAD = style("column", "VeraBold", 7.3, 9.7)
LEVELS = [style("title", "VeraBold", 24, 29, spaceAfter=14, keepWithNext=True),
          style("section", "VeraBold", 15, 19, spaceBefore=9, spaceAfter=6, keepWithNext=True),
          style("subsection", "VeraBold", 11, 15, spaceBefore=6, spaceAfter=4, keepWithNext=True)]


def rich(node, source, root, revision):
    result = escape(node.text or "")
    for child in node:
        content = rich(child, source, root, revision)
        if child.tag in ("strong", "em"):
            tag = {"strong": "b", "em": "i"}[child.tag]
            content = f"<{tag}>{content}</{tag}>"
        elif child.tag == "a":
            href = child.attrib["href"]
            if not urlsplit(href).scheme:
                target = (source.parent / href).resolve()
                if not target.is_file() or not target.is_relative_to(root):
                    raise ValueError(f"unbound manuscript reference: {href}")
                href = f"https://github.com/carstenartur/Regelsuche/blob/{revision}/{target.relative_to(root).as_posix()}"
            if not href.startswith("https://"):
                raise ValueError(f"unsupported manuscript reference: {href}")
            content = f'<a href={quoteattr(href)} color="#17566b"><u>{content}</u></a>'
        elif child.tag == "code":
            content = f'<font color="#21596a">{content}</font>'
        else:
            raise ValueError(f"unsupported manuscript inline markup: {child.tag}")
        result += content + escape(child.tail or "")
    return result


def table(node, source, root, revision):
    heading = [rich(th, source, root, revision) for th in node.findall("thead/tr/th")]
    widths = next((value for key, value in WIDTHS.items() if key in heading), None)
    if widths is None or len(widths) != len(heading):
        raise ValueError(f"unsupported manuscript table columns: {heading}")
    rows = [[Paragraph(text, HEAD) for text in heading]]
    for row in node.findall("tbody/tr"):
        cells = [Paragraph(rich(cell, source, root, revision), CELL) for cell in row]
        if len(cells) != len(heading):
            raise ValueError("table row does not match headers")
        rows.append(cells)
    result = LongTable(rows, colWidths=[v * WIDTH for v in widths], repeatRows=1, hAlign="LEFT", spaceBefore=3)
    result.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#dceaf0")),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f4f7f8")]),
        ("LINEBELOW", (0, 0), (-1, 0), .7, ACCENT), ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 4), ("RIGHTPADDING", (0, 0), (-1, -1), 4),
        ("TOPPADDING", (0, 0), (-1, -1), 3), ("BOTTOMPADDING", (0, 0), (-1, -1), 3)]))
    return result


def article(source, root, revision, png):
    data = subprocess.check_output(["pandoc", "--from=gfm", "--to=html5", "--wrap=none", source])
    html = ET.fromstring(b"<article>" + data + b"</article>")
    items = []
    for node in html:
        if node.tag in ("h1", "h2", "h3"):
            heading = LEVELS[int(node.tag[1])-1]
            # Long tables can span pages; keep only their header/first rows with the title.
            if node.tag == "h2" and node.text == "Resource accounts by policy":
                items.append(CondPageBreak(105))
                heading = heading.clone("table section", keepWithNext=False)
            items.append(Paragraph(rich(node, source, root, revision), heading))
        elif node.tag == "p" and node.find("img") is not None:
            image = node.find("img")
            if image.get("src") != "public-controller-allocation.svg":
                raise ValueError("unknown generated paper figure")
            items.extend([Image(str(png), width=WIDTH, height=WIDTH * 199 / 600), Spacer(1, 8)])
        elif node.tag == "p":
            items.append(Paragraph(rich(node, source, root, revision), BODY))
        elif node.tag == "pre":
            items.append(Preformatted("".join(node.itertext()).rstrip(), style("flow", "Courier", 8, 11, leftIndent=13, spaceAfter=8)))
        elif node.tag == "blockquote":
            for para in node.findall("p"):
                items.append(Paragraph(rich(para, source, root, revision), style("quote", "VeraItalic", leftIndent=15, spaceAfter=8)))
        elif node.tag in ("ol", "ul"):
            for idx, li in enumerate(node.findall("li"), 1):
                items.append(Paragraph(rich(li, source, root, revision), style("item", leftIndent=16, bulletIndent=2, spaceAfter=3),
                                       bulletText=f"{idx}." if node.tag == "ol" else "•"))
        elif node.tag == "table":
            items.extend([table(node, source, root, revision), Spacer(1, 9)])
        else:
            raise ValueError(f"unsupported manuscript block: {node.tag}")
    return items


def render(root, revision, output):
    fonts = Path(reportlab.__file__).parent / "fonts"
    for name, font in (("Vera", "Vera"), ("VeraBold", "VeraBd"), ("VeraItalic", "VeraIt"), ("VeraBoldItalic", "VeraBI")):
        pdfmetrics.registerFont(TTFont(name, str(fonts / (font + ".ttf"))))
    pdfmetrics.registerFontFamily("Vera", normal="Vera", bold="VeraBold", italic="VeraItalic", boldItalic="VeraBoldItalic")
    png = output.parent / "allocation.png"
    # Use the embedded Vera family when rasterizing the verified SVG, including its bold face.
    config = output.parent / "fonts.conf"
    config.write_text("<fontconfig><dir>" + escape(str(fonts)) + "</dir><cachedir>" +
                      escape(str(output.parent / "font-cache")) + "</cachedir><alias><family>sans-serif</family>"
                      "<prefer><family>Bitstream Vera Sans</family></prefer></alias></fontconfig>", encoding="utf-8")
    subprocess.run(["inkscape", str(root / "paper/generated/public-controller-allocation.svg"),
                    "--export-width=1800", "--export-filename=" + str(png)], check=True, stdout=subprocess.DEVNULL,
                   env={**os.environ, "FONTCONFIG_FILE": str(config), "LC_ALL": "C.UTF-8"})
    story = []
    for n, section in enumerate(SECTIONS):
        if n:
            story.extend([CondPageBreak(145), Spacer(1, 20)])
        story.extend(article(root / section, root, revision, png))

    def furniture(sheet, doc):
        sheet.saveState()
        sheet.setFont("Vera", 7.5)
        sheet.setFillColor(ACCENT)
        sheet.drawString(48, A4[1] - 30, "REGELSUCHE     /     METHODS MANUSCRIPT")
        sheet.setStrokeColor(colors.HexColor("#b7c9ce"))
        sheet.line(48, A4[1] - 37, A4[0] - 48, A4[1] - 37)
        sheet.drawString(48, 29, "Foundation    ·    " + revision[:12])
        sheet.drawRightString(A4[0] - 48, 29, str(doc.page))
        sheet.restoreState()

    def fixed_canvas(*args, **kwargs):
        kwargs["invariant"] = True
        return canvas.Canvas(*args, **kwargs)

    doc = SimpleDocTemplate(str(output), pagesize=A4, leftMargin=42, rightMargin=42, topMargin=49, bottomMargin=43,
                            title="Claim-Bounded Autonomous Symbolic Rule Discovery with Machine-Checkable Evidence",
                            author="Regelsuche project", subject="Working methods manuscript and evidence appendices")
    doc.build(story, onFirstPage=furniture, onLaterPages=furniture, canvasmaker=fixed_canvas)
