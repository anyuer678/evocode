// 订单 API 客户端
export const fetchOrders = () => fetch('/api/orders').then((r) => r.json());

export const createOrder = (userId, sku, qty) =>
  fetch('/api/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, sku, qty }),
  }).then((r) => r.json());
