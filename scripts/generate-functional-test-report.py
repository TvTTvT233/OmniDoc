#!/usr/bin/env python3
"""Generate OmniDoc functional verification reports from a runner JSON result."""

from __future__ import annotations

import argparse
import copy
import json
import re
from collections import OrderedDict
from datetime import datetime, timezone, timedelta
from pathlib import Path

from docx import Document
from docx.enum.section import WD_ORIENT, WD_SECTION
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_ROW_HEIGHT_RULE, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_LINE_SPACING
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Inches, Pt, RGBColor


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESULT = ROOT / ".local" / "test-results" / "functional-test-20260724161903.json"
OUTPUT_DIR = ROOT / "docs" / "testing"
REPORT_BASENAME = "OmniDoc-functional-test-report-2026-07-25"

COLORS = {
    "navy": "0B1F3A",
    "blue": "2F6BFF",
    "cyan": "0E8A8A",
    "green": "16836C",
    "green_bg": "E8F5F0",
    "red": "B4232D",
    "red_bg": "FDECEF",
    "amber": "A46200",
    "amber_bg": "FFF4DB",
    "ink": "1C2733",
    "muted": "586575",
    "line": "D6DEE8",
    "soft": "F3F6FA",
    "white": "FFFFFF",
}

LOCAL_TZ = timezone(timedelta(hours=8))


ANOMALIES = [
    {
        "id": "SEC-002",
        "severity": "P0",
        "module": "安全",
        "title": "用户详情接口可匿名读取个人信息",
        "observed": "未携带 Authorization 请求 /api/users/3，HTTP 200、业务码 200，返回用户名、邮箱、手机号、角色和创建时间。",
        "root": "gateway-service/.../AuthGlobalFilter.java:39 将 /api/users/** 整体加入白名单。",
        "suggestion": "白名单只保留注册、登录等精确路径；用户详情必须鉴权并校验本人或管理员权限。",
    },
    {
        "id": "FIL-012",
        "severity": "P1",
        "module": "文件",
        "title": "文件移动返回成功，但原 fileId 随后无法下载",
        "observed": "移动接口返回“文件移动成功”；紧接着按原 fileId 下载得到 HTTP 500。清理时发现对象已移动到新路径，表现为元数据/对象路径状态不一致。",
        "root": "根据接口结果和对象存储状态推断：移动后的对象路径与按 fileId 下载所依赖的元数据未保持一致。",
        "suggestion": "将对象移动与元数据更新做成可回滚的一致性操作，并在返回成功前验证新对象存在且原 fileId 可解析。",
    },
    {
        "id": "COM-001",
        "severity": "P1",
        "module": "评论",
        "title": "评论创建者被硬编码为 userId=1",
        "observed": "当前登录用户 id=3，创建评论返回 userId=1、createdBy=\"1\"。",
        "root": "document-service/.../CommentController.java:28、56 使用 Long userId = 1L。",
        "suggestion": "从可信网关请求头或统一用户上下文读取当前用户，并覆盖创建、修改、删除全链路。",
    },
    {
        "id": "ANN-001",
        "severity": "P1",
        "module": "批注",
        "title": "批注创建者被硬编码为 userId=1",
        "observed": "当前登录用户 id=3，创建批注返回 userId=1、createdBy=\"1\"。",
        "root": "document-service/.../AnnotationController.java:28、57、67 使用 Long userId = 1L。",
        "suggestion": "改为统一用户上下文，并为创建、修改、删除增加所有权与共享权限测试。",
    },
    {
        "id": "AIF-004",
        "severity": "P1",
        "module": "AI 文件",
        "title": "文件版本列表返回业务码 500",
        "observed": "HTTP 200，但响应 code=500、message=服务器内部错误。",
        "root": "日志显示 LinkedHashMap 无法强制转换为 FileVersionVO.VersionInfo，位置 FileVersionService.java:115。",
        "suggestion": "为 Redis/JSON 反序列化使用明确类型信息或显式 DTO 转换，并补充缓存回读测试。",
    },
    {
        "id": "RAG-009",
        "severity": "P1",
        "module": "RAG",
        "title": "混合检索返回业务码 500",
        "observed": "向 /api/ai/rag/search/hybrid 查询“知识库”，HTTP 200、业务码 500。",
        "root": "日志显示 KnowledgeBase.java:270 对不可变 Map 执行 put，引发 UnsupportedOperationException。",
        "suggestion": "合并前复制为 new HashMap<>(result)，并覆盖 vector/BM25 两类结果来源。",
    },
    {
        "id": "RAG-010",
        "severity": "P1",
        "module": "RAG",
        "title": "混合检索重排返回业务码 500",
        "observed": "BM25_FUSION 重排请求 HTTP 200、业务码 500。",
        "root": "与 RAG-009 同源：候选结果为不可变 Map，混合阶段写入 content/source 时异常。",
        "suggestion": "修复可变副本后，为 hybrid 和 hybrid/rerank 增加端到端回归。",
    },
    {
        "id": "AIF-007",
        "severity": "P1",
        "module": "AI 文件",
        "title": "文件删除确认流程无法写入 Redis",
        "observed": "测试清理阶段调用删除确认接口得到业务码 500，API 无法完成对象清理；本轮使用精确对象路径执行人工清理。",
        "root": "日志显示 FileDeleteService.DeleteRequest 无可用 JSON 序列化器/属性，Redis 写入触发 SerializationException。",
        "suggestion": "将 DeleteRequest 改为可序列化 DTO/record 或配置 Jackson 可见性，并补充真实 Redis 集成测试。",
    },
    {
        "id": "AI-005",
        "severity": "P2",
        "module": "AI",
        "title": "无效模型代码被静默回退",
        "observed": "model=not-a-model 仍返回 HTTP/业务码 200，并生成摘要，耗时 8.4 秒。",
        "root": "服务未在调用前校验模型代码，实际行为表现为默认模型回退。",
        "suggestion": "校验允许的模型枚举；无效值返回 400，并在响应中明确实际使用模型。",
    },
    {
        "id": "SEC-004",
        "severity": "P2",
        "module": "安全",
        "title": "跨用户文件下载虽被拒绝，但错误语义为 HTTP 500",
        "observed": "协作者使用所有者 fileId 下载时未获得文件内容，但服务器返回 HTTP 500，而非 403/404。",
        "root": "访问拒绝/对象不可见异常未映射为明确的权限或资源不存在状态码。",
        "suggestion": "统一异常映射为 403 或防枚举的 404，并保持 JSON 错误结构。",
    },
]

