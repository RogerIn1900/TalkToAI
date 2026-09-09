from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION_START
from docx.enum.table import WD_ALIGN_VERTICAL, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


ROOT = Path("/Users/jerry/JustDoIt/OHHHHH/TalkToAI")
OUTPUT = ROOT.parent / "TalkToAI样例对标优化报告.docx"
IMAGE_DIR = ROOT / "artifacts/runtime-2026-09-08"

BLUE = "2457A6"
PALE_BLUE = "EDF3FC"
PALE_GRAY = "F6F7F9"
GRID = "D9D9D9"
TEXT = RGBColor(31, 41, 55)
MUTED = RGBColor(91, 103, 116)
BODY_FONT = "Hiragino Sans GB"


def set_cell_fill(cell, color):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), color)


def set_cell_margins(cell, top=90, start=100, bottom=90, end=100):
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for margin, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{margin}"))
        if node is None:
            node = OxmlElement(f"w:{margin}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def set_table_borders(table):
    tbl_pr = table._tbl.tblPr
    borders = tbl_pr.first_child_found_in("w:tblBorders")
    if borders is None:
        borders = OxmlElement("w:tblBorders")
        tbl_pr.append(borders)
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        tag = borders.find(qn(f"w:{edge}"))
        if tag is None:
            tag = OxmlElement(f"w:{edge}")
            borders.append(tag)
        tag.set(qn("w:val"), "single")
        tag.set(qn("w:sz"), "6")
        tag.set(qn("w:color"), GRID)


def set_repeat_table_header(row):
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)


def prevent_row_split(row):
    tr_pr = row._tr.get_or_add_trPr()
    cant_split = OxmlElement("w:cantSplit")
    cant_split.set(qn("w:val"), "true")
    tr_pr.append(cant_split)


def style_run(run, size=10.5, bold=False, color=TEXT):
    run.font.name = BODY_FONT
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), BODY_FONT)
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color


def add_paragraph(doc, text="", bold_lead=None, after=6, before=0):
    paragraph = doc.add_paragraph()
    paragraph.paragraph_format.space_before = Pt(before)
    paragraph.paragraph_format.space_after = Pt(after)
    paragraph.paragraph_format.line_spacing = 1.25
    if bold_lead and text.startswith(bold_lead):
        lead = paragraph.add_run(bold_lead)
        style_run(lead, bold=True)
        rest = paragraph.add_run(text[len(bold_lead):])
        style_run(rest)
    else:
        run = paragraph.add_run(text)
        style_run(run)
    return paragraph


def add_bullets(doc, items):
    for item in items:
        paragraph = doc.add_paragraph(style="List Bullet")
        paragraph.paragraph_format.space_after = Pt(4)
        paragraph.paragraph_format.line_spacing = 1.2
        style_run(paragraph.add_run(item))


