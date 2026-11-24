"""Vanilla entity NBT field names are CamelCase, and `nbt={...}` matching is CASE-SENSITIVE.

`@e[nbt={onground:1b}]` is valid syntax that matches NOTHING, for ever, and says so nowhere:
CBScript compiles it, Minecraft accepts the command, the datapack loads, and the rule silently never
fires. There is no error to see and no output to miss — the only symptom is that the thing the
author asked for does not happen.

MEASURED, 2026-08-19, across 15 generated datapacks: `OnGround` was wrong-cased 33 times in 10 of
them, and `FallDistance` 12 times in one more. Every one of those packs compiled, loaded and
reported success.

And the guidance already existed. CBSCRIPT_LESSONS.md has said, in as many words and correctly
capitalised, `/execute if entity @s[nbt={OnGround:1b}] run damage @s 6 minecraft:falling_block`
since the Grumbnar work. It was written down, it was read, and the lowercase form was emitted
anyway — which is why this is a transform in the compiler rather than another line in the lessons.

CORRECTED, NOT REFUSED. The lowercase spelling is never right, the fix is mechanical, and only exact
known field names are touched, so correcting cannot change what a working script does. A warning is
printed in the same style as the rest of the compiler so the author still learns.
"""
import re

import mc_target

# Vanilla entity NBT fields whose capitalisation is load-bearing.
#
# Deliberately short: every entry can only be one thing, so rewriting it is safe. A field a custom
# entity might legitimately spell in lower case does not belong here.
FIELDS = [
	'OnGround', 'FallDistance', 'Motion', 'Rotation', 'Invulnerable', 'NoGravity',
	'Silent', 'CustomName', 'CustomNameVisible', 'Health', 'Fire', 'Air', 'Glowing',
	'PortalCooldown', 'Passengers', 'Tags',
]

# Only inside a selector's nbt={...} argument.
#
# Scope matters more than the list does. The same words appear legitimately elsewhere and rewriting
# them would BREAK working scripts: `minecraft:air` is a block, `tag=` in a selector really is lower
# case, and a scoreboard the author named `health` is theirs to name. A first pass over whole files
# flagged 299 "errors", essentially all of them this.
NBT_BLOCK = re.compile(r'nbt=\{[^}]*\}')

_warned = set()


def fix_nbt_case(command):
	"""Return the command with known vanilla NBT fields correctly capitalised.

	Warns once per distinct misspelling per run, so a script with the same mistake in four places
	produces one line of output rather than four.
	"""
	if not command or 'nbt=' not in command:
		return command

	# Fields the target renamed (FallDistance -> fall_distance, mc_target.entity_nbt_renames): either
	# spelling, in any case, becomes the one THIS target reads.
	renames = mc_target.entity_nbt_renames()

	def fix_block(match):
		block = match.group(0)
		for old, (new, kind) in renames.items():
			# The key, and the number after it: the field's TYPE changed too, and nbt={...} matching
			# compares tag types (NbtUtils.compareNbt), so a float 0f or an int 0 matches a double field
			# no better than the old name does.
			pattern = re.compile(r'([{,]\s*)(' + old + r'|' + new + r')(\s*:\s*)(-?(?:\d+\.?\d*|\.\d+)[bBsSlLfFdD]?)?',
				re.IGNORECASE)

			def rename(m, new=new, kind=kind):
				value = m.group(4) or ''
				if value and kind == 'd' and value[-1] not in 'dD' and (value[-1].isalpha() or '.' not in value):
					value = (value[:-1] if value[-1].isalpha() else value) + 'd'
				if m.group(2) != new or value != (m.group(4) or ''):
					key = (m.group(2), new)
					if key not in _warned:
						_warned.add(key)
						print(f'Warning: "{m.group(2)}" should be "{new}" (a double, e.g. {new}:0d) on this '
							f'Minecraft — nbt={{...}} matching is case- and type-sensitive and the old form '
							f'matches nothing. Corrected.')
				return m.group(1) + new + m.group(3) + value

			block = pattern.sub(rename, block)
		for field in FIELDS:
			if field in renames:
				continue
			# A field name sits after `{` or `,` and before `:`. Anchoring on that is what stops a
			# VALUE that happens to read like a field name from being rewritten inside the data.
			pattern = re.compile(r'([{,]\s*)(' + field + r')(\s*:)', re.IGNORECASE)

			def repl(m):
				if m.group(2) != field:
					key = (m.group(2), field)
					if key not in _warned:
						_warned.add(key)
						print(f'Warning: "{m.group(2)}" should be "{field}" — vanilla NBT field '
							f'names are CamelCase and nbt={{...}} matching is case-sensitive, so '
							f'the lower-case form matches nothing and the rule never fires. '
							f'Corrected.')
				return m.group(1) + field + m.group(3)

			block = pattern.sub(repl, block)
		return block

	return NBT_BLOCK.sub(fix_block, command)


def reset_warnings():
	"""Between compiles, so a long-running process reports each script's own mistakes."""
	_warned.clear()
