import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { defineStore } from 'pinia';
import { useAuthStore } from '@/stores/auth';

type MessageHandler<T = unknown> = (payload: T, raw: IMessage) => void;

let client: Client | null = null;
let connectPromise: Promise<void> | null = null;
const subscriptionMap = new Map<string, StompSubscription>();

function wsUrl() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}/ws`;
}

function parseBody(body: string) {
  if (!body) return null;
  try {
    return JSON.parse(body) as unknown;
  } catch {
    return body;
  }
}

export const useWebSocketStore = defineStore('websocket', {
  state: () => ({
    connected: false,
    sessionId: '',
    subscriptions: {} as Record<string, string>,
    retryCount: 0,
    lastError: ''
  }),
  actions: {
    async connect() {
      if (client?.connected) {
        this.connected = true;
        return;
      }
      if (connectPromise) return connectPromise;

      const auth = useAuthStore();
      connectPromise = new Promise<void>((resolve, reject) => {
        const stompClient = new Client({
          brokerURL: wsUrl(),
          reconnectDelay: 5000,
          heartbeatIncoming: 10000,
          heartbeatOutgoing: 10000,
          beforeConnect: async () => {
            if (!auth.accessToken) {
              await auth.refreshSession();
            }
            stompClient.connectHeaders = auth.accessToken
              ? { Authorization: `Bearer ${auth.accessToken}` }
              : {};
          },
          onConnect: (frame) => {
            this.connected = true;
            this.sessionId = frame.headers.session || crypto.randomUUID();
            this.lastError = '';
            this.retryCount = 0;
            resolve();
          },
          onStompError: async (frame) => {
            this.lastError = frame.headers.message || frame.body || 'WebSocket 订阅异常';
            if (this.lastError.includes('401') || this.lastError.includes('Unauthorized')) {
              try {
                await auth.refreshSession();
                stompClient.deactivate().then(() => stompClient.activate());
              } catch {
                auth.clearSession();
              }
            }
            reject(new Error(this.lastError));
          },
          onWebSocketClose: () => {
            this.connected = false;
            this.retryCount += 1;
          },
          onWebSocketError: () => {
            this.lastError = 'WebSocket 连接失败';
            reject(new Error(this.lastError));
          }
        });

        client = stompClient;
        stompClient.activate();
      }).finally(() => {
        connectPromise = null;
      });

      return connectPromise;
    },
    async subscribe<T = unknown>(topic: string, handler: MessageHandler<T>) {
      await this.connect();
      if (!client?.connected) throw new Error('WebSocket 尚未连接');

      this.unsubscribe(topic);
      const subscription = client.subscribe(topic, (message) => {
        handler(parseBody(message.body) as T, message);
      });
      subscriptionMap.set(topic, subscription);
      this.subscriptions[topic] = subscription.id;
      return subscription.id;
    },
    unsubscribe(topic: string) {
      const subscription = subscriptionMap.get(topic);
      if (subscription) {
        subscription.unsubscribe();
        subscriptionMap.delete(topic);
      }
      delete this.subscriptions[topic];
    },
    unsubscribeWhere(predicate: (topic: string) => boolean) {
      Object.keys(this.subscriptions)
        .filter(predicate)
        .forEach((topic) => this.unsubscribe(topic));
    },
    unsubscribeAll() {
      Object.keys(this.subscriptions).forEach((topic) => this.unsubscribe(topic));
    },
    async disconnect() {
      this.unsubscribeAll();
      await client?.deactivate();
      client = null;
      this.connected = false;
      this.sessionId = '';
    }
  }
});