def add_table(doc, headers, rows, widths=None, font_size=9.0):
    table = doc.add_table(rows=1, cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_table_borders(table)
    header = table.rows[0]
    set_repeat_table_header(header)
    prevent_row_split(header)
    for index, text in enumerate(headers):
        cell = header.cells[index]
        set_cell_fill(cell, BLUE)
        set_cell_margins(cell)
        cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
        paragraph = cell.paragraphs[0]
        paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
        style_run(paragraph.add_run(text), size=font_size, bold=True, color=RGBColor(255, 255, 255))
        if widths:
            cell.width = Inches(widths[index])
    for row_index, values in enumerate(rows):
        row = table.add_row()
        prevent_row_split(row)
        if row_index % 2 == 1:
            for cell in row.cells:
                set_cell_fill(cell, PALE_GRAY)
        for index, value in enumerate(values):
            cell = row.cells[index]
            set_cell_margins(cell)
            cell.vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            paragraph = cell.paragraphs[0]
            paragraph.paragraph_format.space_after = Pt(0)
            paragraph.paragraph_format.line_spacing = 1.15
            if index == 0 and len(headers) <= 4:
                style_run(paragraph.add_run(str(value)), size=font_size, bold=True)
            else:
                style_run(paragraph.add_run(str(value)), size=font_size)
            if widths:
                cell.width = Inches(widths[index])
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return table


def add_heading(doc, text, level=1):
    heading = doc.add_heading(text, level=level)
    heading.paragraph_format.space_before = Pt(12 if level == 1 else 8)
    heading.paragraph_format.space_after = Pt(6)
    for run in heading.runs:
        style_run(run, size=16 if level == 1 else 12.5, bold=True, color=RGBColor(0, 0, 0))
    return heading


def add_image_pair(doc, left_path, left_caption, right_path, right_caption):
    table = doc.add_table(rows=2, cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    for index, image_path in enumerate((left_path, right_path)):
        paragraph = table.rows[0].cells[index].paragraphs[0]
        paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
        paragraph.add_run().add_picture(str(image_path), width=Inches(1.55))
    for index, caption in enumerate((left_caption, right_caption)):
        paragraph = table.rows[1].cells[index].paragraphs[0]
        paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
        style_run(paragraph.add_run(caption), size=8.5, color=MUTED)
    for row in table.rows:
        for cell in row.cells:
            set_cell_margins(cell, top=30, start=60, bottom=30, end=60)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)


def build():
    doc = Document()
    section = doc.sections[0]
    section.page_width = Inches(8.5)
    section.page_height = Inches(11)
    section.top_margin = Inches(0.7)
    section.bottom_margin = Inches(0.5)
    section.left_margin = Inches(0.7)
    section.right_margin = Inches(0.7)

    styles = doc.styles
    title_style_pr = styles["Title"]._element.get_or_add_pPr()
    title_style_border = title_style_pr.find(qn("w:pBdr"))
    if title_style_border is not None:
        title_style_pr.remove(title_style_border)
    normal = styles["Normal"]
    normal.font.name = BODY_FONT
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), BODY_FONT)
    normal.font.size = Pt(10.5)
    normal.font.color.rgb = TEXT

    title = doc.add_paragraph(style="Title")
    title.alignment = WD_ALIGN_PARAGRAPH.LEFT
    title.paragraph_format.space_after = Pt(7)
    title_pr = title._p.get_or_add_pPr()
    title_border = title_pr.find(qn("w:pBdr"))
    if title_border is not None:
        title_pr.remove(title_border)
    style_run(title.add_run("TalkToAI 样例对标优化报告"), size=25, bold=True, color=RGBColor(0, 0, 0))
    subtitle = doc.add_paragraph()
    subtitle.paragraph_format.space_after = Pt(16)
    style_run(subtitle.add_run("参考 StockMate 视频观察文档  对比 Android 第一版当前实现"), size=12, color=MUTED)

    add_table(doc, ["项目", "内容"], [
        ["评估日期", "2026 年 9 月 8 日"],
        ["评估范围", "Android、Kuikly UI、CloudBase 测试环境、A 股只读咨询"],
        ["参考材料", "参考样例.docx；StockMate 样例视频观察；TalkToAI 当前源码、自动化测试和模拟器证据"],
        ["结论", "样例对标范围内的高价值 V1 改进已落地并复验；生产行情、持久配额和真机性能仍受外部条件或门槛阻塞。"],
    ], widths=[1.35, 5.55], font_size=9.5)

    add_heading(doc, "一 结论先行")
    add_paragraph(doc, "TalkToAI 当前已从“能聊天和画图”提升到“行情事实先展示、AI 再解释”的可信对话结构。与样例相比，当前版本在数据新鲜度、A 股范围、只读交易边界、弱网重试和测试证据上更严格；样例在视觉完成度、字体与表格实时预览、语音和独立标的对比页上仍更成熟。")
    add_paragraph(doc, "本轮完成了七项直接可交付改进：行情卡片定位到触发它的用户消息之后；增加报价、涨跌、时间、来源和缓存状态；图表可展开原始数据；来源折叠展示；增加分享和踩；扩大 AI 长回答阅读宽度；修复长会话中旧回复覆盖最新行情工具结果的问题。", bold_lead="本轮完成了七项直接可交付改进：")
    add_paragraph(doc, "“完成”只指已确认 V1 范围和当前授权内的实现。当前测试环境行情仍回退到固定测试数据，未配置 Tushare Token 或 AKShare HTTPS 网关；CloudBase 持久化配额身份和 signed release 真机性能也未完成，不能标记为生产可用。", bold_lead="“完成”只指已确认 V1 范围和当前授权内的实现。")

    add_heading(doc, "二 各方面对比点评")
    add_paragraph(doc, "评分采用 5 分制，是基于参考文档可见行为、当前代码和本次实测的产品审计评分，不是行业统计。")
    add_table(doc, ["维度", "样例", "TalkToAI 本轮前", "TalkToAI 本轮后", "判断"], [
        ["信息架构", "4.5", "4.0", "4.2", "均采用侧栏加主页面；TalkToAI 的 A 股和只读边界更明确，样例的工具解释更自然。"],
        ["对话感与长文", "4.5", "3.4", "4.2", "用户右、AI 左并保留操作区；本轮扩大 AI 阅读宽度并增加模型身份、分享和踩。"],
        ["行情可信度", "3.1", "3.8", "4.5", "TalkToAI 明示来源、市场时间、抓取时间、新鲜度和测试数据；真实授权源仍未接通。"],
        ["图表与数据", "4.0", "3.8", "4.4", "TalkToAI 有 K 线和成交量、轴含义、图例及折柱饼切换；本轮补充可展开数据。"],
        ["表格体验", "4.4", "3.6", "4.0", "样例有独立对比页和密度预览；TalkToAI 避免在聊天中暴露 Markdown 表格，但尚无冻结首列的多标的页。"],
        ["设置反馈", "4.6", "4.0", "4.1", "当前主题、气泡和头像已即时反馈并保持页面；样例的字体和表格所见即所得更完整。"],
        ["异常与恢复", "2.8", "4.2", "4.3", "样例视频未证明断网、停止或失败路径；TalkToAI 已有停止、失败重试、离线消息和重启恢复。"],
        ["工程可验证性", "2.0", "4.0", "4.6", "样例无源码与测试证据；TalkToAI 本轮 64 项自动化测试、Lint、双构建和模拟器安装通过。"],
    ], widths=[1.05, 0.58, 0.82, 0.82, 3.6], font_size=8.4)

    add_heading(doc, "三 当前项目原有不足与本轮实现", level=1)
    add_image_pair(
        doc,
        IMAGE_DIR / "sample-compare-market.png",
        "行情摘要  K 线  成交量  坐标与新鲜度",
        IMAGE_DIR / "sample-compare-share-final.png",
        "回答分享复用 Android 系统分享面板",
    )
    add_table(doc, ["原问题", "用户影响", "本轮实现", "证据"], [
        ["行情卡片固定在消息列表前部", "长会话中看不到问题对应的图表", "把卡片锚定到触发行情意图的最新用户消息之后", "TalkToAiPager.kt 287 至 299；TalkToAiViewModel.kt 720、768 至 769"],
        ["图表有趋势但摘要弱", "用户先找数值再判断数据是否可用", "新增代码、最新价、涨跌额和涨跌幅、过期或缓存徽标、市场时间、抓取时间、来源", "TalkToAiPager.kt 751、787、873；TalkUiPolicyTest 14 项通过"],
        ["图表缺少可核对的表格入口", "无法核验每个点或复制数据", "每张图增加“数据/收起”，以固定列展示前 5 行原始值", "TalkToAiPager.kt 584；TalkToAiViewModel.kt 329"],
        ["来源文字长期占用气泡", "长回答信息层级拥挤", "来源改为按消息展开和收起，分享内容仍保留引用和风险提示", "TalkToAiPager.kt 632；TalkToAiViewModel.kt 309"],
        ["操作只有复制、赞和重生成", "反馈和跨应用协作不足", "增加 Android 原生分享和踩，赞踩互斥", "KRBridgeModule.kt 81、446；TalkToAiViewModel.kt 295、309"],
        ["AI 长文宽度偏窄", "中文标题和表格易频繁换行", "AI 内容区从页面宽度的 72% 调整到 82%，用户气泡仍保持 72%", "TalkToAiPager.kt 336 至 355"],
        ["长会话旧回复可能否定本轮行情", "图表已出现但 AI 声称缺少数据", "服务端将最新工具结果同时绑定到系统上下文和本轮用户消息，并声明旧冲突表述无效", "backend src/app.ts 150 至 180；后端长会话测试和远端 SSE 实测"],
    ], widths=[1.35, 1.45, 2.55, 1.6], font_size=8.2)

    add_heading(doc, "四 对话和 AI 内容点评")
    add_paragraph(doc, "样例的优点是长回答更像研究笔记：标题、编号、行情卡片和操作区组织清楚。其风险是正文出现持有、逢低补仓等操作性表达，免责声明不能抵消具体买卖暗示。TalkToAI 应继续坚持“事实、情景、风险、来源”四段式，不给个性化仓位、价格目标或下单指令。")
    add_paragraph(doc, "当前 TalkToAI 已用 KuiklyMarkdown 渲染文字，并把可识别数值表转换成原生图表，避免向用户直接暴露 Markdown 表格源码。本轮进一步让行情结构化事件先于 AI 文本出现，并修复工具上下文在长会话里失效的问题。下一阶段应由后端返回有版本的 Text、Table、Chart、MarketSnapshot、Citation 和 Warning 内容块，彻底减少客户端从自然语言猜结构。")
    add_bullets(doc, [
        "默认先给结论摘要和数据状态，再给详细解释，避免长答案把关键时间和来源埋在后面。",
        "只有完成且非失败的 AI 消息显示赞、踩、分享和重生成；网络错误消息优先显示重新加载。",
        "来源展开后应显示标题、HTTPS 域名、数据时间和用途，不把模型生成的无效 URL 当作可信依据。",
        "语音输入和朗读有体验价值，但不应先于真实行情授权、持久限额和性能门槛。",
    ])

    add_heading(doc, "五 表格图表和行情点评")
    add_table(doc, ["项目", "样例表现", "TalkToAI 当前表现", "进一步建议"], [
        ["分时与坐标", "有价格纵轴和交易时点横轴", "行情页显示横纵轴含义、图例和时间范围", "加入十字光标、选中点明细和均价线"],
        ["K 线与成交量", "视频未展示成交量副图", "MPAndroidChart K 线与成交量同步展示，支持日周月和自定义日期", "补充停牌、复权和交易日历契约测试"],
        ["聊天数据", "既有 Markdown 表格也有独立对比表", "默认图表并可切换折线、柱状、饼状；本轮可展开数据", "后端 typed blocks，负值和非整体关系禁止默认饼图"],
        ["多标的对比", "独立页面但依赖横向滑动", "首版没有独立多标的表", "V2 采用冻结标的列、横向提示、详情卡片和 CSV 导出"],
        ["数据可信度", "显示绝对时间，未见统一新鲜度徽标", "明确市场时间、抓取时间、freshness、缓存和来源", "接入授权 HTTPS 源后再验交易中、收盘、周末和停牌"],
    ], widths=[1.0, 1.65, 2.35, 2.1], font_size=8.5)

    add_heading(doc, "六 样例本身可以提升的点")
    add_table(doc, ["问题", "风险", "建议"], [
        ["直接给持有或补仓方向", "容易跨越信息服务与个性化投资建议边界", "只输出条件化情景、可核验事实和风险，不生成下单或仓位指令"],
        ["正文来源不足", "财报、估值和行情事实无法逐项追溯", "为每类事实提供可展开来源，并区分行情、公告和模型推断"],
        ["只显示数据时间", "用户可能把延迟或缓存误认为实时", "同时展示市场时间、抓取时间、新鲜度、延迟级别和缓存状态"],
        ["宽表格横向截断", "首列和指标含义离开视口", "冻结标的列，提供明显滚动提示；移动端优先卡片或分组详情"],
        ["腾讯意图直接落到 00700", "歧义和跨市场范围没有确认", "歧义时确认公司、市场和代码；A 股首版明确拒绝港美股查询"],
        ["异常路径未展示", "不能判断断网、超时、鉴权和附件失败是否可恢复", "补齐失败状态、重试、停止、离线和数据过期演示及自动化测试"],
        ["AI 预测与历史走势共区", "容易混淆事实与预测", "使用不同颜色、图例、预测区间和置信度，并明确非事实数据"],
    ], widths=[1.7, 2.35, 3.1], font_size=8.6)

    add_heading(doc, "七 验证结果和可靠性边界")
    add_table(doc, ["层级", "实际结果", "说明"], [
        ["后端", "28 项测试通过；TypeScript 构建通过", "含行情 Provider、freshness、配额、附件、SSE 和长会话工具上下文"],
        ["Shared UI 规则", "14 项测试通过", "含行情摘要、格式化、图表表格、分享内容与交互策略"],
        ["Android JVM", "20 项测试通过", "含 SSE、HTTP、附件、Room 编解码、缓存和图表轴策略"],
        ["Android 设备", "2 项测试通过", "Medium Phone API 36.1；Room 设备链路"],
        ["静态检查", "Lint 0 error、55 warning", "警告主要是 Kuikly 或 Android 弃用 API、版本与国际化；未隐藏"],
        ["构建与安装", "debug 和 unsigned release 构建成功；debug 覆盖安装成功", "KuiklyRenderActivity 正常 resumed；未发现 FATAL EXCEPTION 或 ANR"],
        ["CloudBase 测试环境", "部署成功；health HTTP 200；真实 hy3 SSE 成功", "长历史中旧的缺失上下文回复未再覆盖本轮行情数据"],
        ["APK", "8,025,371 bytes；SHA-256 c7256dca4e9860b2f5ccb962a8bee9c34ce882dc1663ead480bfdaab5fe4921b", "debug 可安装制品；release 未签名，不是商店发布包"],
    ], widths=[1.25, 2.7, 3.2], font_size=8.5)
    add_paragraph(doc, "自动化测试合计 64 项：后端 28、Shared 14、Android JVM 20、Android 设备 2，失败 0。最终 debug 覆盖安装后的单次冷启动 TotalTime 为 7620 ms；这是模拟器单样本，不是性能分布，但已经证明仍未达到内部 P90 小于 5 秒的目标，不能用功能通过替代性能结论。")

    add_heading(doc, "八 剩余风险与完成定义")
    add_table(doc, ["优先级", "未完成项", "阻塞或验收条件"], [
        ["P0", "真实授权 A 股 HTTPS 行情", "当前 health 显示 Tushare 和 AKShare 均待配置，只能回退到明确标识的固定测试数据"],
        ["P0", "跨实例持久每日 500 次配额", "CloudBase 函数数据库服务身份需要外部控制台授权；完成后测 1、500、501、并发和跨实例"],
        ["P0", "signed release 真机性能", "需签名或 profileable 包，在真实 Android 设备跑 30 次 Macrobenchmark；冷启动 P90 小于 5 秒"],
        ["P1", "UI 与状态类拆分", "Pager 1432 行、ViewModel 1332 行、Bridge 519 行；按 Chat、Market、Session、Settings 拆分并保持测试"],
        ["P1", "长列表性能门禁", "1000 条消息加 10 张图，janky frames 小于 10%，P95 frame 小于 32 ms"],
        ["P2", "独立多标的表格和朗读", "参考样例实现冻结首列、来源跳转和读屏；不抢占 P0 可信闭环"],
    ], widths=[0.65, 2.4, 4.1], font_size=8.7)

    add_heading(doc, "九 最终判断")
    add_paragraph(doc, "样例对标所暴露的 V1 高价值问题已经完成实现和检验：对话层级、行情摘要、结构化图表、数据展开、来源折叠、分享、负反馈以及行情与 AI 文本的一致性均已落地。当前项目在“行情不会被误认为实时”和“失败路径可验证”方面优于样例；在字体和表格预览、独立多标的对比、语音与整体视觉细腻度方面仍落后。")

    footer = section.footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    style_run(footer.add_run("TalkToAI Android V1  样例对标优化报告  2026-09-08"), size=8, color=MUTED)

    doc.core_properties.title = "TalkToAI 样例对标优化报告"
    doc.core_properties.subject = "TalkToAI Android 第一版与 StockMate 样例的对比、实现和验证"
    doc.core_properties.author = "TalkToAI 项目组"
    doc.save(OUTPUT)
    print(OUTPUT)


if __name__ == "__main__":
    build()