TEST_INFRA = [
    ("前端构建", "PASS", "npm run build：Vite 4.5.3，1670 modules，13.07s；主包 1,241.49 kB（gzip 402.84 kB），存在 >500 kB 警告。"),
    ("前端异步轮询单测", "PASS", "node --test src/api/asyncJob.test.mjs：2/2 通过，0 失败，168.99 ms。"),
    ("common", "WARN", "Surefire 发现 0 个测试；JwtUtilsTest 未进入 JUnit Platform 执行。"),
    ("user-service", "PASS", "UserApplicationTests：1/1 通过。"),
    ("file-service", "ERROR", "FileApplicationTests：1 error；测试包 com.javaee.file 与应用包 com.javaee.fileservice 不一致，无法定位 @SpringBootConfiguration。"),
    ("document-service", "PARTIAL", "DocumentServiceImplTest：4/4 通过；ApplicationTests：1 error，Maven 测试进程缺少 MINIO_ACCESS_KEY。"),
    ("ai-service", "PARTIAL", "除 AllModelsTest 外 52/52 通过；AllModelsTest：1 error，Maven 测试进程未注入 DASHSCOPE_API_KEY。端到端 qwen 调用已通过。"),
    ("gateway-service", "ERROR", "GatewayServiceApplicationTests：1 error；测试包 com.javaee.gatewayservice 与应用包 com.javaee.gateway 不一致。"),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, default=DEFAULT_RESULT)
    parser.add_argument("--output-dir", type=Path, default=OUTPUT_DIR)
    return parser.parse_args()


