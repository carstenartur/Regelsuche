#!/usr/bin/env python3
"""Validate the exchange schema; Java owns freeze binding and oracle replay."""

import argparse
import hashlib
import json
from pathlib import Path

from jsonschema import Draft202012Validator, ValidationError


def canonical(document):
    return json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def check_document(schema, document):
    Draft202012Validator.check_schema(schema)
    try:
        Draft202012Validator(schema).validate(document)
    except ValidationError as error:
        raise ValueError(f"companion schema violation at {list(error.absolute_path)}: {error.message}") from error
    expected = "sha256:" + hashlib.sha256(canonical(document["content"])).hexdigest()
    if expected != document["contentHash"]:
        raise ValueError("companion content hash mismatch")
    return document


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON member: {key}")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError(f"non-JSON numeric constant: {value}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--companion", required=True, type=Path)
    args = parser.parse_args()
    schema = json.loads(args.schema.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    source = args.companion.read_bytes()
    document = json.loads(source.decode("utf-8"), object_pairs_hook=unique_object, parse_constant=reject_constant)
    if canonical(document) != source:
        raise ValueError("companion JSON is not canonical UTF-8")
    check_document(schema, document)
    print("schemaVerifiedCompanionHash=" + document["contentHash"])


if __name__ == "__main__":
    main()
