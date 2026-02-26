import React, { useEffect, useState, useRef } from 'react';
import { useSearchParams, Link } from 'react-router-dom';
import { captureOrder } from '../api';

function CheckoutSuccess() {
  const [searchParams] = useSearchParams();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);
  const captureStarted = useRef(false);

  useEffect(() => {
    const orderId = searchParams.get('token');
    if (!orderId) {
      setError('Missing PayPal order token in URL');
      setLoading(false);
      return;
    }
    if (captureStarted.current) return;
    captureStarted.current = true;

    captureOrder(orderId)
      .then((res) => {
        setResult(res);
        setLoading(false);
      })
      .catch((err) => {
        setError(err?.message || 'Failed to capture PayPal order');
        setLoading(false);
      });
  }, [searchParams]);

  if (loading) {
    return <div>Finalizing your payment with PayPal...</div>;
  }

  if (error) {
    return (
      <div className="error">
        <p>{error}</p>
        <Link to="/">Back to store</Link>
      </div>
    );
  }

  return (
    <div className="success">
      <h1>Payment Successful</h1>
      <p>Your product has been purchased successfully.</p>
      <div className="summary">
        <p>
          <strong>Product:</strong> {result.productName}
        </p>
        <p>
          <strong>Paid amount:</strong> {result.amount} {result.currencyCode}
        </p>
        <p>
          <strong>Status from PayPal:</strong> {result.status}
        </p>
      </div>
      <Link to="/">Back to store</Link>
    </div>
  );
}

export default CheckoutSuccess;