def load_results(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def reviewed_results(raw: dict) -> list[dict]:
    results = copy.deepcopy(raw["results"])
    for item in results:
        if item["id"] == "AGT-015":
            item["status"] = "PASS"
            item["anomaly"] = "否（人工复核）"
            item["detail"] = (
                "原始轮询器只接受 completed/success，因服务返回 INDEXED 而误判；"
                "响应同时为 progress=100、attempts=1、errorMessage=null，复核为成功。"
            )
    return results


def counts_for(results: list[dict]) -> dict[str, int]:
    counts = {"PASS": 0, "FAIL": 0, "SKIP": 0}
    for item in results:
        counts[item["status"]] += 1
    return counts


def module_counts(results: list[dict]) -> OrderedDict[str, dict[str, int]]:
    output: OrderedDict[str, dict[str, int]] = OrderedDict()
    for item in results:
        bucket = output.setdefault(item["module"], {"PASS": 0, "FAIL": 0, "SKIP": 0, "TOTAL": 0})
        bucket[item["status"]] += 1
        bucket["TOTAL"] += 1
    return output


def local_time(iso_value: str) -> str:
    dt = datetime.fromisoformat(iso_value.replace("Z", "+00:00")).astimezone(LOCAL_TZ)
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def md_cell(value: object) -> str:
    text = "" if value is None else str(value)
    return text.replace("\\", "\\\\").replace("|", "\\|").replace("\r", "").replace("\n", "<br>")


def status_text(status: str) -> str:
    return {"PASS": "通过", "FAIL": "异常", "SKIP": "未覆盖"}.get(status, status)


def code_pair(item: dict) -> str:
    http = "—" if item.get("httpStatus") is None else str(item["httpStatus"])
    business = "—" if item.get("businessCode") is None else str(item["businessCode"])
    return f"{http} / {business}"


def generate_markdown(raw: dict, reviewed: list[dict], out_path: Path) -> None:
    raw_counts = raw["counts"]
    reviewed_counts = counts_for(reviewed)
    modules = module_counts(reviewed)
    executed = reviewed_counts["PASS"] + reviewed_counts["FAIL"]
    rate = reviewed_counts["PASS"] / executed * 100 if executed else 0

    lines: list[str] = [
        "# OmniDoc 功能测试报告",
        "",
        f"- 报告日期：2026-07-25",
        f"- 测试时段：2026-07-25 00:19:03 ～ {local_time(raw['finishedAt'])}（Asia/Shanghai）",
        f"- 网关：{raw['environment']['gatewayBase']}",
        f"- Web：{raw['environment']['webBase']}",
        f"- AI 模型：{raw['environment']['model']}（百炼兼容接口）",
        f"- 运行环境：{raw['environment']['platform']}，Node {raw['environment']['node']}，Java 17",
        "",
        "## 1. 结论摘要",
        "",
        f"自动化原始结果：142 条，127 通过、10 异常、5 未覆盖。人工复核发现 AGT-015 的服务状态为 `INDEXED`、进度 100%，属于轮询器状态枚举误判；复核结果为 **128 通过、9 个已确认异常、5 未覆盖**，已执行用例通过率为 **{rate:.2f}%**。清理阶段另发现 1 个 AI 文件删除异常（AIF-007），因此本报告共记录 10 条产品异常。",
        "",
        "当前版本可以完成用户、文件、文档共享、AI 文本处理、异步任务、RAG 基础索引/向量查询、Agent、AIOps 和 HTML PPT 等主要流程；但匿名读取用户资料属于 P0 安全问题，文件移动、评论/批注身份、AI 文件版本/删除、RAG 混合检索属于 P1。**修复 P0/P1 并回归前，不建议作为公开互联网生产服务发布。**",
        "",
        "## 2. 统计",
        "",
        "| 口径 | 总数 | 通过 | 异常 | 未覆盖 | 说明 |",
        "|---|---:|---:|---:|---:|---|",
        f"| 自动化原始 | 142 | {raw_counts['pass']} | {raw_counts['fail']} | {raw_counts['skip']} | 保留测试脚本原始判断 |",
        f"| 人工复核 | 142 | {reviewed_counts['PASS']} | {reviewed_counts['FAIL']} | {reviewed_counts['SKIP']} | AGT-015 由异常修正为通过 |",
        "| 额外清理验证 | 1 | 0 | 1 | 0 | AIF-007 删除确认写 Redis 失败 |",
        "",
        "### 2.1 模块分布（人工复核）",
        "",
        "| 模块 | 总数 | 通过 | 异常 | 未覆盖 |",
        "|---|---:|---:|---:|---:|",
    ]
    for module, bucket in modules.items():
        lines.append(
            f"| {md_cell(module)} | {bucket['TOTAL']} | {bucket['PASS']} | {bucket['FAIL']} | {bucket['SKIP']} |"
        )

    lines += [
        "",
        "## 3. 已确认异常",
        "",
        "| ID | 等级 | 模块 | 异常 | 实际结果 | 建议 |",
        "|---|---|---|---|---|---|",
    ]
    for issue in ANOMALIES:
        lines.append(
            f"| {issue['id']} | {issue['severity']} | {issue['module']} | {md_cell(issue['title'])} | "
            f"{md_cell(issue['observed'])} | {md_cell(issue['suggestion'])} |"
        )

    lines += ["", "### 3.1 根因证据", ""]
    for issue in ANOMALIES:
        lines += [
            f"- **{issue['id']} · {issue['title']}**",
            f"  - 根因/定位：{issue['root']}",
            f"  - 修复建议：{issue['suggestion']}",
        ]

    lines += [
        "",
        "## 4. 测试范围与方法",
        "",
        "- 通过 Gateway `http://127.0.0.1:8080` 调用真实服务，Web 路由通过 `http://127.0.0.1:5173` 验证。",
        "- 使用临时所有者与协作者账号覆盖匿名、本人、跨用户、只读共享和可编辑共享场景。",
        "- AI 调用使用 `qwen3.7-max-2026-05-20`；密钥仅由环境变量读取，报告和结果文件均未记录密钥。",
        "- 文件、文档、评论、批注、RAG 与 Agent 测试数据在测试后清理；因 AIF-007，遗留测试对象使用精确对象路径人工删除，并确认无本轮 runId 残留。",
        "- 对具有真实删除/覆盖风险且无法构造安全前置状态的能力标记为“未覆盖”，不对用户数据执行破坏性测试。",
        "- 前端执行生产构建、异步轮询单测和 SPA 路由 HTTP 验证；未执行浏览器双客户端交互录屏，实时协作用例单列为未覆盖。",
        "",
        "## 5. 前端与后端测试工程结果",
        "",
        "| 范围 | 结论 | 返回结果 / 异常 |",
        "|---|---|---|",
    ]
    for scope, conclusion, detail in TEST_INFRA:
        lines.append(f"| {scope} | {conclusion} | {md_cell(detail)} |")

    skips = [item for item in reviewed if item["status"] == "SKIP"]
    lines += [
        "",
        "## 6. 未覆盖用例",
        "",
        "| ID | 模块 | 用例 | 原因 |",
        "|---|---|---|---|",
    ]
    for item in skips:
        lines.append(
            f"| {item['id']} | {item['module']} | {md_cell(item['name'])} | {md_cell(item['detail'])} |"
        )

    lines += [
        "",
        "## 7. 全量功能用例明细",
        "",
        "> 业务码“—”表示二进制响应、TCP 检查或接口未提供统一业务码。返回结果保留自动化脚本采集到的响应摘要；AGT-015 的原始响应不变，只修正复核结论。",
        "",
        "| ID | 模块 | 测试用例 | 输入 | 预期 | HTTP / 业务码 | 耗时(ms) | 结论 | 是否异常 | 返回结果 | 备注 |",
        "|---|---|---|---|---|---|---:|---|---|---|---|",
    ]
    for item in reviewed:
        lines.append(
            "| "
            + " | ".join(
                [
                    item["id"],
                    md_cell(item["module"]),
                    md_cell(item["name"]),
                    md_cell(item["input"]),
                    md_cell(item["expected"]),
                    code_pair(item),
                    str(item["elapsedMs"]),
                    status_text(item["status"]),
                    md_cell(item["anomaly"]),
                    md_cell(item["result"]),
                    md_cell(item["detail"]),
                ]
            )
            + " |"
        )

    lines += [
        "",
        "## 8. 可重复执行与证据",
        "",
        "- 功能测试脚本：`scripts/run-functional-tests.mjs`",
        f"- 原始结果：`.local/test-results/{DEFAULT_RESULT.name}`",
        "- 前端构建：`cd web && npm.cmd run build`",
        "- 前端轮询单测：`cd web && node --test src/api/asyncJob.test.mjs`",
        "- 后端模块测试：`.\\mvnw.cmd -s .mvn\\settings-local.xml test`，也可使用 `-pl <module> -am` 单独执行。",
        "- 再次执行功能测试：`node scripts/run-functional-tests.mjs`；严格模式可设置 `STRICT_FUNCTIONAL_TESTS=1`。",
        "",
        "## 9. 回归优先级",
        "",
        "1. 立即修复 SEC-002，并新增匿名、普通用户、管理员三组用户详情授权用例。",
        "2. 修复 FIL-012、COM-001、ANN-001，回归跨账号、共享权限和对象一致性。",
        "3. 修复 AIF-004、AIF-007、RAG-009、RAG-010，补充 Redis/MinIO 真实集成测试。",
        "4. 统一权限异常 HTTP 语义并拒绝无效模型代码。",
        "5. 修正 Maven 测试类包路径与测试环境变量注入，使聚合测试能一次性跑完。",
        "",
    ]

    out_path.write_text("\n".join(lines), encoding="utf-8")


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top=60, start=70, bottom=60, end=70) -> None:
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


