"""Bounded project skill loading; only explicitly selected skills enter context."""
from pathlib import Path
import re


def load_project_skills(workspace, selected):
    root = Path(workspace).resolve()
    if not isinstance(selected, list) or len(selected) > 12:
        raise ValueError('最多启用 12 个项目 Skill')
    found = {}
    for parent_name in ('.aster/skills', '.agents/skills'):
        parent = root / parent_name
        if not parent.is_dir() or parent.is_symlink() or root not in parent.resolve().parents:
            continue
        for directory in sorted(parent.iterdir()):
            if len(found) >= 100:
                break
            if directory.is_symlink() or not directory.is_dir() or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', directory.name):
                continue
            file = directory / 'SKILL.md'
            if file.is_symlink() or not file.is_file() or root not in file.resolve().parents or file.stat().st_size > 16000:
                continue
            found.setdefault(directory.name, file)
    enabled, total = [], 0
    for name in selected:
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', name):
            raise ValueError('Skill 名称仅允许字母、数字、下划线和连字符')
        file = found.get(name)
        if file is None:
            raise ValueError('未找到项目 Skill：' + name)
        content = file.read_text(encoding='utf-8')
        total += len(content)
        if total > 32000:
            raise ValueError('已启用 Skill 内容总计超过 32K 字符')
        enabled.append((name, str(file.relative_to(root)), content))
    return sorted(found), enabled
