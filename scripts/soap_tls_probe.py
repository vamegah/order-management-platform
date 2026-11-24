"""Private disposable TLS probe. Request credentials enter only stdin, never argv or logs."""
from urllib.request import Request,build_opener,HTTPSHandler,ProxyHandler,HTTPRedirectHandler
from urllib.error import HTTPError
import argparse,base64,json,socket,ssl,sys
class NoRedirect(HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['soap','reject']);parser.add_argument('--hostname',default='legacy-bank-service');args=parser.parse_args()
context=ssl.create_default_context(cafile='/bank-ca.crt')
if args.mode=='soap':
 opener=build_opener(ProxyHandler({}),NoRedirect(),HTTPSHandler(context=context))
 request=Request('https://legacy-bank-service:8443/ws',data=sys.stdin.buffer.read(),headers={'Content-Type':'text/xml; charset=utf-8','SOAPAction':'"http://legacybank.example.com/ws/GetAccountBalance"'})
 try:response=opener.open(request,timeout=5)
 except HTTPError as error:response=error
 with response:print(json.dumps({'status':response.status,'body':base64.b64encode(response.read()).decode()}))
else:
 try:
  with socket.create_connection(('legacy-bank-service',8443),timeout=5) as connection:
   with context.wrap_socket(connection,server_hostname=args.hostname):pass
 except ssl.SSLCertVerificationError as error:print(json.dumps({'rejected':True,'verifyCode':error.verify_code}));sys.exit(0)
 print(json.dumps({'rejected':False}))
