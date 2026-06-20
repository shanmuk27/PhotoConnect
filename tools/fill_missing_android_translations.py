from __future__ import annotations

import argparse
import shutil
import re
import time
from collections import OrderedDict
from pathlib import Path
from typing import Iterable
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET
import requests


PLACEHOLDER_RE = re.compile(r"%(?:\d+\$)?(?:[-#+ 0,(<]*)?(?:\d+)?(?:\.\d+)?[a-zA-Z%]")
UNESCAPED_APOSTROPHE_RE = re.compile(r"(?<!\\)'")
SKIP_STRING_NAMES = {
    "app_name",
    "google_maps_key",
}

DIRECT_LOCALE_TARGETS = OrderedDict(
    [
        ("values-as", "as"),
        ("values-bn", "bn"),
        ("values-gu", "gu"),
        ("values-hi", "hi"),
        ("values-kn", "kn"),
        ("values-ml", "ml"),
        ("values-mr", "mr"),
        ("values-ne", "ne"),
        ("values-or", "or"),
        ("values-pa", "pa"),
        ("values-sa", "sa"),
        ("values-sd", "sd"),
        ("values-ta", "ta"),
        ("values-te", "te"),
        ("values-ur", "ur"),
    ]
)

COPY_LOCALE_SOURCES = OrderedDict(
    [
        ("values-b+brx", "values-hi"),
        ("values-b+doi", "values-hi"),
        ("values-b+kok", "values-hi"),
        ("values-b+mai", "values-hi"),
        ("values-b+mni", "values-bn"),
        ("values-b+sat", "values-hi"),
        ("values-ks", "values-ur"),
    ]
)


def mask_text(text: str) -> tuple[str, dict[str, str]]:
    replacements: dict[str, str] = {}

    def repl(match: re.Match[str]) -> str:
        token = f"__PH_{len(replacements)}__"
        replacements[token] = match.group(0)
        return token

    masked = PLACEHOLDER_RE.sub(repl, text)
    masked = masked.replace("\\n", "__NL__")
    replacements["__NL__"] = "\\n"
    return masked, replacements


def unmask_text(text: str, replacements: dict[str, str]) -> str:
    restored = text
    for token, original in replacements.items():
        restored = restored.replace(token, original)
    return restored


def escape_android_text(text: str) -> str:
    escaped = escape(text, {"\"": "\\\""})
    escaped = UNESCAPED_APOSTROPHE_RE.sub("\\\\'", escaped)
    return escaped


def load_existing_strings(path: Path) -> tuple[dict[str, str], dict[str, dict[str, str]]]:
    if not path.exists():
        return {}, {}
    tree = ET.parse(path)
    root = tree.getroot()
    strings: dict[str, str] = {}
    plurals: dict[str, dict[str, str]] = {}
    for child in root:
        name = child.attrib.get("name")
        if not name:
            continue
        if child.tag == "string":
            strings[name] = child.text or ""
        elif child.tag == "plurals":
            plurals[name] = {
                item.attrib.get("quantity", "other"): item.text or ""
                for item in child.findall("item")
            }
    return strings, plurals


def chunked(items: list[str], size: int) -> Iterable[list[str]]:
    for index in range(0, len(items), size):
        yield items[index : index + size]


def translate_many(texts: list[str], target: str) -> list[str]:
    if not texts:
        return []
    separator = "\uE000"
    translated: list[str] = []
    chunk: list[str] = []
    chunk_length = 0

    def flush(items: list[str]) -> None:
        if not items:
            return
        joined = separator.join(items)
        try:
            response = requests.get(
                "https://translate.googleapis.com/translate_a/single",
                params={
                    "client": "gtx",
                    "sl": "en",
                    "tl": target,
                    "dt": "t",
                    "q": joined,
                },
                timeout=30,
            )
            response.raise_for_status()
            data = response.json()
            output = "".join(part[0] for part in data[0])
            parts = output.split(separator)
            if len(parts) != len(items):
                raise ValueError("separator was not preserved")
            translated.extend(parts)
        except Exception:
            for item in items:
                response = requests.get(
                    "https://translate.googleapis.com/translate_a/single",
                    params={
                        "client": "gtx",
                        "sl": "en",
                        "tl": target,
                        "dt": "t",
                        "q": item,
                    },
                    timeout=30,
                )
                response.raise_for_status()
                data = response.json()
                translated.append("".join(part[0] for part in data[0]))
                time.sleep(0.05)
        time.sleep(0.1)
    
    for item in texts:
        projected = chunk_length + len(item) + (1 if chunk else 0)
        if chunk and projected > 3500:
            flush(chunk)
            chunk = []
            chunk_length = 0
        chunk.append(item)
        chunk_length += len(item)
    flush(chunk)
    return translated


