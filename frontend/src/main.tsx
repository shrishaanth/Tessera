import React from "react";
import ReactDOM from "react-dom/client";
import "leaflet/dist/leaflet.css";
import "./theme.css";
import { App } from "./App";

// No auth provider: the reporting API is read-only and unauthenticated, which is
// appropriate for a dashboard over data that is entirely simulated and a stack that
// is not deployed anywhere public. A deployed service would need one.
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
