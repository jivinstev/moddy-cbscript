#!/usr/bin/env python3
"""Tests for mc_target.py and the pack.mcmeta it drives.  Run: python3 test_mc_target.py"""
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile

import mc_target
import nbt_case

HERE = os.path.dirname(os.path.abspath(__file__))


class _Env(unittest.TestCase):
	def setUp(self):
		self._was = os.environ.pop('CBSCRIPT_MC', None)

	def tearDown(self):
		os.environ.pop('CBSCRIPT_MC', None)
		if self._was is not None:
			os.environ['CBSCRIPT_MC'] = self._was


class TestPackFormat(_Env):

	def test_unset_is_1_21_1_as_it_always_was(self):
		self.assertEqual(mc_target.version(), (1, 21, 1))
		self.assertEqual(mc_target.pack_format(), 48)

	def test_each_target_gets_its_own_number(self):
		for mc, fmt in (('1.21.1', 48), ('1.21', 48), ('1.21.2', 57), ('1.21.3', 57), ('1.21.4', 61),
						('1.21.5', 71), ('1.21.6', 80), ('1.21.7', 81), ('1.21.8', 81), ('1.21.9', 88),
						('1.21.10', 88), ('1.21.11', 94), ('26.1', 101), ('26.1.1', 101), ('26.1.2', 101),
						('26.2', 107)):
			os.environ['CBSCRIPT_MC'] = mc
			self.assertEqual(mc_target.pack_format(), fmt, mc)

	def test_a_newer_minecraft_gets_the_newest_number_known(self):
		os.environ['CBSCRIPT_MC'] = '26.99'
		self.assertEqual(mc_target.pack_format(), mc_target._row()[0])
		self.assertEqual(mc_target._row(), mc_target.PACK_FORMATS[0][1])

	def test_up_to_1_21_8_the_meta_is_exactly_what_it_was(self):
		# 1.21.8 (81) and below: no new keys, same key order, so a pack compiled for them is byte-for-byte
		# what it was before 1.21.9's rows existed.
		for mc, fmt in (('1.21.1', 48), ('1.21.8', 81)):
			os.environ['CBSCRIPT_MC'] = mc
			self.assertEqual(json.dumps(mc_target.pack_meta('d')), json.dumps({'pack_format': fmt, 'description': 'd'}), mc)

	def test_from_1_21_9_the_meta_declares_min_and_max_format(self):
		for mc in ('1.21.9', '1.21.10'):
			os.environ['CBSCRIPT_MC'] = mc
			self.assertEqual(mc_target.pack_meta('d'),
							 {'pack_format': 88, 'description': 'd', 'min_format': [88, 0], 'max_format': [88, 0]}, mc)

	def test_1_21_11_declares_94_1(self):
		os.environ['CBSCRIPT_MC'] = '1.21.11'
		self.assertEqual(mc_target.pack_meta('d'),
						 {'pack_format': 94, 'description': 'd', 'min_format': [94, 1], 'max_format': [94, 1]})

	def test_26_1_2_declares_101_1(self):
		# 26.1, 26.1.1 and 26.1.2 server jars all say data_major 101, data_minor 1 in version.json.
		for mc in ('26.1', '26.1.2'):
			os.environ['CBSCRIPT_MC'] = mc
			self.assertEqual(mc_target.pack_meta('d'),
							 {'pack_format': 101, 'description': 'd', 'min_format': [101, 1], 'max_format': [101, 1]}, mc)

	def test_26_2_declares_107_1(self):
		# The 26.2 server jar (sha1 823e2250d24b3ddac457a60c92a6a941943fcd6a) says data_major 107, data_minor 1.
		os.environ['CBSCRIPT_MC'] = '26.2'
		self.assertEqual(mc_target.pack_meta('d'),
						 {'pack_format': 107, 'description': 'd', 'min_format': [107, 1], 'max_format': [107, 1]})

	def test_the_chain_rule_is_spelled_the_targets_way(self):
		for mc, rule in (('1.21.1', 'maxCommandChainLength'), ('1.21.8', 'maxCommandChainLength'),
						 ('1.21.10', 'maxCommandChainLength'), ('1.21.11', 'max_command_sequence_length'),
						 ('26.1.2', 'max_command_sequence_length'), ('26.2', 'max_command_sequence_length')):
			os.environ['CBSCRIPT_MC'] = mc
			self.assertEqual(mc_target.max_chain_rule(), rule, mc)

	def test_garbage_is_the_default_not_a_crash(self):
		os.environ['CBSCRIPT_MC'] = 'latest'
		self.assertEqual(mc_target.pack_format(), 48)

	def test_the_written_mcmeta_carries_the_targets_number(self):
		# The real writer, not the helper: this is the line that said 48 for every target.
		import mcworld
		os.environ['CBSCRIPT_MC'] = '1.21.8'
		w = mcworld.mcworld.__new__(mcworld.mcworld)
		buf = io.BytesIO()
		w.zip = zipfile.ZipFile(buf, 'w')
		w.write_mcmeta('t')
		w.zip.close()
		meta = json.loads(zipfile.ZipFile(buf).read('pack.mcmeta'))
		self.assertEqual(meta['pack']['pack_format'], 81)
		self.assertNotIn('min_format', meta['pack'])

	def test_the_written_mcmeta_for_1_21_10_has_min_and_max_format(self):
		import mcworld
		os.environ['CBSCRIPT_MC'] = '1.21.10'
		w = mcworld.mcworld.__new__(mcworld.mcworld)
		buf = io.BytesIO()
		w.zip = zipfile.ZipFile(buf, 'w')
		w.write_mcmeta('t')
		w.zip.close()
		meta = json.loads(zipfile.ZipFile(buf).read('pack.mcmeta'))
		self.assertEqual((meta['pack']['pack_format'], meta['pack']['min_format'], meta['pack']['max_format']),
						 (88, [88, 0], [88, 0]))

	def test_the_written_mcmeta_for_26_1_2_declares_101_1(self):
		import mcworld
		os.environ['CBSCRIPT_MC'] = '26.1.2'
		w = mcworld.mcworld.__new__(mcworld.mcworld)
		buf = io.BytesIO()
		w.zip = zipfile.ZipFile(buf, 'w')
		w.write_mcmeta('t')
		w.zip.close()
		meta = json.loads(zipfile.ZipFile(buf).read('pack.mcmeta'))
		self.assertEqual((meta['pack']['pack_format'], meta['pack']['min_format'], meta['pack']['max_format']),
						 (101, [101, 1], [101, 1]))

	def test_the_written_mcmeta_for_26_2_declares_107_1(self):
		import mcworld
		os.environ['CBSCRIPT_MC'] = '26.2'
		w = mcworld.mcworld.__new__(mcworld.mcworld)
		buf = io.BytesIO()
		w.zip = zipfile.ZipFile(buf, 'w')
		w.write_mcmeta('t')
		w.zip.close()
		meta = json.loads(zipfile.ZipFile(buf).read('pack.mcmeta'))
		self.assertEqual((meta['pack']['pack_format'], meta['pack']['min_format'], meta['pack']['max_format']),
						 (107, [107, 1], [107, 1]))