def translate_missing_locale(base_path: Path, locale_dir: Path, target_language: str) -> None:
    base_tree = ET.parse(base_path)
    base_root = base_tree.getroot()
    locale_path = locale_dir / "strings.xml"
    existing_strings, existing_plurals = load_existing_strings(locale_path)

    pending_string_names: list[str] = []
    pending_string_texts: list[str] = []
    pending_plural_keys: list[tuple[str, str]] = []
    pending_plural_texts: list[str] = []
    masks: dict[tuple[str, str | None], dict[str, str]] = {}

    for child in base_root:
        name = child.attrib.get("name")
        if not name:
            continue
        if child.tag == "string":
            if name in existing_strings:
                continue
            raw = child.text or ""
            if not raw or name in SKIP_STRING_NAMES:
                existing_strings[name] = raw
                continue
            masked, replacements = mask_text(raw)
            pending_string_names.append(name)
            pending_string_texts.append(masked)
            masks[(name, None)] = replacements
        elif child.tag == "plurals":
            existing_items = existing_plurals.setdefault(name, {})
            for item in child.findall("item"):
                quantity = item.attrib.get("quantity", "other")
                if quantity in existing_items:
                    continue
                raw = item.text or ""
                if not raw:
                    existing_items[quantity] = raw
                    continue
                masked, replacements = mask_text(raw)
                pending_plural_keys.append((name, quantity))
                pending_plural_texts.append(masked)
                masks[(name, quantity)] = replacements

    for name, translated in zip(
        pending_string_names,
        translate_many(pending_string_texts, target_language),
    ):
        existing_strings[name] = unmask_text(translated, masks[(name, None)])

    for (name, quantity), translated in zip(
        pending_plural_keys,
        translate_many(pending_plural_texts, target_language),
    ):
        existing_plurals.setdefault(name, {})[quantity] = unmask_text(
            translated,
            masks[(name, quantity)],
        )

    locale_dir.mkdir(parents=True, exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for child in base_root:
        name = child.attrib.get("name")
        if not name:
            continue
        attrs = " ".join(
            f'{key}="{escape_android_text(value)}"'
            for key, value in child.attrib.items()
        )
        if child.tag == "string":
            value = existing_strings.get(name, child.text or "")
            lines.append(f"    <string {attrs}>{escape_android_text(value)}</string>")
        elif child.tag == "plurals":
            lines.append(f"    <plurals {attrs}>")
            quantities = existing_plurals.get(name, {})
            for item in child.findall("item"):
                quantity = item.attrib.get("quantity", "other")
                value = quantities.get(quantity, item.text or "")
                lines.append(
                    f'        <item quantity="{quantity}">{escape_android_text(value)}</item>'
                )
            lines.append("    </plurals>")
    lines.append("</resources>")
    locale_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Fill missing Android locale string resources using machine translation."
    )
    parser.add_argument(
        "--res-dir",
        default="android_project/app/src/main/res",
        help="Android res directory containing values*/strings.xml",
    )
    parser.add_argument(
        "--locales",
        nargs="*",
        help="Optional subset of locale directories to update, for example values-hi values-te",
    )
    args = parser.parse_args()

    res_dir = Path(args.res_dir)
    base_path = res_dir / "values" / "strings.xml"
    if not base_path.exists():
        raise SystemExit(f"Base strings file not found: {base_path}")

    selected = set(args.locales or [])

    for locale_name, target_language in DIRECT_LOCALE_TARGETS.items():
        if selected and locale_name not in selected:
            continue
        locale_dir = res_dir / locale_name
        print(f"Updating {locale_name} using '{target_language}' translations...")
        translate_missing_locale(base_path, locale_dir, target_language)

    for locale_name, source_locale in COPY_LOCALE_SOURCES.items():
        if selected and locale_name not in selected:
            continue
        source_path = res_dir / source_locale / "strings.xml"
        if not source_path.exists():
            raise SystemExit(f"Source locale file not found for copy: {source_path}")
        target_dir = res_dir / locale_name
        target_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source_path, target_dir / "strings.xml")
        print(f"Copied {source_locale} translations into {locale_name}.")

    print("Done.")


if __name__ == "__main__":
    main()
