import re
import html
import subprocess
import os

def markdown_to_html(md_text):
    lines = md_text.split('\n')
    html_lines = []
    in_code_block = False
    code_lang = ""
    code_content = []
    in_table = False
    table_header = True

    for line in lines:
        if line.startswith('```'):
            if in_code_block:
                in_code_block = False
                escaped_code = html.escape('\n'.join(code_content))
                html_lines.append(f'<pre><code class="language-{code_lang}">{escaped_code}</code></pre>')
                code_content = []
            else:
                in_code_block = True
                code_lang = line[3:].strip()
            continue

        if in_code_block:
            code_content.append(line)
            continue

        # Handle tables
        if line.strip().startswith('|') and line.strip().endswith('|'):
            cells = [c.strip() for c in line.strip().split('|')[1:-1]]
            if all(re.match(r'^:?-+:?$', c) for c in cells):
                # Separator line
                table_header = False
                continue
            if not in_table:
                in_table = True
                table_header = True
                html_lines.append('<table>')
            
            tag = 'th' if table_header else 'td'
            row_html = '<tr>' + ''.join(f'<{tag}>{html.escape(c)}</{tag}>' for c in cells) + '</tr>'
            html_lines.append(row_html)
            continue
        elif in_table:
            in_table = False
            table_header = True
            html_lines.append('</table>')

        # Headings
        if line.startswith('# '):
            html_lines.append(f'<h1>{html.escape(line[2:].strip())}</h1>')
        elif line.startswith('## '):
            html_lines.append(f'<h2>{html.escape(line[3:].strip())}</h2>')
        elif line.startswith('### '):
            html_lines.append(f'<h3>{html.escape(line[4:].strip())}</h3>')
        elif line.startswith('#### '):
            html_lines.append(f'<h4>{html.escape(line[5:].strip())}</h4>')
        elif line.startswith('---'):
            html_lines.append('<hr/>')
        elif line.startswith('- ') or line.startswith('* '):
            item = line[2:].strip()
            item = re.sub(r'\*\*(.*?)\*\*', r'<strong>\1</strong>', item)
            item = re.sub(r'\*(.*?)\*', r'<em>\1</em>', item)
            item = re.sub(r'`(.*?)`', r'<code>\1</code>', item)
            html_lines.append(f'<li>{item}</li>')
        elif line.startswith('> '):
            quote = line[2:].strip()
            quote = re.sub(r'\*\*(.*?)\*\*', r'<strong>\1</strong>', quote)
            quote = re.sub(r'`(.*?)`', r'<code>\1</code>', quote)
            html_lines.append(f'<blockquote>{quote}</blockquote>')
        elif line.strip():
            p = line.strip()
            p = re.sub(r'\*\*(.*?)\*\*', r'<strong>\1</strong>', p)
            p = re.sub(r'\*(.*?)\*', r'<em>\1</em>', p)
            p = re.sub(r'`(.*?)`', r'<code>\1</code>', p)
            html_lines.append(f'<p>{p}</p>')

    if in_table:
        html_lines.append('</table>')

    body = '\n'.join(html_lines)
    return f"""<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<title>Informe Técnico de Seguridad - AgroTrack</title>
<style>
    @page {{
        size: A4;
        margin: 20mm 15mm 20mm 15mm;
        @bottom-right {{
            content: counter(page);
        }}
    }}
    body {{
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        color: #1a202c;
        line-height: 1.6;
        font-size: 11pt;
        background: #fff;
        max-width: 900px;
        margin: 0 auto;
        padding: 20px;
    }}
    h1 {{
        color: #2b6cb0;
        border-bottom: 2px solid #2b6cb0;
        padding-bottom: 8px;
        font-size: 20pt;
        margin-top: 24pt;
        page-break-before: auto;
    }}
    h2 {{
        color: #2c5282;
        border-bottom: 1px solid #e2e8f0;
        padding-bottom: 6px;
        font-size: 15pt;
        margin-top: 18pt;
    }}
    h3 {{
        color: #2d3748;
        font-size: 13pt;
        margin-top: 14pt;
    }}
    h4 {{
        color: #4a5568;
        font-size: 11pt;
        margin-top: 10pt;
    }}
    pre {{
        background: #f7fafc;
        border: 1px solid #e2e8f0;
        border-radius: 6px;
        padding: 12px;
        font-family: Consolas, "Courier New", monospace;
        font-size: 9pt;
        overflow-x: auto;
        page-break-inside: avoid;
    }}
    code {{
        background: #edf2f7;
        padding: 2px 5px;
        border-radius: 4px;
        font-family: Consolas, monospace;
        font-size: 9.5pt;
        color: #c53030;
    }}
    pre code {{
        background: none;
        padding: 0;
        color: #2d3748;
    }}
    table {{
        width: 100%;
        border-collapse: collapse;
        margin: 14pt 0;
        font-size: 9.5pt;
        page-break-inside: avoid;
    }}
    th, td {{
        border: 1px solid #cbd5e0;
        padding: 8px 10px;
        text-align: left;
    }}
    th {{
        background: #ebf8ff;
        color: #2b6cb0;
        font-weight: 600;
    }}
    tr:nth-child(even) {{
        background: #f7fafc;
    }}
    blockquote {{
        border-left: 4px solid #3182ce;
        background: #ebf8ff;
        margin: 12pt 0;
        padding: 8px 14px;
        color: #2c5282;
        border-radius: 0 6px 6px 0;
    }}
    hr {{
        border: 0;
        height: 1px;
        background: #e2e8f0;
        margin: 20pt 0;
    }}
    li {{
        margin-bottom: 4pt;
    }}
</style>
</head>
<body>
{body}
</body>
</html>"""

def main():
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    md_file = os.path.join(base_dir, "docs", "INFORME_TECNICO_SEGURIDAD_AGROTRACK.md")
    html_file = os.path.join(base_dir, "docs", "Informe_Seguridad_AgroTrack.html")
    pdf_file = os.path.join(base_dir, "docs", "Informe_Seguridad_AgroTrack.pdf")

    with open(md_file, "r", encoding="utf-8") as f:
        md_text = f.read()

    html_content = markdown_to_html(md_text)
    with open(html_file, "w", encoding="utf-8") as f:
        f.write(html_content)

    print(f"[OK] HTML generado: {html_file}")

    edge_path = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
    if os.path.exists(edge_path):
        cmd = [
            edge_path,
            "--headless=new",
            "--disable-gpu",
            f"--print-to-pdf={pdf_file}",
            html_file
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if os.path.exists(pdf_file) and os.path.getsize(pdf_file) > 0:
            print(f"[OK] PDF generado con exito: {pdf_file} ({os.path.getsize(pdf_file)} bytes)")
        else:
            print(f"[WARN] Error generando PDF con Edge: {res.stderr}")
    else:
        print("[INFO] Microsoft Edge no encontrado para conversion automatica.")

if __name__ == "__main__":
    main()
