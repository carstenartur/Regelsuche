"""Instrument evaluated guards in the original json.loads, including its real native BOM test."""

import ast
import hashlib
import json
import json.decoder
from pathlib import Path


def _record(label):
    return ast.Call(ast.Name('_record_admission', ast.Load()), [ast.Constant('json.loads.' + label)], [])


def _before(node, label):
    return ast.Subscript(ast.Tuple([_record(label), node], ast.Load()), ast.Constant(1), ast.Load())


class _Guards(ast.NodeTransformer):
    def visit_Compare(self, node):
        if (len(node.ops) == 1 and isinstance(node.ops[0], ast.Is)
                and isinstance(node.left, ast.Name) and isinstance(node.comparators[0], ast.Constant)
                and node.comparators[0].value is None):
            return ast.copy_location(_before(node, node.left.id + '.is-none'), node)
        return node

    def visit_UnaryOp(self, node):
        if isinstance(node.op, ast.Not) and isinstance(node.operand, ast.Name) and node.operand.id == 'kw':
            return ast.copy_location(_before(node, 'not-kw'), node)
        return self.generic_visit(node)

    def visit_Call(self, node):
        if isinstance(node.func, ast.Name) and node.func.id == 'isinstance':
            return ast.copy_location(_before(node, 'isinstance'), node)
        if not isinstance(node.func, ast.Attribute) or not isinstance(node.func.value, ast.Name):
            return self.generic_visit(node)
        if node.func.value.id == 's' and node.func.attr == 'startswith':
            # The wrapped operation evaluates once, between the original native call's scope markers.
            seq = ast.Tuple([_record('before-bom'), node, _record('after-bom')], ast.Load())
            return ast.copy_location(ast.Subscript(seq, ast.Constant(1), ast.Load()), node)
        if node.func.value.id == '_default_decoder' and node.func.attr == 'decode':
            return ast.copy_location(_before(node, 'before-decode'), node)
        return self.generic_visit(node)


def instrument_loads(source, original, record):
    function = next(node for node in ast.parse(source).body
                    if isinstance(node, ast.FunctionDef) and node.name == 'loads')
    body = ast.Module([_Guards().visit(function)], [])
    namespace = dict(original.__globals__, _record_admission=record)
    exec(compile(ast.fix_missing_locations(body), original.__code__.co_filename, 'exec'), namespace)
    return namespace['loads']


def install(record):
    source = Path(json.__file__).read_bytes()
    if hashlib.sha256(source).hexdigest() != 'd73226ed88a493ca799d25259c4d62c79799b6c13f581f0124b7b744566d7836':
        raise ValueError('loaded json.loads source differs from the 25.1.3 resource')
    json.loads = instrument_loads(source.decode('utf-8'), json.loads, record)

def instrument_decoder(source, original, record):
    """Scope only the leading pattern match, preserving the compiled pattern and bound match method."""
    module = ast.parse(source)
    classifier = next(n for n in module.body if isinstance(n, ast.ClassDef) and n.name == 'JSONDecoder')
    func = next(n for n in classifier.body if isinstance(n, ast.FunctionDef) and n.name == 'decode')
    class Leading(ast.NodeTransformer):
        def visit_Call(self, node):
            if isinstance(node.func, ast.Name) and node.func.id == '_w' and isinstance(node.args[1], ast.Constant):
                marker = ast.Call(ast.Name('_record_admission', ast.Load()),
                                  [ast.Constant('json.decode.leading-match')], [])
                return ast.copy_location(ast.Subscript(ast.Tuple([marker, node], ast.Load()),
                                                       ast.Constant(1), ast.Load()), node)
            return self.generic_visit(node)
    body = ast.Module([Leading().visit(func)], [])
    namespace = dict(original.__globals__, _record_admission=record)
    exec(compile(ast.fix_missing_locations(body), original.__code__.co_filename, 'exec'), namespace)
    replacement = namespace['decode']
    replacement.__defaults__ = original.__defaults__
    return replacement


def install_regex(record):
    install(record)
    source = Path(json.decoder.__file__).read_bytes()
    if hashlib.sha256(source).hexdigest() != '93edfe9887f626ea977c2e4d4f2a6e0797cb89414b06ff0415c36cdde1d4143e':
        raise ValueError('loaded json.decoder source differs from the 25.1.3 resource')
    json.decoder.JSONDecoder.decode = instrument_decoder(source.decode('utf-8'), json.decoder.JSONDecoder.decode, record)
