# 牛码词组续写关联索引

本工具从本地 `input-data/niumawb_zc.dict.yaml` 建立“已输入前缀 → 词条剩余后缀”索引。只使用用户字典，不下载 `predict.db`、外部词库或语料，也不会修改源词典。生成文件是待主程序审阅/整合的数据，不在此处 stage 或安装到生产资产。

## 生成

在项目根目录运行：

```powershell
python association-tools/build_associations.py
```

默认写入 `association-tools/generated/niuma_associations.json`。可用 `--source`、`--output` 指定路径，`--limit N` 调整每个前缀最多保留的候选数（默认 12，必须为正数）。命令输出 key 数、候选条目总数、输出字节数和源文件 SHA-256。

脚本只依赖 Python 标准库。它从 YAML `columns` 头解析 `text`、`code` 等列名；只使用 `text` 列构造候选，`code` 及可选尾列（如 `stem+fh`）不参与关联。每条词需为 2–16 个纯 Han/CJK 统一或兼容表意文字；混有拉丁字母、标点、空格、emoji、选择符等的词会整体过滤。对词条按 1 至 `min(4, 词长-1)` 个 Unicode 码点长度枚举严格前缀，续写值就是词条余下文本。前缀键按源文件首次遇到顺序输出；每键候选按源行首次出现顺序去重，再应用限额。

输出为紧凑 UTF-8 JSON（保留 CJK 字符、固定对象字段/数组顺序、末尾一个 LF），包含 `format`、说明、精确源文件字节的 `source_sha256` 和 `entries`。无时间戳或随机值。

## 测试

```powershell
python -m unittest discover -s association-tools -p "test_*.py" -v
```

测试使用内嵌小型 fixture，不读取或改动主词典；覆盖真实文本续写、编码列不参与、候选稳定排序与去重、精确源哈希、纯 CJK/符号过滤、补充平面汉字和候选数上限。

## 限制

这是确定性的字典前缀关联，不是统计语言模型，不利用上下文、词频或用户历史，不保证与百度联想质量相当。源字典编码/表头或 Han 范围策略变化时，生成结果可能变化；交付时应检查哈希和统计指标，再由主程序决定是否集成。
