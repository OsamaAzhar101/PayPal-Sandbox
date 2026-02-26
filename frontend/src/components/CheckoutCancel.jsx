import React from 'react';
import { Link } from 'react-router-dom';

function CheckoutCancel() {
  return (
    <div className="cancel">
      <h1>Payment Cancelled</h1>
      <p>You cancelled the PayPal payment.</p>
      <Link to="/">Back to store</Link>
    </div>
  );
}

export default CheckoutCancel;