class TestEntityNbtRenames(_Env):
	"""FallDistance (float) -> fall_distance (double): the entity's NBT since 1.21.5, one gate on every rung."""

	def test_renamed_from_1_21_5(self):
		R = {'FallDistance': ('fall_distance', 'd')}
		for mc, want in (('1.21.1', {}), ('1.21.4', {}), ('1.21.5', R), ('1.21.8', R), ('1.21.11', R),
						 ('26.1', R), ('26.1.2', R), ('26.2', R)):
			os.environ['CBSCRIPT_MC'] = mc
			self.assertEqual(mc_target.entity_nbt_renames(), want, mc)

	def test_fall_distance_is_a_snake_case_double_from_1_21_5(self):
		for mc in ('1.21.8', '26.1.2'):
			os.environ['CBSCRIPT_MC'] = mc
			nbt_case.reset_warnings()
			for written, want in (
					('@e[nbt={FallDistance:0f}]', '@e[nbt={fall_distance:0d}]'),
					('@e[nbt={falldistance:0f}]', '@e[nbt={fall_distance:0d}]'),
					('@e[nbt={fall_distance:0f}]', '@e[nbt={fall_distance:0d}]'),
					('@e[nbt={FallDistance:0}]', '@e[nbt={fall_distance:0d}]'),
					('@e[nbt={FallDistance:2.5F}]', '@e[nbt={fall_distance:2.5d}]'),
					('@e[nbt={fall_distance:0d}]', '@e[nbt={fall_distance:0d}]'),
					('@e[nbt={fall_distance:0.5}]', '@e[nbt={fall_distance:0.5}]'),
					('@e[nbt={onground:1b,FallDistance:0f}]', '@e[nbt={OnGround:1b,fall_distance:0d}]')):
				self.assertEqual(nbt_case.fix_nbt_case(written), want, (mc, written))

	def test_before_1_21_5_keeps_fall_distance_camel_case_float(self):
		for mc in ('1.21.1', '1.21.4'):
			os.environ['CBSCRIPT_MC'] = mc
			nbt_case.reset_warnings()
			self.assertEqual(nbt_case.fix_nbt_case('@e[nbt={falldistance:0f}]'), '@e[nbt={FallDistance:0f}]', mc)


