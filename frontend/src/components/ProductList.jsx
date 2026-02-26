import React, { useEffect, useState } from 'react';
import { createOrder, fetchProducts } from '../api';

function ProductList() {
  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [payingFor, setPayingFor] = useState(null);

  useEffect(() => {
    fetchProducts()
      .then((data) => {
        setProducts(data);
        setLoading(false);
      })
      .catch(() => {
        setError('Failed to load products');
        setLoading(false);
      });
  }, []);

  const handlePayWithPaypal = async (productId) => {
    setError('');
    setPayingFor(productId);
    try {
      const { approvalUrl } = await createOrder(productId);
      window.location.href = approvalUrl;
    } catch (e) {
      setError('Failed to create PayPal order');
      setPayingFor(null);
    }
  };

  if (loading) {
    return <div>Loading products...</div>;
  }

  if (error) {
    return <div className="error">{error}</div>;
  }

  return (
    <div className="product-list">
      <h1>Mock E-Commerce Store</h1>
      <p>Select a product and pay using PayPal Sandbox.</p>
      <div className="grid">
        {products.map((p) => (
          <div key={p.id} className="card">
            <h2>{p.name}</h2>
            <p className="price">${p.price}</p>
            <button
              onClick={() => handlePayWithPaypal(p.id)}
              disabled={payingFor === p.id}
            >
              {payingFor === p.id ? 'Redirecting to PayPal...' : 'Pay with PayPal'}
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

export default ProductList;

