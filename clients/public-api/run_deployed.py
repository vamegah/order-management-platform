"""Run required public API acceptance and write normal JUnit evidence without logging credentials."""
import argparse
import importlib.util
import os
from pathlib import Path
import sys
import time
import unittest
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[2]


class TrackingResult(unittest.TextTestResult):
    def __init__(self,*arguments,**options):
        super().__init__(*arguments,**options)
        self.executed=[]
        self.started={}
        self.elapsed={}

    def startTest(self,test):
        self.executed.append(test.id())
        self.started[test.id()]=time.monotonic()
        super().startTest(test)

    def stopTest(self,test):
        self.elapsed[test.id()]=time.monotonic()-self.started[test.id()]
        super().stopTest(test)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'clients/public-api/deployed-results.xml')
    parser.add_argument('--build',type=Path,default=ROOT/'clients/public-api/build')
    options=parser.parse_args()
    if os.environ.get('PUBLIC_API_E2E')!='1' or not os.environ.get('COMPOSE_PROJECT_NAME','').startswith('omp-public-api-'):
        raise RuntimeError('Explicit PUBLIC_API_E2E=1 and an omp-public-api-* Compose project are required')
    os.environ['NO_PROXY']=os.environ.get('NO_PROXY','')+',localhost,127.0.0.1,.localhost'
    sys.path.insert(0,str(options.build.resolve()))
    spec=importlib.util.spec_from_file_location('public_api_deployed',Path(__file__).with_name('deployed_cases.py'))
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    suite=unittest.defaultTestLoader.loadTestsFromModule(module)
    result=unittest.TextTestRunner(verbosity=2,resultclass=TrackingResult).run(suite)
    root=ET.Element('testsuite',name='public-api-deployed',tests=str(result.testsRun),
                    failures=str(len(result.failures)),errors=str(len(result.errors)),skipped=str(len(result.skipped)))
    for identifier in result.executed:
        case=ET.SubElement(root,'testcase',name=identifier,classname='PublicApiAcceptance',time=str(result.elapsed.get(identifier,0)))
        for kind,entries in (('failure',result.failures),('error',result.errors)):
            for test,trace in entries:
                if test.id()==identifier or test.id().startswith(identifier+' '): ET.SubElement(case,kind).text=trace
        for test,reason in result.skipped:
            if test.id()==identifier: ET.SubElement(case,'skipped').text=reason
    options.output.parent.mkdir(parents=True,exist_ok=True)
    ET.ElementTree(root).write(options.output,encoding='utf-8',xml_declaration=True)
    return 0 if result.wasSuccessful() and result.testsRun>=30 and not result.skipped else 1


if __name__=='__main__': raise SystemExit(main())
