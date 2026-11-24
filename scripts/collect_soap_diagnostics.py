"""Redact mounted secrets before retaining disposable banking diagnostics."""
from pathlib import Path
import argparse, subprocess
parser=argparse.ArgumentParser();parser.add_argument('--project',required=True);args=parser.parse_args()
if not args.project.startswith('omp-soap-acceptance-'):raise ValueError('Unexpected project')
root=Path(__file__).resolve().parents[1]
command=['docker','compose','--project-name',args.project,'--env-file','.env.example','-f','docker-compose.yml','-f','docker-compose.e2e.yml','-f','docker-compose.soap-e2e.yml']
secrets=[p.read_text(encoding='utf-8').strip() for p in (root/'coverage/soap-fixtures/secrets').rglob('*') if p.is_file()]
out=root/'coverage/soap-acceptance';out.mkdir(parents=True,exist_ok=True)
for name,arguments in [('containers.txt',['ps','-a']),('services.log',['logs','--no-color','--tail','200','legacy-bank-service','legacy-banking-adapter'])]:
 result=subprocess.run([*command,*arguments],cwd=root,capture_output=True,check=False)
 text=result.stdout.decode(errors='replace')
 for value in secrets:
  if len(value)>=8:text=text.replace(value,'[REDACTED]')
 (out/name).write_text(text,encoding='utf-8')