PROBE = """dir "x"
desc "dialect probe"

reset
    tell @a 'Hello! [Click me](call go)'
    /execute as @e[nbt={FallDistance:0f}] run say still
    /execute as @e[nbt={onground:1b,falldistance:2f}] run say odd
    /team add red
    /team modify red color red
    /item replace entity @s hotbar.0 with minecraft:stone 1
end

function go()
    /say went
end
"""


def compile_with(mc):
	"""Compile PROBE with the REAL compiler at CBSCRIPT_MC=mc; return every function's text and pack.mcmeta."""
	with tempfile.TemporaryDirectory() as d:
		src = os.path.join(d, 'probe.cbscript')
		out = os.path.join(d, 'probe.zip')
		with open(src, 'w', encoding='utf-8') as f:
			f.write(PROBE)
		env = dict(os.environ, CBSCRIPT_MC=mc)
		r = subprocess.run([sys.executable, os.path.join(HERE, 'compile_once.py'), src, '--out', out],
			capture_output=True, text=True, encoding='utf-8', env=env, cwd=d)
		verdict = json.loads(r.stdout)
		assert verdict['ok'], verdict
		with zipfile.ZipFile(out) as z:
			text = '\n'.join(z.read(n).decode() for n in sorted(z.namelist()) if n.endswith('.mcfunction'))
			return text, json.loads(z.read('pack.mcmeta'))


class TestCompiledOutput(unittest.TestCase):

	def test_26_1_2(self):
		text, meta = compile_with('26.1.2')
		self.assertIn('gamerule max_command_sequence_length', text)
		self.assertNotIn('maxCommandChainLength', text)
		self.assertIn('"click_event":{"action":"run_command","command":', text)
		self.assertNotIn('clickEvent', text)
		self.assertIn('nbt={fall_distance:0d}', text)
		self.assertIn('nbt={OnGround:1b,fall_distance:2d}', text)
		self.assertNotIn('FallDistance', text)
		self.assertEqual((meta['pack']['pack_format'], meta['pack']['max_format']), (101, [101, 1]))

	def test_26_2(self):
		# 26.2 changed no spelling the compiler writes: the reports diff against 26.1.2 shows only the pack
		# format, and team modify's colour argument (minecraft:color -> minecraft:team_color) still takes `red`.
		text, meta = compile_with('26.2')
		self.assertIn('gamerule max_command_sequence_length', text)
		self.assertIn('"click_event":{"action":"run_command","command":', text)
		self.assertIn('nbt={fall_distance:0d}', text)
		self.assertNotIn('FallDistance', text)
		self.assertIn('team modify red color red', text)
		self.assertIn('item replace entity @s hotbar.0 with minecraft:stone 1', text)
		self.assertEqual((meta['pack']['pack_format'], meta['pack']['min_format'], meta['pack']['max_format']),
						 (107, [107, 1], [107, 1]))

	def test_1_21_11_is_what_its_rung_writes(self):
		text, meta = compile_with('1.21.11')
		self.assertIn('gamerule max_command_sequence_length', text)
		self.assertIn('"click_event":{"action":"run_command","command":', text)
		self.assertIn('nbt={fall_distance:0d}', text)
		self.assertNotIn('FallDistance', text)
		self.assertEqual(meta['pack']['pack_format'], 94)

	def test_1_21_8_and_1_21_10_write_fall_distance(self):
		for mc in ('1.21.8', '1.21.10'):
			text, _ = compile_with(mc)
			self.assertIn('nbt={fall_distance:0d}', text, mc)
			self.assertIn('nbt={OnGround:1b,fall_distance:2d}', text, mc)
			self.assertNotIn('FallDistance', text, mc)
			self.assertNotIn('falldistance', text, mc)

	def test_1_21_4_keeps_fall_distance(self):
		text, _ = compile_with('1.21.4')
		self.assertIn('nbt={FallDistance:0f}', text)
		self.assertIn('nbt={OnGround:1b,FallDistance:2f}', text)
		self.assertNotIn('fall_distance', text)


if __name__ == '__main__':
	unittest.main()
