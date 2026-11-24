"""Which Minecraft this compile is for, and the facts that follow from it.

The mod ships ONE bundled compiler, and the version is a runtime fact rather than a branch: the Java
side sets CBSCRIPT_MC to the version it is compiling for (1.21.8 since the mod's 1.21.8 bump). Unset means 1.21.1, the only version this compiler
knew before, so an old caller behaves exactly as it always did.
"""
import os

DEFAULT = '1.21.1'

# pack_format per release, newest first; a version takes the first row it reaches.
# 1.21/1.21.1 = 48, 1.21.2/1.21.3 = 57, 1.21.4 = 61, 1.21.5 = 71, 1.21.6 = 80, 1.21.7/1.21.8 = 81,
# 1.21.9/1.21.10 = 88.0 (read off each server jar's version.json: pack_version.data_major/data_minor).
# A newer Minecraft than the table knows gets the newest number it has, which that Minecraft reads as
# "made for an older version" and still loads.
PACK_FORMATS = (
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


# Entity NBT renamed FallDistance (float) to fall_distance (double) in 1.21.5. From that release the
# entity class writes and reads fall_distance; only the datafixer (EntityFallDistanceFloatToDoubleFix)
# still names FallDistance, to convert saved entities. The type changed with the name (putDouble), and
# selector nbt matching compares tag types, so nbt={FallDistance:0f} and nbt={fall_distance:0f} both
# compile, load and match nothing on 1.21.5 and later; nbt={fall_distance:0d} is the form that matches.
FALL_DISTANCE_SNAKE_SINCE = (1, 21, 5)


def entity_nbt_renames():
	"""Entity NBT fields the target names differently, {old: (new, numeric suffix of its type)}."""
	return {'FallDistance': ('fall_distance', 'd')} if version() >= FALL_DISTANCE_SNAKE_SINCE else {}
