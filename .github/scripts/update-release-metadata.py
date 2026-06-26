#!/usr/bin/env python3
import argparse
import datetime as dt

parser = argparse.ArgumentParser()
parser.add_argument('version')
parser.add_argument('--release', action='store_true')
args = parser.parse_args()
print(args.version)
print(dt.date.today().isoformat() if args.release else '')
