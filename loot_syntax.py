"""Item modifiers in the target's loot-function shape.

An item modifier is written into the pack as its script wrote it. Up to 26.2 a loot function names
itself with "function"; from 26.3 with "type" (mc_target.loot_function_key), and the old key makes the
26.3 server refuse to load ANY data pack, so the key is renamed where it is the function's own name:
the item modifier object itself, or each object of a list of them. Nothing else is touched, and below
26.3 the text is returned exactly as written.

26.3 also refuses shapes this does not rewrite, because the right rewrite is not mechanical: a bare
list of functions ("Not a JSON object") and a function's "conditions" list (now one "condition"
whose own dispatch key is "type"). Those are warned about, in the compiler's usual style.
"""
import json

import mc_target

_warned = set()


def reset_warnings():
	_warned.clear()


def _warn(key, text):
	if key not in _warned:
		_warned.add(key)
		print(f'Warning: {text}')


def _rename(obj, key):
	"""obj with its "function" key renamed to `key`, in the same position."""
	if not isinstance(obj, dict) or 'function' not in obj or key in obj:
		return obj
	if 'conditions' in obj:
		_warn('conditions', 'an item modifier\'s "conditions" list is not read on this Minecraft: write one '
			'"condition": {"type": "minecraft:...", ...} (several: {"type": "minecraft:all_of", "terms": [...]}).')
	return {(key if k == 'function' else k): v for k, v in obj.items()}


def fix_item_modifier(text):
	"""The item modifier JSON text `text` in the target's shape."""
	key = mc_target.loot_function_key()
	if key == 'function':
		return text
	try:
		data = json.loads(text)
	except (TypeError, ValueError):
		return text
	if isinstance(data, list):
		_warn('list', 'an item modifier that is a list of functions is not read on this Minecraft: write one '
			'{"type": "minecraft:sequence", "functions": [...]}.')
		fixed = [_rename(f, key) for f in data]
	else:
		fixed = _rename(data, key)
	if fixed == data:
		return text
	return json.dumps(fixed, separators=(',', ':'), ensure_ascii=False)
