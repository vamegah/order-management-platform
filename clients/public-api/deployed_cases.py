"""Real consumer requests through Traefik; no service imports, mocked HTTP or direct DB edits."""
from datetime import datetime,timedelta,timezone
import json
import re
import time
import unittest
from uuid import uuid4

from api_client import ProductApi,CustomerApi,OrderApi,WebhookApi,PaymentApi,BankingApi,GraphqlApi
from runtime import Transport
from support import AuthTokens,metric_requests,origins

ALICE='00000000-0000-0000-0000-000000000101'
BOB='00000000-0000-0000-0000-000000000102'
PRODUCT='00000000-0000-0000-0000-000000000204'
SECOND_PRODUCT='00000000-0000-0000-0000-000000000202'


class PublicApiAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.auth=AuthTokens()
        cls.locations=origins()
        admin=cls.auth.bearer('admin')
        cls.products=ProductApi(cls.locations['product-service'],admin)
        cls.customers=CustomerApi(cls.locations['customer-service'],admin)
        cls.orders=OrderApi(cls.locations['order-service'],admin)
        cls.webhooks=WebhookApi(cls.locations['notification-service'],admin)
        cls.payments=PaymentApi(cls.locations['payment-service'],admin)
        cls.banking=BankingApi(cls.locations['legacy-banking-adapter'],admin,timeout=60)
        cls.graphql=GraphqlApi(cls.locations['graphql'],admin)
        cls.owner=GraphqlApi(cls.locations['graphql'],cls.auth.bearer('alice'))
        cls.foreign=GraphqlApi(cls.locations['graphql'],cls.auth.bearer('bob'))
        cls.production=GraphqlApi(cls.locations['graphql-prod'],admin)

    def graph(self,client,name,variables=None):
        result=client.execute(name,variables=variables or {})
        codes=[error.get('extensions',{}).get('code','unclassified') for error in result.data.get('errors',[])]
        self.assertEqual([],codes,'The compiled consumer operation must succeed')
        self.assertIn('no-store',result.headers.get('cache-control',''))
        return result.data['data']

    def code(self,result,expected):
        self.assertIsInstance(result.data,dict)
        self.assertIn(expected,[error.get('extensions',{}).get('code') for error in result.data.get('errors',[])])
        self.assertFalse(re.search(r'Bearer |jdbc:|com\.example|stacktrace|password=',json.dumps(result.data)))

    def new_customer(self):
        body={'email':'public-api-'+uuid4().hex+'@example.invalid','firstName':'Contract','lastName':'Consumer'}
        result=self.customers.customer_create(body=body,expected_status=201).data
        self.addCleanup(self.customers.customer_deactivate,path_params={'id':result['id']},expected_status=204)
        return result

    def new_order(self,customer=ALICE,product=PRODUCT,owner=False):
        body={'customerId':customer,'shippingAddress':{'line1':'1 Contract Way','city':'Oakland','postalCode':'94607','country':'US'},
              'lines':[{'productId':product,'quantity':1}],'notes':'public-api-'+uuid4().hex}
        arguments={'body':body,'expected_status':201}
        if owner: arguments['token']=self.auth.token('alice')
        result=self.orders.order_create(**arguments).data
        self.assertTrue(result['id'])
        self.assertTrue(result['lines'][0]['reservationId'])
        self.addCleanup(self.orders.order_cancel,path_params={'id':result['id']},body={'reason':'Public API contract cleanup'},expected_status=200)
        return result

    def product_input(self):
        category=self.products.category_list(expected_status=200).data[0]['id']
        return {'sku':'CONTRACT-'+uuid4().hex[:16].upper(),'name':'Contract product','categoryId':category,
                'price':{'amount':'12.50','currency':'USD'},'images':[{'url':'https://example.invalid/image.png','altText':'Contract'}]}

    def test_01_rest_authentication_for_every_published_service(self):
        cases=[(self.products,'Product_list',{}),(self.customers,'Customer_list',{}),(self.orders,'Order_list',{}),
               (self.webhooks,'WebhookSubscription_list',{}),
               (self.payments,'Payment_get',{'path_params':{'id':str(uuid4())}}),
               (self.banking,'BankingAdapter_balance',{'path_params':{'accountNumber':'ACC-1'}})]
        for client,operation,arguments in cases:
            for token in (None,'invalid-public-api-token'):
                with self.subTest(operation=operation,invalid=token is not None):
                    client.call(operation,token=token,expected_status=401,**arguments)

    def test_02_rest_product_pagination_is_stable(self):
        first=self.products.product_list(query={'page':0,'size':1,'sort':'name,asc'},expected_status=200).data
        second=self.products.product_list(query={'page':1,'size':1,'sort':'name,asc'},expected_status=200).data
        self.assertEqual(1,first['size'])
        self.assertTrue(first['first'])
        self.assertNotEqual(first['content'][0]['id'],second['content'][0]['id'])
        self.assertGreaterEqual(first['totalElements'],2)

    def test_03_rest_pagination_errors_match_the_contract(self):
        for query in ({'page':-1},{'size':0}):
            result=self.products.product_list(query=query,validate_request=False,expected_status=400)
            self.assertEqual(400,result.data['status'])

    def test_04_rest_batch_reads_preserve_order_and_omit_missing_duplicates(self):
        missing=str(uuid4())
        products=self.products.product_batch(query={'ids':[PRODUCT,missing,SECOND_PRODUCT,PRODUCT]},expected_status=200).data
        self.assertEqual([PRODUCT,SECOND_PRODUCT],[product['id'] for product in products])
        customers=self.customers.customer_batch(query={'ids':[ALICE,missing,BOB,ALICE]},expected_status=200).data
        self.assertEqual([ALICE,BOB],[customer['id'] for customer in customers])
        self.products.product_batch(query={'ids':[PRODUCT]*101},validate_request=False,expected_status=400)

    def test_05_rest_customer_reads_and_batches_enforce_verified_ownership(self):
        alice=self.auth.token('alice')
        self.customers.customer_get_by_id(path_params={'id':ALICE},token=alice,expected_status=200)
        self.customers.customer_get_by_id(path_params={'id':BOB},token=alice,expected_status=403)
        self.customers.customer_batch(query={'ids':[ALICE]},token=alice,expected_status=200)
        self.customers.customer_batch(query={'ids':[ALICE,BOB]},token=alice,expected_status=403)
        self.customers.address_list(path_params={'customerId':BOB},token=alice,expected_status=403)

    def test_06_rest_product_writes_require_administrator(self):
        body=self.product_input()
        body['price']['amount']=12.5
        self.products.product_create(body=body,token=self.auth.token('alice'),expected_status=403)

    def test_07_rest_checkout_references_and_foreign_order_access(self):
        order=self.new_order(owner=True)
        self.orders.order_get_by_id(path_params={'id':order['id']},token=self.auth.token('alice'),expected_status=200)
        self.orders.order_get_by_id(path_params={'id':order['id']},token=self.auth.token('bob'),expected_status=403)
        self.orders.order_cancel(path_params={'id':order['id']},body={'reason':'Owner cancellation'},token=self.auth.token('alice'),expected_status=200)

    def test_08_rest_order_listing_requires_an_owner_filter_or_administrator(self):
        order=self.new_order(owner=True)
        other=self.new_customer()
        self.new_order(customer=other['id'])
        alice=self.auth.token('alice')
        page=self.orders.order_list(query={'customerId':ALICE,'size':100},token=alice,expected_status=200).data
        self.assertIn(order['id'],[item['id'] for item in page['content']])
        self.assertTrue(all(item['customerId']==ALICE for item in page['content']))
        self.orders.order_list(token=alice,expected_status=403)
        self.orders.order_list(query={'customerId':other['id']},token=alice,expected_status=403)

    def test_09_webhook_crud_and_secret_visibility_are_owner_scoped(self):
        alice,bob=self.auth.token('alice'),self.auth.token('bob')
        body={'name':'public-api-'+uuid4().hex,'targetUrl':'https://example.invalid/contract',
              'eventTypes':['public-api.contract.never']}
        created=self.webhooks.webhook_subscription_create(body=body,token=alice,expected_status=201).data
        identity={'id':created['id']}
        self.addCleanup(self.webhooks.webhook_subscription_delete,path_params=identity,token=alice,expected_status=204)
        secret=created['secret']
        self.assertGreaterEqual(len(secret),32)
        read=self.webhooks.webhook_subscription_get(path_params=identity,token=alice,expected_status=200).data
        self.assertFalse('secret' in read,'Read responses must omit the secret')
        self.webhooks.webhook_subscription_get(path_params=identity,token=bob,expected_status=404)
        self.webhooks.webhook_subscription_update(path_params=identity,body={'name':'updated'},token=alice,expected_status=200)
        rotated=self.webhooks.webhook_subscription_rotate_secret(path_params=identity,token=alice,expected_status=200).data['secret']
        self.assertTrue(secret!=rotated,'Rotation must replace the secret')
        subscriptions=self.webhooks.webhook_subscription_list(token=alice,expected_status=200).data
        self.assertTrue(all('secret' not in subscription for subscription in subscriptions))

    def test_10_webhook_errors_and_replay_alias_preserve_admin_boundary(self):
        self.webhooks.webhook_subscription_create(body={'name':'','targetUrl':'file:///tmp/no','eventTypes':[]},
                token=self.auth.token('alice'),validate_request=False,expected_status=400)
        self.webhooks.dlq_list(token=self.auth.token('alice'),expected_status=403)
        self.webhooks.dlq_list(expected_status=200)
        identity={'deliveryId':str(uuid4())}
        self.webhooks.webhook_delivery_replay(path_params=identity,token=self.auth.token('alice'),expected_status=403)
        self.webhooks.webhook_delivery_replay(path_params=identity,expected_status=404)

    def test_11_payment_owner_refund_and_idempotent_retries(self):
        alice=self.auth.token('alice')
        body={'orderId':str(uuid4()),'amountMinor':125,'currency':'USD','idempotencyKey':'charge-'+uuid4().hex}
        payment=self.payments.payment_charge(body=body,token=alice,expected_status=200).data
        self.assertEqual('confirmed',payment['result'])
        repeated=self.payments.payment_charge(body=body,token=alice,expected_status=200).data
        self.assertEqual(payment['paymentId'],repeated['paymentId'])
        identity={'id':payment['paymentId']}
        self.payments.payment_get(path_params=identity,token=self.auth.token('bob'),expected_status=403)
        refund_body={'amountMinor':125,'reason':'Contract cleanup','idempotencyKey':'refund-'+uuid4().hex}
        arguments={'path_params':{'paymentId':payment['paymentId']},'body':refund_body,'token':alice,'expected_status':200}
        refund=self.payments.refund_refund(**arguments).data
        self.assertEqual('COMPLETED',refund['status'])
        self.assertEqual(refund['id'],self.payments.refund_refund(**arguments).data['id'])
        self.assertEqual('REFUNDED',self.payments.payment_get(path_params=identity,token=alice,expected_status=200).data['status'])
        self.payments.payment_get(path_params={'id':str(uuid4())},expected_status=404)

    def test_12_signed_provider_callback_does_not_use_jwt_authentication(self):
        body={'id':'evt_contract_'+uuid4().hex,'type':'public-api.ignored','data':{'object':{'id':'noop_contract'}}}
        self.payments.stripe_webhook_receive(path_params={'providerName':'unknown'},body=body,token=None,expected_status=404)
        self.payments.stripe_webhook_receive(path_params={'providerName':'noop'},body=body,token=None,expected_status=401)
        arguments={'path_params':{'providerName':'noop'},'body':body,'headers':{'X-Noop-Signature':'noop-dev-secret'},'token':None,'expected_status':200}
        first=self.payments.stripe_webhook_receive(**arguments).data
        self.assertIn(first['status'],('ignored','accepted'))
        self.assertEqual('duplicate',self.payments.stripe_webhook_receive(**arguments).data['status'])

    def test_13_banking_transfer_retries_and_conflicts_match_the_contract(self):
        self.banking.banking_adapter_balance(path_params={'accountNumber':'ACC-1'},token=self.auth.token('alice'),expected_status=403)
        before=self.banking.banking_adapter_balance(path_params={'accountNumber':'ACC-1'},expected_status=200).data
        body={'fromAccount':'ACC-1','toAccount':'ACC-2','amount':1.25,'currency':'USD','reference':'contract-'+uuid4().hex}
        arguments={'body':body,'headers':{'Idempotency-Key':'bank-'+uuid4().hex},'expected_status':200}
        first=self.banking.banking_adapter_transfer(**arguments).data
        self.addCleanup(self.banking.banking_adapter_transfer,body={**body,'fromAccount':'ACC-2','toAccount':'ACC-1'},
                headers={'Idempotency-Key':'bank-cleanup-'+uuid4().hex},expected_status=200)
        repeated=self.banking.banking_adapter_transfer(**arguments).data
        self.assertEqual(first['transactionId'],repeated['transactionId'])
        self.assertAlmostEqual(float(before['balance'])-1.25,float(first['newBalance']),places=2)
        self.banking.banking_adapter_transfer(body={**body,'amount':2.5},headers=arguments['headers'],expected_status=409)

    def test_14_banking_validation_and_business_errors_are_typed(self):
        self.banking.banking_adapter_transfer(body={'fromAccount':'invalid','toAccount':'ACC-2','amount':0,'currency':'USD','reference':'contract'},
                validate_request=False,expected_status=400)
        missing=self.banking.banking_adapter_balance(path_params={'accountNumber':'ACC-999999'},expected_status=404).data
        self.assertEqual(404,missing['status'])
        self.assertTrue(missing['type'].startswith('urn:problem:'))

    def test_15_graphql_missing_and_invalid_credentials_are_rejected(self):
        for token in (None,'invalid-public-api-token'):
            self.graphql.execute('Catalog',token=token,expected_status=401)

    def test_16_graphql_catalog_and_pagination_match_the_compiled_schema(self):
        first=self.graph(self.owner,'Catalog',{'page':0,'size':1})
        second=self.graph(self.owner,'Catalog',{'page':1,'size':1})
        self.assertNotEqual(first['products']['content'][0]['id'],second['products']['content'][0]['id'])
        self.assertEqual(1,first['products']['size'])
        self.assertTrue(first['categories'])
        self.assertIsInstance(first['products']['content'][0]['price']['amount'],str)

    def test_17_graphql_owner_and_foreign_profile_rules(self):
        own=self.graph(self.owner,'Customer',{'id':ALICE})
        self.assertEqual(ALICE,own['customer']['id'])
        self.assertEqual(ALICE,self.graph(self.owner,'Me')['me']['id'])
        denied=self.owner.execute('Customer',variables={'id':BOB})
        self.code(denied,'FORBIDDEN')
        self.assertIsNone(denied.data['data']['customer'])

    def test_18_graphql_not_found_is_nullable_with_a_stable_error(self):
        missing=self.graphql.execute('Product',variables={'id':str(uuid4())})
        self.code(missing,'NOT_FOUND')
        self.assertIsNone(missing.data['data']['product'])

    def test_19_graphql_validation_errors_are_redacted_and_stable(self):
        result=self.graphql.execute('Catalog',variables={'page':-1,'size':1})
        self.code(result,'VALIDATION_FAILED')
        result=self.graphql.execute('Customers',variables={'size':0})
        self.code(result,'VALIDATION_FAILED')

    def test_20_graphql_product_mutations_reach_the_authenticated_rest_service(self):
        body=self.product_input()
        created=self.graph(self.graphql,'CreateProduct',{'input':body})['createProduct']
        self.addCleanup(self.products.product_deactivate,path_params={'id':created['id']},expected_status=204)
        update={key:value for key,value in body.items() if key!='sku'}
        update['name']='Updated contract product'
        updated=self.graph(self.graphql,'UpdateProduct',{'id':created['id'],'input':update})['updateProduct']
        self.assertEqual(update['name'],updated['name'])
        self.assertTrue(updated['images'][0]['id'])
        denied=self.owner.execute('CreateProduct',variables={'input':self.product_input()})
        self.code(denied,'FORBIDDEN')
        self.assertIsNone(denied.data['data'])
        self.assertEqual('INACTIVE',self.graph(self.graphql,'DeactivateProduct',{'id':created['id']})['deactivateProduct']['status'])

    def test_21_graphql_preferences_create_defaults_and_preserve_omitted_values(self):
        body={'email':'gql-'+uuid4().hex+'@example.invalid','firstName':'Contract','lastName':'Consumer','dateOfBirth':'1990-01-02'}
        customer=self.graph(self.graphql,'CreateCustomer',{'input':body})['createCustomer']
        self.addCleanup(self.customers.customer_deactivate,path_params={'id':customer['id']},expected_status=204)
        defaults=self.graph(self.graphql,'SetPreferences',{'customerId':customer['id'],'input':{}})['setCustomerPreferences']
        self.assertEqual('en-US',defaults['locale'])
        self.assertTrue(defaults['notificationEmail'])
        changed=self.graph(self.graphql,'SetPreferences',{'customerId':customer['id'],'input':{'currency':'EUR','notificationEmail':False}})['setCustomerPreferences']
        self.assertEqual('en-US',changed['locale'])
        self.assertEqual('EUR',changed['currency'])
        self.assertFalse(changed['notificationEmail'])
        preserved=self.graph(self.graphql,'SetPreferences',{'customerId':customer['id'],'input':{'marketingOptIn':True}})['setCustomerPreferences']
        self.assertEqual('EUR',preserved['currency'])
        self.assertFalse(preserved['notificationEmail'])
        self.assertTrue(preserved['marketingOptIn'])
        localized=self.graph(self.graphql,'SetPreferences',{'customerId':customer['id'],'input':{'locale':'fr-FR'}})['setCustomerPreferences']
        self.assertEqual('fr-FR',localized['locale'])
        self.assertEqual('EUR',localized['currency'])

    def test_22_graphql_nested_orders_are_scoped_to_the_verified_customer(self):
        order=self.new_order(owner=True)
        data=self.graph(self.owner,'CustomerOrders',{'id':ALICE,'limit':100})['customer']
        self.assertIn(order['id'],[item['id'] for item in data['orders']])
        self.assertTrue(all(item['customerId']==ALICE for item in data['orders']))
        denied=self.owner.execute('CustomerOrders',variables={'id':BOB})
        self.code(denied,'FORBIDDEN')

    def test_23_graphql_order_mutations_preserve_state_and_owner_isolation(self):
        body={'customerId':ALICE,'shippingAddress':{'line1':'2 GraphQL Way','city':'Oakland','postalCode':'94607','country':'US'},
              'lines':[{'productId':PRODUCT,'quantity':1}],'notes':'GraphQL contract'}
        order=self.graph(self.owner,'CreateOrder',{'input':body})['createOrder']
        self.addCleanup(self.orders.order_cancel,path_params={'id':order['id']},body={'reason':'Contract cleanup'},expected_status=200)
        denied=self.foreign.execute('Order',variables={'id':order['id']})
        self.code(denied,'FORBIDDEN')
        self.assertEqual('PLACED',self.graph(self.owner,'PlaceOrder',{'id':order['id']})['placeOrder']['status'])
        cancelled=self.graph(self.owner,'CancelOrder',{'id':order['id'],'reason':'Contract cancellation'})['cancelOrder']
        self.assertEqual('CANCELLED',cancelled['status'])
        canonical=self.graph(self.owner,'Order',{'id':order['id']})['order']
        self.assertIsInstance(canonical['statusHistory'],list)
        before=len(canonical['statusHistory'])
        self.graph(self.owner,'CancelOrder',{'id':order['id'],'reason':'Contract cancellation'})
        repeated=self.graph(self.owner,'Order',{'id':order['id']})['order']
        self.assertEqual(before,len(repeated['statusHistory']))

    def test_24_placed_date_filters_use_placement_not_creation_time(self):
        order=self.new_order(owner=True)
        lower=(datetime.now(timezone.utc)-timedelta(minutes=5)).isoformat()
        upper=(datetime.now(timezone.utc)+timedelta(minutes=5)).isoformat()
        filter_={'orderNumber':order['orderNumber'],'placedAfter':lower,'placedBefore':upper}
        self.assertEqual([],self.graph(self.graphql,'Orders',{'filter':filter_})['orders']['content'])
        placed=self.graph(self.owner,'PlaceOrder',{'id':order['id']})['placeOrder']
        self.assertTrue(placed['placedAt'])
        found=self.graph(self.graphql,'Orders',{'filter':filter_})['orders']['content']
        self.assertEqual([order['id']],[item['id'] for item in found])
        rest=self.orders.order_list(query={**filter_,'page':0,'size':10},expected_status=200).data
        self.assertEqual([order['id']],[item['id'] for item in rest['content']])
        future={**filter_,'placedAfter':upper,'placedBefore':(datetime.now(timezone.utc)+timedelta(minutes=10)).isoformat()}
        self.assertEqual([],self.graph(self.graphql,'Orders',{'filter':future})['orders']['content'])
        self.code(self.graphql.execute('Orders',variables={'filter':{**filter_,'placedAfter':upper,'placedBefore':lower}}),'VALIDATION_FAILED')
        self.orders.order_list(query={**filter_,'placedAfter':upper,'placedBefore':lower},validate_request=False,expected_status=400)

    def test_25_deployed_dataloaders_batch_unique_ids_once_per_service(self):
        customer=self.new_customer()
        first=self.new_order(customer=ALICE,product=PRODUCT)
        second=self.new_order(customer=customer['id'],product=SECOND_PRODUCT)
        admin=self.auth.bearer('admin')
        before={service:metric_requests(self.locations[service],admin,uri) for service,uri in
                [('product-service','/api/v1/products/batch'),('customer-service','/api/v1/customers/batch')]}
        data=self.graph(self.graphql,'AggregateOrders',{'first':first['id'],'second':second['id']})
        self.assertEqual(first['id'],data['repeated']['id'])
        self.assertEqual({ALICE,customer['id']},{data['first']['customer']['id'],data['second']['customer']['id']})
        for service,uri in [('product-service','/api/v1/products/batch'),('customer-service','/api/v1/customers/batch')]:
            difference=0
            for _ in range(20):
                difference=metric_requests(self.locations[service],admin,uri)-before[service]
                if difference: break
                time.sleep(0.1)
            self.assertEqual(1,difference,'Each downstream service must receive one deduplicated batch request')

    def test_26_development_and_production_policy_are_executed_on_real_gateways(self):
        query='{__schema{queryType{name}}}'
        development=self.graphql.execute_raw(query,expected_status=200)
        self.assertEqual('Query',development.data['data']['__schema']['queryType']['name'])
        production=self.production.execute_raw(query)
        self.assertTrue(production.data.get('errors'))
        self.assertIn('no-store',production.headers.get('cache-control',''))
        self.graph(self.production,'Catalog',{'size':1})
        dev_ui=Transport(self.locations['graphql'],self.auth.bearer('admin')).send('GET','/graphiql',headers={'Accept':'text/html'})
        self.assertIn(dev_ui.status,(200,302,307))
        prod_ui=Transport(self.locations['graphql-prod'],self.auth.bearer('admin')).send('GET','/graphiql',headers={'Accept':'text/html'})
        self.assertEqual(404,prod_ui.status)

    def test_27_deployed_depth_and_complexity_limits_have_stable_codes(self):
        nested='kind'
        for _ in range(25): nested='ofType{'+nested+'}'
        self.code(self.graphql.execute_raw('{__type(name:"Order"){'+nested+'}}'),'QUERY_DEPTH_LIMIT')
        expensive='{'+''.join('p'+str(index)+':products(size:100){content{id name}}' for index in range(12))+'}'
        self.code(self.graphql.execute_raw(expensive),'QUERY_COMPLEXITY_LIMIT')

    def test_28_deployed_body_limit_rejects_before_graphql_execution(self):
        response=self.graphql.execute_raw('{__typename}',extensions={'padding':'x'*70000},expected_status=413)
        self.code(response,'REQUEST_TOO_LARGE')

    def test_29_hash_only_persisted_queries_are_rejected_by_the_documented_policy(self):
        result=self.production.execute_raw(extensions={'persistedQuery':{'version':1,'sha256Hash':'0'*64}},expected_status=400)
        self.assertIn('no-store',result.headers.get('cache-control',''))

    def test_zz_rate_limit_is_identity_scoped_and_returns_retry_after(self):
        limited=None
        for _ in range(61):
            result=self.foreign.execute_raw('{__typename}')
            if result.status==429:
                limited=result
                break
            self.assertEqual(200,result.status)
        self.assertIsNotNone(limited,'The acceptance gateway uses a 60-request identity window')
        self.code(limited,'RATE_LIMITED')
        self.assertTrue(limited.headers.get('retry-after'))
        self.owner.execute_raw('{__typename}',expected_status=200)
