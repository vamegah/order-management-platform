import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';

/** expiresAt is the token expiry as Unix epoch milliseconds, supplied by the authentication SDK. */
export interface Identity { accessToken: string; subject: string; expiresAt: number }
export interface EventEnvelope { eventType: string; occurredAt: string; data: Record<string, unknown> }
export type State = 'stopped' | 'connecting' | 'reconciling' | 'live' | 'retrying' | 'failed';
export type ErrorCode = 'CREDENTIALS' | 'CONNECTION' | 'BROKER_REJECTED' | 'RECOVERY' | 'INVALID_EVENT' | 'BUFFER_FULL' | 'CALLBACK' | 'RETRIES_EXHAUSTED';
export interface Options {
  /** Native STOMP endpoint: wss://notification.example/ws/websocket. */
  url: string;
  getIdentity: (signal: AbortSignal) => Promise<Identity>;
  /** Fetch canonical API state. Honor cancellation before committing state to the caller's store. */
  resync: (destinations: readonly string[], context: { subject: string; signal: AbortSignal }) => Promise<void>;
  onIdentityChange?: (subject: string) => void;
  onState?: (state: State) => void;
  onError?: (code: ErrorCode) => void;
  allowInsecureLocalhost?: boolean;
  minRetryDelayMs?: number;
  maxRetryDelayMs?: number;
  maxAttempts?: number;
  timeoutMs?: number;
  resyncIntervalMs?: number;
  minResyncIntervalMs?: number;
  maxBufferedMessages?: number;
  maxSubscriptions?: number;
}

type Listener = (event: EventEnvelope) => void;
type Desired = { destination: string; listener: Listener };
type Limits = Required<Pick<Options, 'minRetryDelayMs' | 'maxRetryDelayMs' | 'maxAttempts' | 'timeoutMs' |
  'resyncIntervalMs' | 'minResyncIntervalMs' | 'maxBufferedMessages' | 'maxSubscriptions'>>;

/** Live invalidation hints plus canonical reads; no durable event replay or projection guarantee. */
export class NotificationClient {
  private readonly options: Options & Limits;
  private desired = new Map<number, Desired>();
  private active = new Map<string, StompSubscription>();
  private pending = new Map<string, EventEnvelope>();
  private client?: Client;
  private controller?: AbortController;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private recoveryTimer?: ReturnType<typeof setTimeout>;
  private credentialTimer?: ReturnType<typeof setTimeout>;
  private periodicTimer?: ReturnType<typeof setInterval>;
  private wanted = false;
  private attempt = 0;
  private generation = 0;
  private nextId = 0;
  private recovering = false;
  private dirty = false;
  private lastRecovery = 0;
  private subject?: string;
  private currentState: State = 'stopped';