def set_cell_text(cell, text: str, *, bold=False, color=None, size=8, align=None) -> None:
    cell.text = ""
    paragraph = cell.paragraphs[0]
    if align is not None:
        paragraph.alignment = align
    paragraph.paragraph_format.space_after = Pt(0)
    paragraph.paragraph_format.space_before = Pt(0)
    paragraph.paragraph_format.line_spacing = 1.0
    run = paragraph.add_run(str(text))
    run.bold = bold
    run.font.name = "Microsoft YaHei"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    run.font.size = Pt(size)
    if color:
        run.font.color.rgb = RGBColor.from_string(color)
    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
    set_cell_margins(cell)


def set_repeat_table_header(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)


def prevent_row_split(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    cant_split = OxmlElement("w:cantSplit")
    tr_pr.append(cant_split)


def set_table_borders(table, color="D6DEE8", size="4") -> None:
    tbl_pr = table._tbl.tblPr
    borders = tbl_pr.find(qn("w:tblBorders"))
    if borders is None:
        borders = OxmlElement("w:tblBorders")
        tbl_pr.append(borders)
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        element = borders.find(qn(f"w:{edge}"))
        if element is None:
            element = OxmlElement(f"w:{edge}")
            borders.append(element)
        element.set(qn("w:val"), "single")
        element.set(qn("w:sz"), size)
        element.set(qn("w:color"), color)


def set_column_widths(table, widths: list[float]) -> None:
    for row in table.rows:
        for idx, width in enumerate(widths):
            row.cells[idx].width = Inches(width)
            tc_pr = row.cells[idx]._tc.get_or_add_tcPr()
            tc_w = tc_pr.find(qn("w:tcW"))
            if tc_w is not None:
                tc_w.set(qn("w:w"), str(int(width * 1440)))
                tc_w.set(qn("w:type"), "dxa")


def add_page_number(paragraph) -> None:
    paragraph.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    run = paragraph.add_run("OMNIDOC  •  ")
    run.font.name = "Aptos"
    run.font.size = Pt(8)
    run.font.color.rgb = RGBColor.from_string(COLORS["muted"])
    begin = OxmlElement("w:fldChar")
    begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = "PAGE"
    end = OxmlElement("w:fldChar")
    end.set(qn("w:fldCharType"), "end")
    run._r.append(begin)
    run._r.append(instr)
    run._r.append(end)


def configure_section(section, landscape=False) -> None:
    if landscape:
        section.orientation = WD_ORIENT.LANDSCAPE
        section.page_width = Inches(11.69)
        section.page_height = Inches(8.27)
        section.top_margin = Inches(0.45)
        section.bottom_margin = Inches(0.45)
        section.left_margin = Inches(0.45)
        section.right_margin = Inches(0.45)
    else:
        section.page_width = Inches(8.27)
        section.page_height = Inches(11.69)
        section.top_margin = Inches(0.65)
        section.bottom_margin = Inches(0.65)
        section.left_margin = Inches(0.75)
        section.right_margin = Inches(0.75)
    section.header_distance = Inches(0.25)
    section.footer_distance = Inches(0.3)


def configure_header_footer(section, run_id: str) -> None:
    header = section.header
    header.is_linked_to_previous = False
    p = header.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    p.paragraph_format.space_after = Pt(0)
    run = p.add_run(f"FUNCTIONAL VERIFICATION  /  RUN {run_id}")
    run.font.name = "Aptos"
    run.font.size = Pt(7.5)
    run.font.bold = True
    run.font.color.rgb = RGBColor.from_string(COLORS["muted"])
    footer = section.footer
    footer.is_linked_to_previous = False
    fp = footer.paragraphs[0]
    add_page_number(fp)


def set_document_defaults(doc: Document) -> None:
    styles = doc.styles
    normal = styles["Normal"]
    normal.font.name = "Microsoft YaHei"
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    normal.font.size = Pt(9.5)
    normal.font.color.rgb = RGBColor.from_string(COLORS["ink"])
    normal.paragraph_format.space_after = Pt(4)
    normal.paragraph_format.line_spacing_rule = WD_LINE_SPACING.SINGLE

    for name, size, color, before, after in (
        ("Title", 30, COLORS["navy"], 0, 10),
        ("Heading 1", 18, COLORS["navy"], 12, 6),
        ("Heading 2", 12, COLORS["blue"], 9, 4),
        ("Heading 3", 10, COLORS["cyan"], 7, 3),
    ):
        style = styles[name]
        style.font.name = "Microsoft YaHei"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string(color)
        style.paragraph_format.space_before = Pt(before)
        style.paragraph_format.space_after = Pt(after)
        style.paragraph_format.keep_with_next = True


def add_heading(doc: Document, text: str, level: int = 1):
    return doc.add_heading(text, level=level)


def add_body(doc: Document, text: str, *, bold_prefix: str | None = None):
    p = doc.add_paragraph()
    if bold_prefix and text.startswith(bold_prefix):
        p.add_run(bold_prefix).bold = True
        p.add_run(text[len(bold_prefix):])
    else:
        p.add_run(text)
    return p


def add_bullet(doc: Document, text: str, level=0):
    p = doc.add_paragraph(style="List Bullet" if level == 0 else "List Bullet 2")
    p.paragraph_format.space_after = Pt(2)
    p.add_run(text)
    return p


def add_callout(doc: Document, title: str, body: str, kind="blue") -> None:
    palette = {
        "blue": (COLORS["blue"], "EAF0FF"),
        "red": (COLORS["red"], COLORS["red_bg"]),
        "amber": (COLORS["amber"], COLORS["amber_bg"]),
        "green": (COLORS["green"], COLORS["green_bg"]),
    }
    accent, background = palette[kind]
    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = True
    set_table_borders(table, color=background, size="0")
    cell = table.cell(0, 0)
    set_cell_shading(cell, background)
    set_cell_margins(cell, top=130, start=170, bottom=130, end=170)
    p = cell.paragraphs[0]
    p.paragraph_format.space_after = Pt(3)
    r = p.add_run(title)
    r.bold = True
    r.font.name = "Microsoft YaHei"
    r.font.size = Pt(10)
    r.font.color.rgb = RGBColor.from_string(accent)
    p2 = cell.add_paragraph()
    p2.paragraph_format.space_after = Pt(0)
    p2.paragraph_format.line_spacing = 1.08
    r2 = p2.add_run(body)
    r2.font.name = "Microsoft YaHei"
    r2.font.size = Pt(9)
    r2.font.color.rgb = RGBColor.from_string(COLORS["ink"])
    doc.add_paragraph().paragraph_format.space_after = Pt(0)


def add_simple_table(doc: Document, headers: list[str], rows: list[list[str]], widths=None):
    table = doc.add_table(rows=1, cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    table.style = "Table Grid"
    set_table_borders(table)
    for idx, header in enumerate(headers):
        cell = table.rows[0].cells[idx]
        set_cell_shading(cell, COLORS["navy"])
        set_cell_text(cell, header, bold=True, color=COLORS["white"], size=8)
    set_repeat_table_header(table.rows[0])
    for row_data in rows:
        row = table.add_row()
        prevent_row_split(row)
        for idx, value in enumerate(row_data):
            set_cell_text(row.cells[idx], value, size=8)
        if len(table.rows) % 2 == 1:
            for cell in row.cells:
                set_cell_shading(cell, COLORS["soft"])
    if widths:
        set_column_widths(table, widths)
    return table


def severity_color(severity: str) -> tuple[str, str]:
    if severity == "P0":
        return COLORS["red"], COLORS["red_bg"]
    if severity == "P1":
        return COLORS["amber"], COLORS["amber_bg"]
    return COLORS["blue"], "EAF0FF"


def add_issue_block(doc: Document, issue: dict) -> None:
    accent, bg = severity_color(issue["severity"])
    table = doc.add_table(rows=1, cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_table_borders(table, color=COLORS["line"])
    left, right = table.rows[0].cells
    set_cell_shading(left, accent)
    set_cell_text(left, f"{issue['severity']}\n{issue['id']}", bold=True, color=COLORS["white"], size=9, align=WD_ALIGN_PARAGRAPH.CENTER)
    set_cell_shading(right, bg)
    set_cell_margins(right, top=100, start=130, bottom=100, end=130)
    right.text = ""
    p = right.paragraphs[0]
    p.paragraph_format.space_after = Pt(3)
    r = p.add_run(f"{issue['module']} · {issue['title']}")
    r.bold = True
    r.font.name = "Microsoft YaHei"
    r.font.size = Pt(9.5)
    r.font.color.rgb = RGBColor.from_string(accent)
    for label, value in (("实际", issue["observed"]), ("定位", issue["root"]), ("建议", issue["suggestion"])):
        p = right.add_paragraph()
        p.paragraph_format.space_after = Pt(1)
        p.paragraph_format.line_spacing = 1.02
        label_run = p.add_run(f"{label}：")
        label_run.bold = True
        label_run.font.name = "Microsoft YaHei"
        label_run.font.size = Pt(8)
        value_run = p.add_run(value)
        value_run.font.name = "Microsoft YaHei"
        value_run.font.size = Pt(8)
    set_column_widths(table, [0.75, 5.85])
    doc.add_paragraph().paragraph_format.space_after = Pt(0)


def generate_docx(raw: dict, reviewed: list[dict], out_path: Path) -> None:
    raw_counts = raw["counts"]
    reviewed_counts = counts_for(reviewed)
    modules = module_counts(reviewed)
    executed = reviewed_counts["PASS"] + reviewed_counts["FAIL"]
    pass_rate = reviewed_counts["PASS"] / executed * 100 if executed else 0

    doc = Document()
    set_document_defaults(doc)
    configure_section(doc.sections[0], landscape=False)
    configure_header_footer(doc.sections[0], raw["runId"])

    # Memo masthead.
    mast = doc.add_table(rows=1, cols=2)
    mast.alignment = WD_TABLE_ALIGNMENT.CENTER
    mast.autofit = False
    set_table_borders(mast, color=COLORS["navy"], size="0")
    left, right = mast.rows[0].cells
    set_cell_shading(left, COLORS["navy"])
    set_cell_text(left, "OMNIDOC  /  QUALITY", bold=True, color=COLORS["white"], size=10)
    set_cell_shading(right, COLORS["blue"])
    set_cell_text(right, "FUNCTIONAL VERIFICATION", bold=True, color=COLORS["white"], size=9, align=WD_ALIGN_PARAGRAPH.RIGHT)
    set_column_widths(mast, [3.35, 3.35])

    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(42)
    p.paragraph_format.space_after = Pt(2)
    r = p.add_run("功能测试报告")
    r.font.name = "Microsoft YaHei"
    r._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    r.font.size = Pt(31)
    r.font.bold = True
    r.font.color.rgb = RGBColor.from_string(COLORS["navy"])
    p2 = doc.add_paragraph()
    p2.paragraph_format.space_after = Pt(22)
    r2 = p2.add_run("FULL-STACK FUNCTIONAL TEST REPORT")
    r2.font.name = "Aptos Display"
    r2.font.size = Pt(13)
    r2.font.bold = True
    r2.font.color.rgb = RGBColor.from_string(COLORS["blue"])

    meta_rows = [
        ["项目", "OmniDoc"],
        ["测试日期", "2026-07-25（Asia/Shanghai）"],
        ["执行时段", f"00:19:03 ～ {local_time(raw['finishedAt']).split(' ')[1]}"],
        ["AI 模型", raw["environment"]["model"]],
        ["入口", f"{raw['environment']['webBase']}  /  {raw['environment']['gatewayBase']}"],
        ["运行标识", raw["runId"]],
    ]
    add_simple_table(doc, ["FIELD", "VALUE"], meta_rows, widths=[1.35, 5.35])

    doc.add_paragraph().paragraph_format.space_after = Pt(8)
    add_callout(
        doc,
        "发布建议  /  HOLD",
        "核心主流程可运行，但存在 1 个 P0 安全问题与多项 P1 数据一致性/身份/检索问题。修复并回归前，不建议部署为公开互联网生产服务。",
        kind="red",
    )

    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(16)
    p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    r = p.add_run("Prepared by Codex  ·  Evidence-backed local verification")
    r.font.name = "Aptos"
    r.font.size = Pt(8)
    r.font.color.rgb = RGBColor.from_string(COLORS["muted"])

    doc.add_page_break()

    add_heading(doc, "1  执行摘要", 1)
    add_body(
        doc,
        f"自动化原始结果为 142 条：{raw_counts['pass']} 通过、{raw_counts['fail']} 异常、{raw_counts['skip']} 未覆盖。"
        "人工复核 AGT-015 响应后确认其状态 INDEXED、进度 100%、无错误，属于测试轮询器状态枚举误判；"
        f"复核结果为 {reviewed_counts['PASS']} 通过、{reviewed_counts['FAIL']} 个已确认异常、"
        f"{reviewed_counts['SKIP']} 未覆盖，已执行用例通过率 {pass_rate:.2f}%。"
    )
    add_callout(
        doc,
        "重要口径",
        "本报告保留原始自动化统计，同时给出人工复核统计。清理阶段另发现 AIF-007 文件删除确认异常，因此产品异常总数为 10 条。",
        kind="blue",
    )

    summary_rows = [
        ["自动化原始", "142", str(raw_counts["pass"]), str(raw_counts["fail"]), str(raw_counts["skip"]), "保留脚本原始判断"],
        ["人工复核", "142", str(reviewed_counts["PASS"]), str(reviewed_counts["FAIL"]), str(reviewed_counts["SKIP"]), "AGT-015 修正为通过"],
        ["清理追加", "1", "0", "1", "0", "AIF-007：删除确认失败"],
    ]
    add_simple_table(
        doc,
        ["口径", "总数", "通过", "异常", "未覆盖", "说明"],
        summary_rows,
        widths=[1.05, 0.6, 0.6, 0.6, 0.75, 3.1],
    )

    add_heading(doc, "1.1  能力结论", 2)
    for text in (
        "可运行：用户注册登录、用户资料、文件上传/下载/预览/搜索/复制/分片上传、文档与共享协作、评论与批注基本 CRUD。",
        "可运行：AI 同步/异步文本处理、AI 文件上传下载、技能列表、RAG 索引/向量检索/问答、Agent、Agent 知识索引、AIOps、HTML PPT 生成/预览/下载。",
        "存在异常：匿名用户详情、文件移动、评论/批注身份、AI 文件版本和删除、RAG 混合检索、无效模型校验、跨用户下载错误语义。",
        "未覆盖：真实危险操作确认、失败步骤续接、回收站恢复、历史版本覆盖切换、WebSocket/STOMP 双客户端广播。",
    ):
        add_bullet(doc, text)

    add_heading(doc, "1.2  模块统计（人工复核）", 2)
    module_rows = [
        [name, str(c["TOTAL"]), str(c["PASS"]), str(c["FAIL"]), str(c["SKIP"])]
        for name, c in modules.items()
    ]
    add_simple_table(doc, ["模块", "总数", "通过", "异常", "未覆盖"], module_rows, widths=[2.4, 0.8, 0.8, 0.8, 0.9])

    add_heading(doc, "2  已确认异常", 1)
    add_body(doc, "严重度定义：P0 为必须立即处理的安全/数据暴露问题；P1 为阻断核心能力或破坏数据/身份一致性；P2 为错误语义、输入校验或质量问题。")
    for issue in ANOMALIES:
        add_issue_block(doc, issue)

    add_heading(doc, "3  测试范围与方法", 1)
    methodology = [
        "通过 Gateway 8080 调用真实微服务；前端通过 5173 验证生产构建与 SPA 路由。",
        "使用临时所有者和协作者账号，覆盖匿名、本人、跨用户、只读共享与可编辑共享。",
        f"AI 实际调用 {raw['environment']['model']}；密钥只从环境变量读取，报告不包含密钥。",
        "测试完成后删除临时用户、文档、文件、RAG 与 Agent 数据；因删除接口异常，精确清理 MinIO 测试对象并复查 runId 无残留。",
        "具有真实删除/覆盖风险且无法安全构造前置状态的用例标记为未覆盖。",
        "未进行浏览器双客户端实时交互；共享权限与跨账号编辑已通过 API 用例验证。",
    ]
    for text in methodology:
        add_bullet(doc, text)

    add_heading(doc, "3.1  环境状态", 2)
    env_rows = [
        ["应用", "Gateway 8080；User 8081；File 8082；AI 8083；Document 8084；Web 5173", "全部监听"],
        ["基础设施", "MySQL 3306；Redis 6379；RabbitMQ 5672；MinIO 9000", "全部监听"],
        ["运行时", f"{raw['environment']['platform']}；Node {raw['environment']['node']}；Java 17", "已验证"],
        ["AI", raw["environment"]["model"], "同步、异步、RAG、Agent、PPT 均有真实返回"],
    ]
    add_simple_table(doc, ["类别", "配置", "状态"], env_rows, widths=[1.0, 4.75, 0.95])

    add_heading(doc, "4  构建与测试工程", 1)
    add_body(doc, "以下结果用于评估自动化测试基线，不与真实端到端业务用例混算。")
    add_simple_table(doc, ["范围", "结论", "返回结果 / 异常"], [list(row) for row in TEST_INFRA], widths=[1.35, 0.75, 4.6])

    add_heading(doc, "5  未覆盖用例", 1)
    skip_rows = [
        [item["id"], item["module"], item["name"], item["detail"]]
        for item in reviewed
        if item["status"] == "SKIP"
    ]
    add_simple_table(doc, ["ID", "模块", "用例", "原因"], skip_rows, widths=[0.75, 0.9, 1.5, 3.55])

    # Full case appendix in landscape.
    section = doc.add_section(WD_SECTION.NEW_PAGE)
    configure_section(section, landscape=True)
    configure_header_footer(section, raw["runId"])
    add_heading(doc, "6  全量功能用例明细", 1)
    add_body(
        doc,
        "HTTP / 业务码中的“—”表示二进制响应、TCP 检查或接口未提供统一业务码。"
        "返回结果为脚本采集的完整响应摘要；AGT-015 的响应内容保持原样，仅修正复核结论。",
    )

    headers = ["ID", "模块", "测试用例 / 输入", "预期", "HTTP /\n业务码", "耗时", "结论", "是否异常", "返回结果 / 备注"]
    table = doc.add_table(rows=1, cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    table.style = "Table Grid"
    set_table_borders(table, color="CAD3DF", size="3")
    for idx, header in enumerate(headers):
        cell = table.rows[0].cells[idx]
        set_cell_shading(cell, COLORS["navy"])
        set_cell_text(cell, header, bold=True, color=COLORS["white"], size=6.8, align=WD_ALIGN_PARAGRAPH.CENTER)
    set_repeat_table_header(table.rows[0])

    for item in reviewed:
        row = table.add_row()
        prevent_row_split(row)
        row.height_rule = WD_ROW_HEIGHT_RULE.AT_LEAST
        combined_name = f"{item['name']}\n输入：{item['input']}"
        combined_result = item["result"]
        if item["detail"]:
            combined_result += f"\n备注：{item['detail']}"
        values = [
            item["id"],
            item["module"],
            combined_name,
            item["expected"],
            code_pair(item),
            f"{item['elapsedMs']} ms",
            status_text(item["status"]),
            item["anomaly"],
            combined_result,
        ]
        for idx, value in enumerate(values):
            align = WD_ALIGN_PARAGRAPH.CENTER if idx in (0, 1, 4, 5, 6, 7) else WD_ALIGN_PARAGRAPH.LEFT
            set_cell_text(row.cells[idx], value, size=6.4, align=align)
        if item["status"] == "FAIL":
            set_cell_shading(row.cells[6], COLORS["red_bg"])
            set_cell_text(row.cells[6], "异常", bold=True, color=COLORS["red"], size=6.6, align=WD_ALIGN_PARAGRAPH.CENTER)
        elif item["status"] == "SKIP":
            set_cell_shading(row.cells[6], COLORS["amber_bg"])
            set_cell_text(row.cells[6], "未覆盖", bold=True, color=COLORS["amber"], size=6.6, align=WD_ALIGN_PARAGRAPH.CENTER)
        else:
            set_cell_shading(row.cells[6], COLORS["green_bg"])
            set_cell_text(row.cells[6], "通过", bold=True, color=COLORS["green"], size=6.6, align=WD_ALIGN_PARAGRAPH.CENTER)
        if len(table.rows) % 2 == 1:
            for idx, cell in enumerate(row.cells):
                if idx != 6:
                    set_cell_shading(cell, "F7F9FC")

    set_column_widths(table, [0.55, 0.62, 1.55, 1.42, 0.65, 0.58, 0.55, 0.72, 4.0])

    section2 = doc.add_section(WD_SECTION.NEW_PAGE)
    configure_section(section2, landscape=False)
    configure_header_footer(section2, raw["runId"])
    add_heading(doc, "7  回归建议与证据", 1)
    priorities = [
        "立即修复 SEC-002，增加匿名、本人、普通他人、管理员四类用户详情授权测试。",
        "修复 FIL-012、COM-001、ANN-001，回归对象一致性、身份归属与跨账号共享权限。",
        "修复 AIF-004、AIF-007、RAG-009、RAG-010，增加真实 Redis/MinIO 集成测试。",
        "统一权限异常 HTTP 语义；无效模型代码返回 400，并明确实际模型。",
        "修正 file/gateway 测试包路径，建立测试 profile 注入 MinIO/AI 环境，使 Maven 聚合测试一次性通过。",
    ]
    for idx, item in enumerate(priorities, 1):
        p = doc.add_paragraph(style="List Number")
        p.add_run(item)

    add_heading(doc, "7.1  可重复执行", 2)
    evidence_rows = [
        ["功能测试脚本", "scripts/run-functional-tests.mjs"],
        ["原始结果", f".local/test-results/{DEFAULT_RESULT.name}"],
        ["执行命令", "node scripts/run-functional-tests.mjs"],
        ["严格模式", "设置 STRICT_FUNCTIONAL_TESTS=1 后执行同一脚本"],
        ["前端构建", "cd web；npm.cmd run build"],
        ["前端单测", "cd web；node --test src/api/asyncJob.test.mjs"],
        ["后端测试", r".\mvnw.cmd -s .mvn\settings-local.xml test"],
    ]
    add_simple_table(doc, ["证据 / 操作", "路径 / 命令"], evidence_rows, widths=[1.65, 5.05])

    add_callout(
        doc,
        "清理确认",
        "临时用户、文档、文件元数据、RAG 与 Agent 数据均已清理；AIF-007 导致 API 删除失败的测试对象已按精确路径清理，随后确认本轮 runId 无 MinIO 残留。",
        kind="green",
    )

    core = doc.core_properties
    core.title = "OmniDoc 功能测试报告"
    core.subject = "2026-07-25 全栈功能验证"
    core.author = "Codex"
    core.keywords = "OmniDoc, functional testing, QA, API, report"
    core.comments = "Generated from reproducible functional test evidence."

    out_path.parent.mkdir(parents=True, exist_ok=True)
    doc.save(out_path)


def main() -> None:
    args = parse_args()
    raw = load_results(args.input)
    reviewed = reviewed_results(raw)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    markdown_path = args.output_dir / f"{REPORT_BASENAME}.md"
    docx_path = args.output_dir / f"{REPORT_BASENAME}.docx"
    generate_markdown(raw, reviewed, markdown_path)
    generate_docx(raw, reviewed, docx_path)
    print(json.dumps(
        {
            "markdown": str(markdown_path),
            "docx": str(docx_path),
            "rawCounts": raw["counts"],
            "reviewedCounts": counts_for(reviewed),
            "confirmedProductAnomalies": len(ANOMALIES),
        },
        ensure_ascii=False,
        indent=2,
    ))


if __name__ == "__main__":
    main()
