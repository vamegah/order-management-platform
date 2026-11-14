"""Export independent producer documents for comparison; CI never overwrites v1 snapshots."""
import argparse
from pathlib import Path
from support import ROOT,export_live

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,default=ROOT/'clients/public-api/build/live')
options=parser.parse_args()
print('Exported live OpenAPI documents:',export_live(options.output))
