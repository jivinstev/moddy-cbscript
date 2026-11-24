"""Which Minecraft this compile is for, and the facts that follow from it.

The mod ships ONE bundled compiler, and the version is a runtime fact rather than a branch: the Java
side sets CBSCRIPT_MC to the version it is compiling for (1.21.8 since the mod's 1.21.8 bump). Unset means 1.21.1, the only version this compiler
knew before, so an old caller behaves exactly as it always did.
"""
import os

DEFAULT = '1.21.1'

# pack_format per release, newest first; a version takes the first row it reaches.
# 1.21/1.21.1 = 48, 1.21.2/1.21.3 = 57, 1.21.4 = 61, 1.21.5 = 71, 1.21.6 = 80, 1.21.7/1.21.8 = 81,
# 1.21.9/1.21.10 = 88.0, 1.21.11 = 94.1, 26.1/26.1.1/26.1.2 = 101.1 (read off each server jar's version.json:
# pack_version.data_major/data_minor; 1.21.11's SharedConstants.DATA_PACK_FORMAT_MAJOR/MINOR = 94/1).
# A newer Minecraft than the table knows gets the newest number it has, which that Minecraft reads as
# "made for an older version" and still loads.
PACK_FORMATS = (
	((26, 1), (101, 1)),
	((1, 21, 11), (94, 1)),
	((1, 21, 9), (88, 0)),
	((1, 21, 7), 81),
	((1, 21, 6), 80),
	((1, 21, 5), 71),
	((1, 21, 4), 61),
	((1, 21, 2), 57),
	((0,), 48),
)


def version():
	"""The target as a tuple, e.g. (1, 21, 4). An unparseable value is treated as the default."""
	raw = os.environ.get('CBSCRIPT_MC', DEFAULT)
	try:
		return tuple(int(p) for p in raw.split('.')[:3])
	except ValueError:
		return tuple(int(p) for p in DEFAULT.split('.'))


# The last data-pack format a bare pack_format may declare. From 1.21.9 (88) the game refuses a pack
# whose format is newer than this unless it also declares min_format and max_format
# ("Pack declares support for version newer than 81, but is missing mandatory fields min_format and
# max_format"), so pack_meta() writes both from 1.21.9 on and nothing new before it.
LAST_BARE_FORMAT = 81


def _row():
	v = version()
	for since, fmt in PACK_FORMATS:
		if v >= since:
			return fmt if isinstance(fmt, tuple) else (fmt, 0)
	fmt = PACK_FORMATS[-1][1]
	return fmt if isinstance(fmt, tuple) else (fmt, 0)


def pack_format():
	"""The target's data-pack format's major number (pack.mcmeta's pack_format)."""
	return _row()[0]


def pack_meta(desc):
	"""pack.mcmeta's "pack" object for the target. Up to 1.21.8 exactly what it always was
	({pack_format, description}); from 1.21.9 also min_format and max_format, both the target's own
	[major, minor], which that game requires for a format past LAST_BARE_FORMAT."""
	major, minor = _row()
	pack = {'pack_format': major, 'description': desc}
	if major > LAST_BARE_FORMAT:
		pack['min_format'] = [major, minor]
		pack['max_format'] = [major, minor]
	return pack


# 1.21.11 moved game rules into a registry with snake_case ids, and the old camelCase names are no longer
# commands ("gamerule maxCommandChainLength" is an unknown command there; Mojang's GameRuleRegistryFix maps
# maxCommandChainLength -> minecraft:max_command_sequence_length). The one rule the compiler writes itself.
def max_chain_rule():
	"""The game rule that bounds a function's command chain, as the target's /gamerule spells it."""
	return 'max_command_sequence_length' if version() >= (1, 21, 11) else 'maxCommandChainLength'


# Entity NBT renamed FallDistance (float) to fall_distance (double); the game's datafixer
# (EntityFallDistanceFloatToDoubleFix) converts saved entities, but nbt={FallDistance:...} in a selector
# matches nothing on a game that stores fall_distance. The rename is older than 26.x: in the 1.21.5
# server jar the entity class already writes fall_distance and only the datafixer still names
# FallDistance. The 26.1.2 rung gates it at 26.1 so a 1.21.x compile stays byte-for-byte what its own
# rung writes; lowering this to (1, 21, 5) belongs on master, carried up the ladder.
FALL_DISTANCE_SNAKE_SINCE = (26, 1)


def entity_nbt_renames():
	"""Entity NBT fields the target spells differently from the CamelCase name, {old: new}."""
	return {'FallDistance': 'fall_distance'} if version() >= FALL_DISTANCE_SNAKE_SINCE else {}