  constructor(options: Options) {
    const url = new URL(options.url);
    const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname) || url.hostname.endsWith('.localhost');
    if (url.username || url.password || url.search || url.hash ||
      !(url.protocol === 'wss:' || (url.protocol === 'ws:' && local && options.allowInsecureLocalhost))) {
      throw new Error('Use a credential-free wss URL; insecure localhost requires explicit opt-in');
    }
    this.options = { minRetryDelayMs: 1000, maxRetryDelayMs: 60000, maxAttempts: 12, timeoutMs: 15000,
      resyncIntervalMs: 30000, minResyncIntervalMs: 100, maxBufferedMessages: 128, maxSubscriptions: 100, ...options };
    for (const key of ['minRetryDelayMs', 'maxRetryDelayMs', 'maxAttempts', 'timeoutMs', 'resyncIntervalMs',
      'minResyncIntervalMs', 'maxBufferedMessages', 'maxSubscriptions'] as const) {
      if (!Number.isSafeInteger(this.options[key]) || this.options[key] < 1 || this.options[key] > 2147483647) throw new Error('Limits must be positive safe integers');
    }
    if (typeof options.getIdentity !== 'function' || typeof options.resync !== 'function') throw new Error('Identity and reconciliation callbacks are required');
    if (this.options.maxRetryDelayMs < this.options.minRetryDelayMs) throw new Error('Invalid retry limits');
  }

  get state(): State { return this.currentState; }

  connect(): void {
    if (this.wanted) return;
    this.wanted = true;
    this.attempt = 0;
    void this.open();
  }

  async disconnect(): Promise<void> {
    this.wanted = false;
    this.clearConnection();
    const client = this.client;
    this.client = undefined;
    this.setState('stopped');
    if (client) { client.connectHeaders = {}; await client.deactivate({ force: true }); }
  }

  /** One wire subscription per destination; listener handles are independent and idempotent. */
  subscribe(destination: string, listener: Listener): () => void {
    if (!/^\/topic\/(orders|payments)\/[0-9a-f-]{36}$/.test(destination) &&
      !/^\/topic\/inventory\/[A-Za-z0-9._-]{1,100}$/.test(destination) &&
      destination !== '/user/queue/notifications') throw new Error('Unsupported notification destination');
    if (this.desired.size >= this.options.maxSubscriptions) throw new Error('Subscription limit exceeded');
    const id = ++this.nextId;
    this.desired.set(id, { destination, listener });
    if (this.client?.connected && !this.active.has(destination)) {
      this.subscribeOne(destination, this.generation);
      this.queueRecovery();
    }
    return () => {
      if (!this.desired.delete(id)) return;
      if (![...this.desired.values()].some(entry => entry.destination === destination)) {
        try { this.active.get(destination)?.unsubscribe(); } catch { this.report('CONNECTION'); }
        this.active.delete(destination);
        this.pending.delete(destination);
      }
    };
  }

  private report(code: ErrorCode): void {
    // Broker frames, exception text, headers and credentials never reach diagnostics.
    try { this.options.onError?.(code); } catch { /* diagnostics must not interrupt cleanup */ }
  }
  private callback(operation: () => void): void {
    try { operation(); } catch { this.report('CALLBACK'); }
  }
  private setState(state: State): void {
    if (this.currentState === state) return;
    this.currentState = state;
    this.callback(() => this.options.onState?.(state));
  }
  private isCurrent(generation: number): boolean { return this.wanted && generation === this.generation; }

  private async bounded<T>(operation: Promise<T>, signal: AbortSignal): Promise<T> {
    if (signal.aborted) throw new Error('Cancelled');
    let timeout: ReturnType<typeof setTimeout>;
    let abort: () => void;
    const cancellation = new Promise<never>((_, reject) => {
      abort = () => reject(new Error('Cancelled'));
      signal.addEventListener('abort', abort, { once: true });
      timeout = setTimeout(() => reject(new Error('Timeout')), this.options.timeoutMs);
    });
    try { return await Promise.race([operation, cancellation]); }
    finally { clearTimeout(timeout!); signal.removeEventListener('abort', abort!); }
  }

  private async open(): Promise<void> {
    if (!this.wanted) return;
    if (++this.attempt > this.options.maxAttempts) { this.fail('RETRIES_EXHAUSTED'); return; }
    const generation = ++this.generation;
    const controller = new AbortController();
    this.controller = controller;
    this.setState('connecting');
    let identity: Identity;
    try {
      identity = await this.bounded(this.options.getIdentity(controller.signal), controller.signal);
      if (!identity.accessToken?.trim() || identity.accessToken.length > 16384 || /[\r\n\0]/.test(identity.accessToken) || !identity.subject?.trim() || !Number.isFinite(identity.expiresAt) || identity.expiresAt <= Date.now()) throw new Error('Missing identity');
    } catch { if (this.isCurrent(generation)) this.retry('CREDENTIALS'); return; }
    if (!this.isCurrent(generation)) return;
    if (this.subject !== undefined && this.subject !== identity.subject) {
      this.desired.clear();
      this.callback(() => this.options.onIdentityChange?.(identity.subject));
    }
    this.subject = identity.subject;
    const expiresAt = identity.expiresAt;
    const client = new Client({ brokerURL: this.options.url,
      connectHeaders: { Authorization: `Bearer ${identity.accessToken}` },
      reconnectDelay: 0, connectionTimeout: this.options.timeoutMs,
      heartbeatIncoming: 10000, heartbeatOutgoing: 10000,
      logRawCommunication: false, debug: () => {},
      onConnect: () => {
        client.connectHeaders = {};
        if (!this.isCurrent(generation)) return;
        const remaining = expiresAt - Date.now();
        if (remaining <= 0) { this.retry('CREDENTIALS'); return; }
        const refreshAfter = Math.min(2147483647, remaining - Math.min(30000, remaining / 2));
        this.credentialTimer = setTimeout(() => { if (this.isCurrent(generation)) this.retry('CREDENTIALS', false); }, refreshAfter);
        this.setState('reconciling');
        for (const destination of new Set([...this.desired.values()].map(entry => entry.destination))) {
          if (!this.isCurrent(generation)) break;
          this.subscribeOne(destination, generation);
        }
        this.queueRecovery();
        // The platform's simple broker has no receipts/replay cursor; periodic reads repair gaps.
        this.periodicTimer = setInterval(() => this.queueRecovery(), this.options.resyncIntervalMs);
      },
      onWebSocketClose: () => { if (this.isCurrent(generation)) this.retry('CONNECTION'); },
      onStompError: () => { if (this.isCurrent(generation)) this.fail('BROKER_REJECTED'); },
    });
    this.client = client;
    client.activate();
  }

  private subscribeOne(destination: string, generation: number): void {
    try {
      const subscription = this.client!.subscribe(destination, message => this.receive(destination, message, generation));
      this.active.set(destination, subscription);
    } catch { if (this.isCurrent(generation)) this.retry('CONNECTION'); }
  }

  private receive(destination: string, message: IMessage, generation: number): void {
    if (!this.isCurrent(generation) || !this.active.has(destination)) return;
    let event: EventEnvelope;
    try {
      if (message.body.length > 65536 || new TextEncoder().encode(message.body).byteLength > 65536) throw new Error('Oversized message');
      const value: unknown = JSON.parse(message.body);
      if (!value || typeof value !== 'object') throw new Error('Invalid envelope');
      const record = value as Record<string, unknown>;
      if (typeof record.eventType !== 'string' || !record.eventType ||
        typeof record.occurredAt !== 'string' || !Number.isFinite(Date.parse(record.occurredAt)) ||
        !record.data || typeof record.data !== 'object' || Array.isArray(record.data)) throw new Error('Invalid envelope');
      event = value as EventEnvelope;
    } catch { this.retry('INVALID_EVENT'); return; }
    if (!this.pending.has(destination) && this.pending.size >= this.options.maxBufferedMessages) {
      this.retry('BUFFER_FULL'); return;
    }
    this.pending.set(destination, event);
    this.queueRecovery();
  }

  private queueRecovery(): void {
    if (!this.wanted || !this.client?.connected) return;
    this.dirty = true;
    if (this.recovering || this.recoveryTimer) return;
    const generation = this.generation;
    const delay = Math.max(0, this.lastRecovery + this.options.minResyncIntervalMs - Date.now());
    this.recoveryTimer = setTimeout(() => {
      this.recoveryTimer = undefined;
      if (this.isCurrent(generation)) void this.recover(generation, this.controller!.signal);
    }, delay);
  }

  private async recover(generation: number, signal: AbortSignal): Promise<void> {
    this.recovering = true;
    this.dirty = false;
    this.lastRecovery = Date.now();
    const events = this.pending;
    this.pending = new Map();
    try {
      const destinations = [...new Set([...this.desired.values()].map(entry => entry.destination))];
      await this.bounded(this.options.resync(destinations, { subject: this.subject!, signal }), signal);
      if (!this.isCurrent(generation)) return;
      this.attempt = 0;
      this.setState('live');
      for (const [id, desired] of [...this.desired]) {
        const event = events.get(desired.destination);
        if (event && this.desired.has(id)) this.callback(() => desired.listener(event));
      }
    } catch { if (this.isCurrent(generation)) this.retry('RECOVERY'); }
    finally {
      if (this.isCurrent(generation)) {
        this.recovering = false;
        if (this.dirty) this.queueRecovery();
      }
    }
  }

  private clearConnection(): void {
    this.generation++;
    this.controller?.abort();
    clearTimeout(this.retryTimer); clearTimeout(this.recoveryTimer); clearInterval(this.periodicTimer); clearTimeout(this.credentialTimer);
    this.recoveryTimer = undefined;
    this.active.clear(); this.pending.clear();
    this.recovering = false; this.dirty = false;
  }
  private retry(code: ErrorCode, report = true): void {
    if (!this.wanted) return;
    if (report) this.report(code);
    this.clearConnection();
    const client = this.client;
    this.client = undefined;
    if (client) { client.connectHeaders = {}; void client.deactivate({ force: true }).catch(() => {}); }
    this.setState('retrying');
    const base = Math.min(this.options.maxRetryDelayMs, this.options.minRetryDelayMs * 2 ** Math.min(Math.max(0, this.attempt - 1), 30));
    const delay = Math.min(this.options.maxRetryDelayMs, Math.floor(base * (0.5 + Math.random())));
    this.retryTimer = setTimeout(() => { void this.open(); }, delay);
  }
  private fail(code: ErrorCode): void {
    this.report(code);
    void this.disconnect().then(() => { if (!this.wanted) this.setState('failed'); });
  }
}
