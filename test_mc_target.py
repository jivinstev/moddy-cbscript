#!/usr/bin/env python3
"""Tests for mc_target.py and the pack.mcmeta it drives.  Run: python3 test_mc_target.py"""
import io
import json
import os
import unittest
import zipfile

import mc_target


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
						('1.21.10', 88)):
			os.environ['CBSCRIPT_MC'] = mc
			self.assertEqual(mc_target.pack_format(), fmt, mc)

	def test_a_newer_minecraft_gets_the_newest_number_known(self):
		os.environ['CBSCRIPT_MC'] = '1.21.99'
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


if __name__ == '__main__':
	unittest.main()
