import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { DataSourceBanner } from "../components/DataSourceBanner";

/**
 * FR-6.1 and FR-6.2 are requirements about honesty, and the failure mode is a
 * dashboard that quietly stops saying its data is invented. Nothing else in the
 * system would notice, so it is asserted here.
 */
describe("DataSourceBanner", () => {
  afterEach(() => vi.unstubAllGlobals());

  const info = {
    simulated: true,
    headline: "All vehicle data on this dashboard is simulated.",
    details: ["Vehicles are driven along a real OpenStreetMap road network.", "No real people."],
  };

  function stubFetch(response: Promise<unknown>) {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => response.then((body) => ({ ok: true, json: () => Promise.resolve(body) }))),
    );
  }

  it("states plainly that the data is simulated", async () => {
    stubFetch(Promise.resolve(info));
    render(<DataSourceBanner />);

    expect(await screen.findByText(info.headline)).toBeInTheDocument();
    expect(screen.getByText(/simulated data/i)).toBeInTheDocument();
  });

  it("still discloses when the API cannot be reached", async () => {
    // A page that silently omits the disclosure because an endpoint was down is
    // the exact failure FR-6.1 exists to prevent.
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("network down"))));
    render(<DataSourceBanner />);

    // The fallback text, not the API's — specific enough that the badge does not
    // also match and mask a missing headline.
    await waitFor(() =>
      expect(
        screen.getByText(/all vehicle data on this dashboard is simulated/i),
      ).toBeInTheDocument(),
    );
    // And the fallback explanation is there behind the toggle, so the degraded
    // path discloses as fully as the normal one.
    await userEvent.click(screen.getByRole("button", { name: /how this data is generated/i }));
    expect(screen.getByText(/generated, not collected/i)).toBeInTheDocument();
  });

  it("has no dismiss control", async () => {
    stubFetch(Promise.resolve(info));
    render(<DataSourceBanner />);
    await screen.findByText(info.headline);

    // A banner an operator can close is a banner most operators have closed, so
    // the only control expands detail — it never removes the statement.
    const buttons = screen.getAllByRole("button");
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveTextContent(/how this data is generated/i);
  });

  it("expands the full explanation on request", async () => {
    stubFetch(Promise.resolve(info));
    render(<DataSourceBanner />);
    await screen.findByText(info.headline);

    await userEvent.click(screen.getByRole("button", { name: /how this data is generated/i }));

    for (const detail of info.details) {
      expect(screen.getByText(detail)).toBeInTheDocument();
    }
    // The headline stays visible while the detail is open: collapsing the
    // explanation is different from hiding the claim.
    expect(screen.getByText(info.headline)).toBeInTheDocument();
  });
});
