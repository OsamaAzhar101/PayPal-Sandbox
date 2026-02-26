const API_BASE = 'http://localhost:8080';

export async function fetchProducts() {
  const res = await fetch(`${API_BASE}/api/products`);
  if (!res.ok) {
    throw new Error('Failed to fetch products');
  }
  return res.json();
}

export async function createOrder(productId) {
  const res = await fetch(`${API_BASE}/api/paypal/create-order`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ productId })
  });
  if (!res.ok) {
    throw new Error('Failed to create order');
  }
  return res.json();
}

export async function captureOrder(orderId) {
  const res = await fetch(`${API_BASE}/api/paypal/capture-order?orderId=${encodeURIComponent(orderId)}`, {
    method: 'POST'
  });
  if (!res.ok) {
    let message = 'Failed to capture order';
    try {
      const data = await res.json();
      if (data?.message) message = data.message;
    } catch (_) {}
    throw new Error(message);
  }
  return res.json();
}

