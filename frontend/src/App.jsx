import React from 'react';
import { Routes, Route, Link } from 'react-router-dom';
import ProductList from './components/ProductList';
import CheckoutSuccess from './components/CheckoutSuccess';
import CheckoutCancel from './components/CheckoutCancel';

function App() {
  return (
    <div className="app">
      <header className="header">
        <Link to="/" className="logo">
          Mock Store
        </Link>
      </header>
      <main className="main">
        <Routes>
          <Route path="/" element={<ProductList />} />
          <Route path="/checkout/success" element={<CheckoutSuccess />} />
          <Route path="/checkout/cancel" element={<CheckoutCancel />} />
        </Routes>
      </main>
    </div>
  );
}

export default App;

